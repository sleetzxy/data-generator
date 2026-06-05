const API = '/api/v1';

const DEFAULT_JOB_TEMPLATE = `job: my_job
tables:
  - name: customers
    schema: schemas/customer.yaml
    count: 100
`;

const DEFAULT_WRITER = {
    type: 'csv',
    connection: 'local-csv',
    mode: 'insert'
};

let editingDefinition = null;
let runsRefreshTimer = null;

document.querySelectorAll('.tab').forEach(tab => {
    tab.addEventListener('click', () => switchTab(tab.dataset.tab));
});

document.getElementById('btn-new-definition').addEventListener('click', () => openDefinitionModal(null));
document.getElementById('btn-refresh-definitions').addEventListener('click', loadDefinitions);
document.getElementById('btn-refresh-runs').addEventListener('click', loadRuns);
document.getElementById('auto-refresh-runs').addEventListener('change', setupRunsAutoRefresh);

document.getElementById('modal-close').addEventListener('click', closeModal);
document.getElementById('modal-cancel').addEventListener('click', closeModal);
document.getElementById('modal-save').addEventListener('click', saveDefinition);
document.querySelector('#modal .modal-backdrop').addEventListener('click', closeModal);

document.getElementById('detail-close').addEventListener('click', closeDetailModal);
document.querySelector('#detail-modal .modal-backdrop').addEventListener('click', closeDetailModal);

function switchTab(name) {
    document.querySelectorAll('.tab').forEach(t => t.classList.toggle('active', t.dataset.tab === name));
    document.querySelectorAll('.panel').forEach(p => p.classList.toggle('active', p.id === `panel-${name}`));
    if (name === 'definitions') {
        loadDefinitions();
    } else {
        loadRuns();
        setupRunsAutoRefresh();
    }
}

function setupRunsAutoRefresh() {
    if (runsRefreshTimer) {
        clearInterval(runsRefreshTimer);
        runsRefreshTimer = null;
    }
    if (document.getElementById('auto-refresh-runs').checked
        && document.getElementById('panel-runs').classList.contains('active')) {
        runsRefreshTimer = setInterval(loadRuns, 5000);
    }
}

async function api(path, options = {}) {
    const response = await fetch(`${API}${path}`, {
        headers: { 'Content-Type': 'application/json', ...options.headers },
        ...options
    });
    if (!response.ok) {
        let message = response.statusText;
        try {
            const body = await response.json();
            message = body.message || body.error || message;
        } catch (_) { /* ignore */ }
        throw new Error(message);
    }
    if (response.status === 204) {
        return null;
    }
    return response.json();
}

function showToast(message) {
    const toast = document.getElementById('toast');
    toast.textContent = message;
    toast.classList.remove('hidden');
    setTimeout(() => toast.classList.add('hidden'), 3000);
}

function statusBadge(status) {
    return `<span class="badge status-${status}">${status}</span>`;
}

function formatTime(iso) {
    if (!iso) return '-';
    try {
        return new Date(iso).toLocaleString('zh-CN');
    } catch (_) {
        return iso;
    }
}

async function loadDefinitions() {
    const tbody = document.getElementById('definitions-body');
    try {
        const items = await api('/job-definitions');
        if (!items.length) {
            tbody.innerHTML = '<tr class="empty-row"><td colspan="4">暂无任务配置</td></tr>';
            return;
        }
        tbody.innerHTML = items.map(item => `
            <tr>
                <td><strong>${escapeHtml(item.name)}</strong></td>
                <td><code>${escapeHtml(item.path)}</code></td>
                <td>${item.readOnly
                    ? '<span class="badge builtin">内置</span>'
                    : '<span class="badge custom">自定义</span>'}</td>
                <td class="actions">
                    <button class="btn small" onclick="viewDefinition('${escapeAttr(item.name)}')">查看</button>
                    <button class="btn small" onclick="editDefinition('${escapeAttr(item.name)}')">编辑</button>
                    <button class="btn small primary" onclick="runDefinition('${escapeAttr(item.path)}')">运行</button>
                    ${item.readOnly ? '' :
                        `<button class="btn small danger" onclick="deleteDefinition('${escapeAttr(item.name)}')">删除</button>`}
                </td>
            </tr>
        `).join('');
    } catch (err) {
        tbody.innerHTML = `<tr class="empty-row"><td colspan="4">加载失败: ${escapeHtml(err.message)}</td></tr>`;
    }
}

async function loadRuns() {
    const tbody = document.getElementById('runs-body');
    try {
        const items = await api('/jobs');
        if (!items.length) {
            tbody.innerHTML = '<tr class="empty-row"><td colspan="7">暂无运行记录</td></tr>';
            return;
        }
        tbody.innerHTML = items.map(item => `
            <tr>
                <td><code>${escapeHtml(item.jobId)}</code></td>
                <td>${escapeHtml(item.jobConfig || '-')}</td>
                <td>${statusBadge(item.status)}</td>
                <td>${formatTime(item.submittedAt)}</td>
                <td>${escapeHtml(item.duration || '-')}</td>
                <td>${item.writtenRows ?? 0} / ${item.totalRows ?? 0}</td>
                <td class="actions">
                    <button class="btn small" onclick="viewRunDetail('${escapeAttr(item.jobId)}')">详情/日志</button>
                    ${item.status === 'PENDING' || item.status === 'RUNNING'
                        ? `<button class="btn small danger" onclick="cancelRun('${escapeAttr(item.jobId)}')">取消</button>`
                        : `<button class="btn small danger" onclick="removeRun('${escapeAttr(item.jobId)}')">删除</button>`}
                </td>
            </tr>
        `).join('');
    } catch (err) {
        tbody.innerHTML = `<tr class="empty-row"><td colspan="7">加载失败: ${escapeHtml(err.message)}</td></tr>`;
    }
}

function openDefinitionModal(name, content) {
    editingDefinition = name;
    document.getElementById('modal-title').textContent = name ? `编辑任务: ${name}` : '新建任务';
    document.getElementById('definition-name').value = name || '';
    document.getElementById('definition-name').disabled = !!name;
    document.getElementById('name-field').style.display = name ? 'none' : 'flex';
    document.getElementById('definition-content').value = content || DEFAULT_JOB_TEMPLATE;
    document.getElementById('modal').classList.remove('hidden');
}

function closeModal() {
    document.getElementById('modal').classList.add('hidden');
    editingDefinition = null;
}

async function viewDefinition(name) {
    try {
        const item = await api(`/job-definitions/${encodeURIComponent(name)}`);
        openDefinitionModal(name, item.content);
        document.getElementById('modal-save').style.display = item.readOnly ? 'none' : '';
    } catch (err) {
        showToast('加载失败: ' + err.message);
    }
}

async function editDefinition(name) {
    try {
        const item = await api(`/job-definitions/${encodeURIComponent(name)}`);
        openDefinitionModal(name, item.content);
        document.getElementById('modal-save').style.display = '';
        if (item.readOnly) {
            showToast('内置任务编辑后将保存为自定义覆盖版本');
        }
    } catch (err) {
        showToast('加载失败: ' + err.message);
    }
}

async function saveDefinition() {
    const name = editingDefinition || document.getElementById('definition-name').value.trim();
    const content = document.getElementById('definition-content').value;
    if (!name) {
        showToast('请输入任务名称');
        return;
    }
    try {
        if (editingDefinition) {
            await api(`/job-definitions/${encodeURIComponent(editingDefinition)}`, {
                method: 'PUT',
                body: JSON.stringify({ name: editingDefinition, content })
            });
            showToast('任务已更新');
        } else {
            await api('/job-definitions', {
                method: 'POST',
                body: JSON.stringify({ name, content })
            });
            showToast('任务已创建');
        }
        closeModal();
        loadDefinitions();
    } catch (err) {
        showToast('保存失败: ' + err.message);
    }
}

async function deleteDefinition(name) {
    if (!confirm(`确定删除任务配置 "${name}"？`)) return;
    try {
        await api(`/job-definitions/${encodeURIComponent(name)}`, { method: 'DELETE' });
        showToast('已删除');
        loadDefinitions();
    } catch (err) {
        showToast('删除失败: ' + err.message);
    }
}

async function runDefinition(path) {
    try {
        const result = await api('/jobs', {
            method: 'POST',
            body: JSON.stringify({ jobConfig: path, writer: DEFAULT_WRITER })
        });
        showToast(`任务已提交: ${result.jobId} (${result.status})`);
        switchTab('runs');
    } catch (err) {
        showToast('提交失败: ' + err.message);
    }
}

async function viewRunDetail(jobId) {
    try {
        const [job, logs] = await Promise.all([
            api(`/jobs/${encodeURIComponent(jobId)}`),
            api(`/jobs/${encodeURIComponent(jobId)}/logs`)
        ]);
        document.getElementById('detail-title').textContent = `运行详情: ${jobId}`;
        const progress = job.progress || {};
        document.getElementById('detail-summary').innerHTML = `
            <div class="detail-item"><label>状态</label>${statusBadge(job.status)}</div>
            <div class="detail-item"><label>配置文件</label>${escapeHtml(job.jobConfig || '-')}</div>
            <div class="detail-item"><label>提交时间</label>${formatTime(job.submittedAt)}</div>
            <div class="detail-item"><label>耗时</label>${escapeHtml(job.duration || '-')}</div>
            <div class="detail-item"><label>写入行数</label>${progress.writtenRows ?? 0} / ${progress.totalRows ?? 0}</div>
            ${job.errorMessage ? `<div class="detail-item"><label>错误</label>${escapeHtml(job.errorMessage)}</div>` : ''}
        `;
        document.getElementById('detail-logs').innerHTML = logs.length
            ? logs.map(entry => `<span class="log-line-${entry.level}">[${entry.timestamp}] ${entry.level} ${escapeHtml(entry.message)}</span>`).join('\n')
            : '暂无日志';
        document.getElementById('detail-modal').classList.remove('hidden');
    } catch (err) {
        showToast('加载详情失败: ' + err.message);
    }
}

function closeDetailModal() {
    document.getElementById('detail-modal').classList.add('hidden');
}

async function cancelRun(jobId) {
    if (!confirm(`确定取消任务 ${jobId}？`)) return;
    try {
        await api(`/jobs/${encodeURIComponent(jobId)}`, { method: 'DELETE' });
        showToast('任务已取消');
        loadRuns();
    } catch (err) {
        showToast('取消失败: ' + err.message);
    }
}

async function removeRun(jobId) {
    if (!confirm(`确定删除运行记录 ${jobId}？`)) return;
    try {
        await api(`/jobs/${encodeURIComponent(jobId)}/record`, { method: 'DELETE' });
        showToast('记录已删除');
        loadRuns();
    } catch (err) {
        showToast('删除失败: ' + err.message);
    }
}

function escapeHtml(text) {
    if (text == null) return '';
    return String(text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

function escapeAttr(text) {
    return escapeHtml(text).replace(/'/g, '&#39;');
}

loadDefinitions();

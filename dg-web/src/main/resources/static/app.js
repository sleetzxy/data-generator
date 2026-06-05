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
let refreshTimer = null;
let allRunsCache = [];
let logModalContext = {
    definitionName: null,
    definitionPath: null,
    runs: [],
    selectedJobId: null,
    view: 'list'
};

document.getElementById('btn-new-definition').addEventListener('click', () => openDefinitionModal(null));
document.getElementById('btn-refresh-definitions').addEventListener('click', loadDefinitions);
document.getElementById('auto-refresh').addEventListener('change', setupAutoRefresh);

document.getElementById('modal-close').addEventListener('click', closeModal);
document.getElementById('modal-cancel').addEventListener('click', closeModal);
document.getElementById('modal-save').addEventListener('click', saveDefinition);
document.querySelector('#modal .modal-backdrop').addEventListener('click', closeModal);

document.getElementById('log-close').addEventListener('click', closeLogModal);
document.querySelector('#log-modal .modal-backdrop').addEventListener('click', closeLogModal);
document.getElementById('log-back').addEventListener('click', showLogListView);
document.getElementById('log-stop-run').addEventListener('click', () => {
    if (logModalContext.selectedJobId) {
        stopRun(logModalContext.selectedJobId);
    }
});

function setupAutoRefresh() {
    if (refreshTimer) {
        clearInterval(refreshTimer);
    }
    refreshTimer = null;
    if (document.getElementById('auto-refresh').checked) {
        refreshTimer = setInterval(loadDefinitions, 5000);
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
    if (!status) {
        return '<span class="badge status-none">未运行</span>';
    }
    return `<span class="badge status-${status}">${status}</span>`;
}

function isActiveRun(status) {
    return status === 'PENDING' || status === 'RUNNING';
}

function formatTime(iso) {
    if (!iso) return '-';
    try {
        return new Date(iso).toLocaleString('zh-CN');
    } catch (_) {
        return iso;
    }
}

function renderLogLines(logs) {
    if (!logs.length) {
        return '暂无日志';
    }
    return logs.map(entry =>
        `<span class="log-line-${entry.level}">[${entry.timestamp}] ${entry.level} ${escapeHtml(entry.message)}</span>`
    ).join('\n');
}

function findLatestRun(path) {
    return allRunsCache
        .filter(run => run.jobConfig === path)
        .sort((a, b) => new Date(b.submittedAt) - new Date(a.submittedAt))[0] || null;
}

async function loadDefinitions() {
    const tbody = document.getElementById('definitions-body');
    try {
        const [items, runs] = await Promise.all([
            api('/job-definitions'),
            api('/jobs')
        ]);
        allRunsCache = runs;

        if (!items.length) {
            tbody.innerHTML = '<tr class="empty-row"><td colspan="5">暂无任务配置</td></tr>';
            refreshOpenLogModal();
            return;
        }

        tbody.innerHTML = items.map(item => {
            const latestRun = findLatestRun(item.path);
            return `
            <tr>
                <td><strong>${escapeHtml(item.name)}</strong></td>
                <td><code>${escapeHtml(item.path)}</code></td>
                <td>${item.readOnly
                    ? '<span class="badge builtin">内置</span>'
                    : '<span class="badge custom">自定义</span>'}</td>
                <td>${statusBadge(latestRun?.status)}</td>
                <td class="actions">
                    <button class="btn small" onclick="viewDefinition('${escapeAttr(item.name)}')">查看</button>
                    <button class="btn small" onclick="editDefinition('${escapeAttr(item.name)}')">编辑</button>
                    <button class="btn small primary" onclick="runDefinition('${escapeAttr(item.path)}', '${escapeAttr(item.name)}')">运行</button>
                    <button class="btn small log-btn" onclick="viewDefinitionLogs('${escapeAttr(item.name)}', '${escapeAttr(item.path)}')">日志</button>
                    ${item.readOnly ? '' :
                        `<button class="btn small danger" onclick="deleteDefinition('${escapeAttr(item.name)}')">删除</button>`}
                </td>
            </tr>`;
        }).join('');

        refreshOpenLogModal();
    } catch (err) {
        tbody.innerHTML = `<tr class="empty-row"><td colspan="5">加载失败: ${escapeHtml(err.message)}</td></tr>`;
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

async function runDefinition(path, name) {
    try {
        const result = await api('/jobs', {
            method: 'POST',
            body: JSON.stringify({ jobConfig: path, writer: DEFAULT_WRITER })
        });
        showToast(`任务已提交: ${result.jobId} (${result.status})`);
        await loadDefinitions();
        openLogListModal(name, path);
        if (isActiveRun(result.status)) {
            openRunLogDetail(result.jobId);
        }
    } catch (err) {
        showToast('提交失败: ' + err.message);
    }
}

async function viewDefinitionLogs(name, path) {
    try {
        if (!allRunsCache.length) {
            allRunsCache = await api('/jobs');
        }
        const runs = allRunsCache
            .filter(run => run.jobConfig === path)
            .sort((a, b) => new Date(b.submittedAt) - new Date(a.submittedAt));
        if (!runs.length) {
            showToast(`任务 "${name}" 暂无运行记录`);
            return;
        }
        openLogListModal(name, path);
    } catch (err) {
        showToast('加载失败: ' + err.message);
    }
}

function renderLogRunsTable(runs) {
    const tbody = document.getElementById('log-runs-body');
    if (!runs.length) {
        tbody.innerHTML = '<tr class="empty-row"><td colspan="6">暂无运行记录</td></tr>';
        return;
    }
    tbody.innerHTML = runs.map(run => `
        <tr class="log-run-row" onclick="openRunLogDetail('${escapeAttr(run.jobId)}')">
            <td><code>${escapeHtml(run.jobId)}</code></td>
            <td>${statusBadge(run.status)}</td>
            <td>${formatTime(run.submittedAt)}</td>
            <td>${escapeHtml(run.duration || '-')}</td>
            <td>${run.writtenRows ?? 0} / ${run.totalRows ?? 0}</td>
            <td class="actions" onclick="event.stopPropagation()">
                ${isActiveRun(run.status)
                    ? `<button class="btn small danger" onclick="stopRun('${escapeAttr(run.jobId)}')">停止</button>`
                    : '-'}
            </td>
        </tr>
    `).join('');
}

function openLogListModal(name, path) {
    const runs = allRunsCache
        .filter(run => run.jobConfig === path)
        .sort((a, b) => new Date(b.submittedAt) - new Date(a.submittedAt));

    logModalContext = {
        definitionName: name,
        definitionPath: path,
        runs,
        selectedJobId: null,
        view: 'list'
    };

    document.getElementById('log-title').textContent = `运行记录: ${name}`;
    renderLogRunsTable(runs);
    showLogListView();
    document.getElementById('log-modal').classList.remove('hidden');
}

function showLogListView() {
    logModalContext.view = 'list';
    logModalContext.selectedJobId = null;
    document.getElementById('log-list-view').classList.remove('hidden');
    document.getElementById('log-detail-view').classList.add('hidden');
    document.getElementById('log-back').classList.add('hidden');
    document.getElementById('log-stop-run').classList.add('hidden');
    if (logModalContext.definitionName) {
        document.getElementById('log-title').textContent = `运行记录: ${logModalContext.definitionName}`;
    }
}

async function openRunLogDetail(jobId) {
    logModalContext.view = 'detail';
    logModalContext.selectedJobId = jobId;

    document.getElementById('log-list-view').classList.add('hidden');
    document.getElementById('log-detail-view').classList.remove('hidden');
    document.getElementById('log-back').classList.remove('hidden');
    document.getElementById('log-title').textContent = `执行日志: ${jobId}`;

    const logContent = document.getElementById('log-content');
    logContent.textContent = '加载中...';

    try {
        const job = await api(`/jobs/${encodeURIComponent(jobId)}`);
        const run = logModalContext.runs.find(item => item.jobId === jobId) || job;
        const progress = job.progress || {};

        document.getElementById('log-run-summary').innerHTML = `
            <div class="detail-item"><label>状态</label>${statusBadge(job.status)}</div>
            <div class="detail-item"><label>提交时间</label>${formatTime(job.submittedAt)}</div>
            <div class="detail-item"><label>耗时</label>${escapeHtml(job.duration || '-')}</div>
            <div class="detail-item"><label>写入行数</label>${progress.writtenRows ?? 0} / ${progress.totalRows ?? 0}</div>
            ${job.errorMessage ? `<div class="detail-item detail-item-wide"><label>错误</label>${escapeHtml(job.errorMessage)}</div>` : ''}
        `;

        document.getElementById('log-stop-run').classList.toggle('hidden', !isActiveRun(job.status));

        const logs = await api(`/jobs/${encodeURIComponent(jobId)}/logs`);
        logContent.innerHTML = renderLogLines(logs);

        if (run && run.status !== job.status) {
            run.status = job.status;
        }
    } catch (err) {
        logContent.textContent = '加载失败: ' + err.message;
        document.getElementById('log-stop-run').classList.add('hidden');
    }
}

async function refreshOpenLogModal() {
    if (document.getElementById('log-modal').classList.contains('hidden') || !logModalContext.definitionPath) {
        return;
    }

    const runs = allRunsCache
        .filter(run => run.jobConfig === logModalContext.definitionPath)
        .sort((a, b) => new Date(b.submittedAt) - new Date(a.submittedAt));

    if (!runs.length) {
        closeLogModal();
        return;
    }

    logModalContext.runs = runs;

    if (logModalContext.view === 'list') {
        renderLogRunsTable(runs);
        return;
    }

    if (logModalContext.selectedJobId) {
        await openRunLogDetail(logModalContext.selectedJobId);
    }
}

function closeLogModal() {
    document.getElementById('log-modal').classList.add('hidden');
    logModalContext = {
        definitionName: null,
        definitionPath: null,
        runs: [],
        selectedJobId: null,
        view: 'list'
    };
    showLogListView();
}

async function stopRun(jobId) {
    if (!confirm(`确定停止任务 ${jobId}？`)) return;
    try {
        await api(`/jobs/${encodeURIComponent(jobId)}`, { method: 'DELETE' });
        showToast('任务已停止');
        await loadDefinitions();
    } catch (err) {
        showToast('停止失败: ' + err.message);
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
setupAutoRefresh();

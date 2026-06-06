const API = '/api/v1';
const LOG_PAGE_SIZE = 10;

const DEFAULT_JOB_TEMPLATE = `id: my_job
name: 我的测试任务
writer:
  type: csv
  connection: local-csv
  mode: insert
tables:
  - name: customers
    count: 100
    schema:
      table: customers
      fields:
        - name: id
          type: BIGINT
          generator: { strategy: sequence, start: 1, step: 1 }
        - name: name
          type: VARCHAR
          generator: { strategy: random, type: string, length: 10 }
`;

let editingDefinition = null;
let allRunsCache = [];
let logModalContext = {
    definitionName: null,
    definitionPath: null,
    runs: [],
    page: 1,
    selectedJobId: null
};

document.getElementById('btn-new-definition').addEventListener('click', () => openDefinitionModal(null));
document.getElementById('btn-refresh-definitions').addEventListener('click', loadDefinitions);

document.getElementById('modal-close').addEventListener('click', closeModal);
document.getElementById('modal-cancel').addEventListener('click', closeModal);
document.getElementById('modal-save').addEventListener('click', saveDefinition);
document.querySelector('#modal .modal-backdrop').addEventListener('click', closeModal);

document.getElementById('log-close').addEventListener('click', closeLogModal);
document.querySelector('#log-modal .modal-backdrop').addEventListener('click', closeLogModal);

async function api(path, options = {}) {
    const method = (options.method || 'GET').toUpperCase();
    const headers = { 'Content-Type': 'application/json', ...options.headers };
    if (method !== 'GET' && method !== 'HEAD' && method !== 'OPTIONS' && typeof getCsrfToken === 'function') {
        const csrfToken = getCsrfToken();
        if (csrfToken) {
            headers['X-XSRF-TOKEN'] = csrfToken;
        }
    }
    const response = await fetch(`${API}${path}`, {
        credentials: 'same-origin',
        headers,
        ...options
    });
    if (response.status === 401) {
        window.location.href = '/login.html';
        return;
    }
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

function findActiveRun(path) {
    return allRunsCache
        .filter(run => run.jobConfig === path && isActiveRun(run.status))
        .sort((a, b) => new Date(b.submittedAt) - new Date(a.submittedAt))[0] || null;
}

async function fetchAllJobs() {
    const all = [];
    let page = 1;
    const size = 100;
    while (true) {
        const data = await api(`/jobs?page=${page}&size=${size}`);
        all.push(...(data.items || []));
        if (all.length >= data.total || !data.items?.length) {
            break;
        }
        page++;
    }
    return all;
}

async function loadDefinitions() {
    const tbody = document.getElementById('definitions-body');
    try {
        const [items, runs] = await Promise.all([
            api('/job-definitions'),
            fetchAllJobs()
        ]);
        allRunsCache = runs;

        if (!items.length) {
            tbody.innerHTML = '<tr class="empty-row"><td colspan="6">暂无任务配置</td></tr>';
            refreshOpenLogModal();
            return;
        }

        tbody.innerHTML = items.map(item => {
            const latestRun = findLatestRun(item.path);
            const activeRun = findActiveRun(item.path);
            return `
            <tr>
                <td><code>${escapeHtml(item.id || '-')}</code></td>
                <td><strong>${escapeHtml(item.name)}</strong></td>
                <td><code>${escapeHtml(item.path)}</code></td>
                <td>${item.readOnly
                    ? '<span class="badge builtin">内置</span>'
                    : '<span class="badge custom">自定义</span>'}</td>
                <td>${statusBadge(latestRun?.status)}</td>
                <td class="actions">
                    <button class="btn small" onclick="viewDefinition('${escapeAttr(item.name)}')">查看</button>
                    <button class="btn small" onclick="editDefinition('${escapeAttr(item.name)}')">编辑</button>
                    <button class="btn small primary" onclick="runDefinition('${escapeAttr(item.path)}')">运行</button>
                    <button class="btn small danger"
                        ${activeRun ? `onclick="stopRun('${escapeAttr(activeRun.jobId)}')"` : 'disabled title="当前无运行中的任务"'}
                    >停止</button>
                    <button class="btn small log-btn" onclick="viewDefinitionLogs('${escapeAttr(item.name)}', '${escapeAttr(item.path)}')">日志</button>
                    ${item.readOnly ? '' :
                        `<button class="btn small danger" onclick="deleteDefinition('${escapeAttr(item.name)}')">删除</button>`}
                </td>
            </tr>`;
        }).join('');

        refreshOpenLogModal();
    } catch (err) {
        tbody.innerHTML = `<tr class="empty-row"><td colspan="6">加载失败: ${escapeHtml(err.message)}</td></tr>`;
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
            body: JSON.stringify({ jobConfig: path })
        });
        showToast(`任务已提交: ${result.jobId} (${result.status})`);
        await loadDefinitions();
    } catch (err) {
        showToast('提交失败: ' + err.message);
    }
}

async function viewDefinitionLogs(name, path) {
    try {
        if (!allRunsCache.length) {
            allRunsCache = await fetchAllJobs();
        }
        const runs = allRunsCache
            .filter(run => run.jobConfig === path)
            .sort((a, b) => new Date(b.submittedAt) - new Date(a.submittedAt));
        if (!runs.length) {
            showToast(`任务 "${name}" 暂无运行记录`);
            return;
        }
        openLogListModal(name, path, runs);
    } catch (err) {
        showToast('加载失败: ' + err.message);
    }
}

function getLogTotalPages(runCount) {
    return Math.max(1, Math.ceil(runCount / LOG_PAGE_SIZE));
}

function getPagedRuns(runs, page) {
    const start = (page - 1) * LOG_PAGE_SIZE;
    return runs.slice(start, start + LOG_PAGE_SIZE);
}

function isSelectedRunOnCurrentPage() {
    if (!logModalContext.selectedJobId) {
        return false;
    }
    return getPagedRuns(logModalContext.runs, logModalContext.page)
        .some(run => run.jobId === logModalContext.selectedJobId);
}

function renderLogPagination() {
    const pagination = document.getElementById('log-pagination');
    const totalPages = getLogTotalPages(logModalContext.runs.length);
    const { page, runs } = logModalContext;

    if (runs.length <= LOG_PAGE_SIZE) {
        pagination.classList.add('hidden');
        pagination.innerHTML = '';
        return;
    }

    pagination.classList.remove('hidden');
    pagination.innerHTML = `
        <button class="btn small" ${page <= 1 ? 'disabled' : ''} onclick="changeLogPage(${page - 1})">上一页</button>
        <span class="pagination-info">第 ${page} / ${totalPages} 页，共 ${runs.length} 条</span>
        <button class="btn small" ${page >= totalPages ? 'disabled' : ''} onclick="changeLogPage(${page + 1})">下一页</button>
    `;
}

function renderLogRunsTable() {
    const tbody = document.getElementById('log-runs-body');
    const { runs } = logModalContext;
    const totalPages = getLogTotalPages(runs.length);

    if (logModalContext.page > totalPages) {
        logModalContext.page = totalPages;
    }
    if (logModalContext.page < 1) {
        logModalContext.page = 1;
    }

    if (!runs.length) {
        tbody.innerHTML = '<tr class="empty-row"><td colspan="5">暂无运行记录</td></tr>';
        renderLogPagination();
        return;
    }

    const pagedRuns = getPagedRuns(runs, logModalContext.page);
    const selectedOnPage = isSelectedRunOnCurrentPage();
    if (!selectedOnPage) {
        logModalContext.selectedJobId = null;
    }

    let html = '';
    for (const run of pagedRuns) {
        const expanded = logModalContext.selectedJobId === run.jobId;
        html += `
        <tr class="log-run-row${expanded ? ' selected' : ''}" onclick="toggleRunLogDetail('${escapeAttr(run.jobId)}')">
            <td><code>${escapeHtml(run.jobId)}</code></td>
            <td>${statusBadge(run.status)}</td>
            <td>${formatTime(run.submittedAt)}</td>
            <td>${escapeHtml(run.duration || '-')}</td>
            <td>${run.writtenRows ?? 0} / ${run.totalRows ?? 0}</td>
        </tr>`;
        if (expanded) {
            html += `
        <tr class="log-detail-row">
            <td colspan="5">
                <div class="log-detail-panel" data-log-panel="${escapeAttr(run.jobId)}">加载中...</div>
            </td>
        </tr>`;
        }
    }
    tbody.innerHTML = html;
    renderLogPagination();

    if (logModalContext.selectedJobId) {
        loadRunLogDetailContent(logModalContext.selectedJobId);
    }
}

function openLogListModal(name, path, runs) {
    logModalContext = {
        definitionName: name,
        definitionPath: path,
        runs,
        page: 1,
        selectedJobId: null
    };

    document.getElementById('log-title').textContent = `运行记录: ${name}`;
    renderLogRunsTable();
    document.getElementById('log-modal').classList.remove('hidden');
}

function changeLogPage(page) {
    const totalPages = getLogTotalPages(logModalContext.runs.length);
    if (page < 1 || page > totalPages) {
        return;
    }
    logModalContext.page = page;
    logModalContext.selectedJobId = null;
    renderLogRunsTable();
}

function toggleRunLogDetail(jobId) {
    logModalContext.selectedJobId = logModalContext.selectedJobId === jobId ? null : jobId;
    renderLogRunsTable();
}

async function loadRunLogDetailContent(jobId) {
    const panel = document.querySelector(`[data-log-panel="${cssEscape(jobId)}"]`);
    if (!panel) {
        return;
    }
    panel.textContent = '加载中...';

    try {
        const job = await api(`/jobs/${encodeURIComponent(jobId)}`);
        const run = logModalContext.runs.find(item => item.jobId === jobId) || job;
        const progress = job.progress || {};

        const logs = await api(`/jobs/${encodeURIComponent(jobId)}/logs`);
        panel.innerHTML = `
            <div class="detail-summary log-detail-summary">
                <div class="detail-item"><label>状态</label>${statusBadge(job.status)}</div>
                <div class="detail-item"><label>提交时间</label>${formatTime(job.submittedAt)}</div>
                <div class="detail-item"><label>耗时</label>${escapeHtml(job.duration || '-')}</div>
                <div class="detail-item"><label>写入行数</label>${progress.writtenRows ?? 0} / ${progress.totalRows ?? 0}</div>
                ${job.errorMessage ? `<div class="detail-item detail-item-wide"><label>错误</label>${escapeHtml(job.errorMessage)}</div>` : ''}
            </div>
            <pre class="log-view">${renderLogLines(logs)}</pre>
        `;

        if (run && run.status !== job.status) {
            run.status = job.status;
        }
    } catch (err) {
        panel.textContent = '加载失败: ' + err.message;
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

    const selectedJobId = logModalContext.selectedJobId;
    logModalContext.runs = runs;
    renderLogRunsTable();

    if (selectedJobId && logModalContext.selectedJobId === selectedJobId) {
        await loadRunLogDetailContent(selectedJobId);
    }
}

function closeLogModal() {
    document.getElementById('log-modal').classList.add('hidden');
    logModalContext = {
        definitionName: null,
        definitionPath: null,
        runs: [],
        page: 1,
        selectedJobId: null
    };
    document.getElementById('log-runs-body').innerHTML = '';
    document.getElementById('log-pagination').classList.add('hidden');
    document.getElementById('log-pagination').innerHTML = '';
}

async function stopRun(jobId) {
    if (!jobId) {
        showToast('当前无运行中的任务');
        return;
    }
    if (!confirm(`确定停止任务 ${jobId}？`)) return;
    try {
        const runs = await fetchAllJobs();
        allRunsCache = runs;
        const current = runs.find(run => run.jobId === jobId);
        if (!current || !isActiveRun(current.status)) {
            showToast('任务已结束');
            await loadDefinitions();
            return;
        }
        await api(`/jobs/${encodeURIComponent(jobId)}`, { method: 'DELETE' });
        showToast('任务已停止');
        await loadDefinitions();
    } catch (err) {
        showToast('停止失败: ' + err.message);
    }
}

function cssEscape(value) {
    if (window.CSS && typeof window.CSS.escape === 'function') {
        return window.CSS.escape(value);
    }
    return String(value).replace(/\\/g, '\\\\').replace(/"/g, '\\"');
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

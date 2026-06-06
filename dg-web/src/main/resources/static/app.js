const API = '/api/v1';
const LOG_PAGE_SIZE = 10;
const LOG_LINES_PAGE_SIZE = 50;
const DEFINITION_PAGE_SIZE = 20;
const PREVIEW_PAGE_SIZE = 10;
const PREVIEW_FETCH_LIMIT = 10;

const DEFAULT_JOB_TEMPLATE = `writer:
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
let editingScheduleEditable = false;
let allRunsCache = [];
let definitionsCache = [];
let definitionsPage = 1;
let previewContext = {
    displayName: null,
    path: null,
    rows: [],
    columns: [],
    page: 1
};
let logModalContext = {
    definitionName: null,
    definitionPath: null,
    runs: [],
    page: 1,
    selectedJobId: null,
    logDetailPages: {},
    logDetailLines: {}
};

document.getElementById('btn-new-definition').addEventListener('click', () => openDefinitionModal(null));
document.getElementById('btn-refresh-definitions').addEventListener('click', loadDefinitions);

document.getElementById('modal-close').addEventListener('click', closeModal);
document.getElementById('modal-cancel').addEventListener('click', closeModal);
document.getElementById('modal-save').addEventListener('click', saveDefinition);
document.querySelector('#modal .modal-backdrop').addEventListener('click', closeModal);

document.getElementById('log-close').addEventListener('click', closeLogModal);
document.querySelector('#log-modal .modal-backdrop').addEventListener('click', closeLogModal);

document.getElementById('preview-close').addEventListener('click', closePreviewModal);
document.querySelector('#preview-modal .modal-backdrop').addEventListener('click', closePreviewModal);

document.addEventListener('click', (event) => {
    if (!event.target.closest('.action-menu')) {
        closeActionMenus();
    }
});

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

function renderScheduleCron(schedule) {
    if (!schedule || !schedule.enabled || !schedule.cron) {
        return '<span class="muted">—</span>';
    }
    return `<code>${escapeHtml(schedule.cron)}</code>`;
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

function renderLogLines(logs, page = 1) {
    if (!logs.length) {
        return '暂无日志';
    }
    const start = (page - 1) * LOG_LINES_PAGE_SIZE;
    const pagedLogs = logs.slice(start, start + LOG_LINES_PAGE_SIZE);
    return pagedLogs.map(entry =>
        `<span class="log-line-${entry.level}">[${entry.timestamp}] ${entry.level} ${escapeHtml(entry.message)}</span>`
    ).join('\n');
}

function renderLogDetailPagination(jobId, totalLines, page) {
    const totalPages = Math.max(1, Math.ceil(totalLines / LOG_LINES_PAGE_SIZE));
    if (totalLines <= LOG_LINES_PAGE_SIZE) {
        return '';
    }
    return `
        <div class="pagination log-detail-pagination">
            <button type="button" class="btn small" ${page <= 1 ? 'disabled' : ''} onclick="changeLogDetailPage('${escapeAttr(jobId)}', ${page - 1})">上一页</button>
            <span class="pagination-info">第 ${page} / ${totalPages} 页，共 ${totalLines} 条</span>
            <button type="button" class="btn small" ${page >= totalPages ? 'disabled' : ''} onclick="changeLogDetailPage('${escapeAttr(jobId)}', ${page + 1})">下一页</button>
        </div>
    `;
}

function changeLogDetailPage(jobId, page) {
    const logs = logModalContext.logDetailLines[jobId] || [];
    const totalPages = Math.max(1, Math.ceil(logs.length / LOG_LINES_PAGE_SIZE));
    if (page < 1 || page > totalPages) {
        return;
    }
    logModalContext.logDetailPages[jobId] = page;
    const panel = document.querySelector(`[data-log-panel="${cssEscape(jobId)}"]`);
    if (!panel) {
        return;
    }
    const pre = panel.querySelector('.log-view');
    if (pre) {
        pre.innerHTML = renderLogLines(logs, page);
    }
    const pagination = panel.querySelector('.log-detail-pagination');
    if (pagination) {
        pagination.outerHTML = renderLogDetailPagination(jobId, logs.length, page);
    }
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
        definitionsCache = items;
        renderDefinitionsTable();
    } catch (err) {
        tbody.innerHTML = `<tr class="empty-row"><td colspan="6">加载失败: ${escapeHtml(err.message)}</td></tr>`;
        renderDefinitionsPagination();
    }
}

function getDefinitionTotalPages() {
    return Math.max(1, Math.ceil(definitionsCache.length / DEFINITION_PAGE_SIZE));
}

function getPagedDefinitions() {
    const start = (definitionsPage - 1) * DEFINITION_PAGE_SIZE;
    return definitionsCache.slice(start, start + DEFINITION_PAGE_SIZE);
}

function renderDefinitionsPagination() {
    const pagination = document.getElementById('definitions-pagination');
    const totalPages = getDefinitionTotalPages();
    const total = definitionsCache.length;

    if (total <= DEFINITION_PAGE_SIZE) {
        pagination.classList.add('hidden');
        pagination.innerHTML = '';
        return;
    }

    pagination.classList.remove('hidden');
    pagination.innerHTML = `
        <button type="button" class="btn small" ${definitionsPage <= 1 ? 'disabled' : ''} onclick="changeDefinitionsPage(${definitionsPage - 1})">上一页</button>
        <span class="pagination-info">第 ${definitionsPage} / ${totalPages} 页，共 ${total} 条</span>
        <button type="button" class="btn small" ${definitionsPage >= totalPages ? 'disabled' : ''} onclick="changeDefinitionsPage(${definitionsPage + 1})">下一页</button>
    `;
}

function changeDefinitionsPage(page) {
    const totalPages = getDefinitionTotalPages();
    if (page < 1 || page > totalPages) {
        return;
    }
    definitionsPage = page;
    renderDefinitionsTable();
}

function renderDefinitionsTable() {
    const tbody = document.getElementById('definitions-body');
    const totalPages = getDefinitionTotalPages();

    if (definitionsPage > totalPages) {
        definitionsPage = totalPages;
    }
    if (definitionsPage < 1) {
        definitionsPage = 1;
    }

    if (!definitionsCache.length) {
        tbody.innerHTML = '<tr class="empty-row"><td colspan="6">暂无任务配置</td></tr>';
        renderDefinitionsPagination();
        refreshOpenLogModal();
        return;
    }

    const pagedItems = getPagedDefinitions();
    const startIndex = (definitionsPage - 1) * DEFINITION_PAGE_SIZE;
    tbody.innerHTML = pagedItems.map((item, index) => {
        const latestRun = findLatestRun(item.path);
        const activeRun = findActiveRun(item.path);
        const fileName = item.fileName;
        const displayName = item.name || fileName;
        const isBuiltin = item.builtin === true || item.readOnly === true;
        const scheduleEnabled = item.schedule?.enabled === true;
        const rowIndex = startIndex + index;
        return `
            <tr>
                <td><code>${escapeHtml(item.id || '-')}</code></td>
                <td>${escapeHtml(displayName)}</td>
                <td>${isBuiltin
                    ? '<span class="badge builtin">内置</span>'
                    : '<span class="badge custom">自定义</span>'}</td>
                <td>${renderScheduleCron(item.schedule)}</td>
                <td>${statusBadge(latestRun?.status)}</td>
                <td class="actions">${renderActionsCell(item, rowIndex, displayName, fileName, item.path, activeRun, isBuiltin, scheduleEnabled)}</td>
            </tr>`;
    }).join('');

    renderDefinitionsPagination();
    refreshOpenLogModal();
}

function renderActionsCell(item, index, displayName, fileName, path, activeRun, isBuiltin, scheduleEnabled) {
    const menuId = `action-menu-${index}`;
    const stopDisabled = !activeRun;
    const stopJobId = activeRun?.jobId || '';
    let menuItems = `
        <button type="button" class="action-menu-item" onclick="viewDefinition('${escapeAttr(fileName)}'); closeActionMenus()">查看</button>
        <button type="button" class="action-menu-item" onclick="previewDefinition('${escapeAttr(displayName)}', '${escapeAttr(path)}'); closeActionMenus()">预览</button>
        <button type="button" class="action-menu-item" onclick="viewDefinitionLogs('${escapeAttr(displayName)}', '${escapeAttr(path)}'); closeActionMenus()">日志</button>
        <button type="button" class="action-menu-item${stopDisabled ? ' disabled' : ''}"
            ${stopDisabled ? 'disabled title="当前无运行中的任务"' : `onclick="stopRun('${escapeAttr(stopJobId)}'); closeActionMenus()"`}>停止</button>`;
    if (!isBuiltin) {
        menuItems += `
        <button type="button" class="action-menu-item" onclick="editDefinition('${escapeAttr(fileName)}'); closeActionMenus()">编辑</button>
        <button type="button" class="action-menu-item danger" onclick="deleteDefinition('${escapeAttr(fileName)}'); closeActionMenus()">删除</button>`;
    }
    return `
        <button type="button" class="btn small primary" onclick="runDefinition('${escapeAttr(path)}', ${scheduleEnabled})">运行</button>
        <div class="action-menu">
            <button type="button" class="btn small action-menu-toggle" onclick="toggleActionMenu(event, '${menuId}')">更多</button>
            <div id="${menuId}" class="action-menu-dropdown hidden">${menuItems}</div>
        </div>`;
}

function toggleActionMenu(event, menuId) {
    event.stopPropagation();
    const menu = document.getElementById(menuId);
    const wasOpen = !menu.classList.contains('hidden');
    closeActionMenus();
    if (!wasOpen) {
        menu.classList.remove('hidden');
    }
}

function closeActionMenus() {
    document.querySelectorAll('.action-menu-dropdown').forEach(el => el.classList.add('hidden'));
}

function openDefinitionModal(fileName, content, readOnly = false, schedule = null, displayName = null) {
    editingDefinition = fileName || null;
    const title = fileName
        ? (readOnly ? `查看任务: ${displayName || fileName}` : `编辑任务: ${displayName || fileName}`)
        : '新建任务';
    document.getElementById('modal-title').textContent = title;
    document.getElementById('definition-name').value = displayName || fileName || '';
    document.getElementById('definition-name').disabled = !!readOnly;
    document.getElementById('name-field').style.display = 'flex';
    document.getElementById('definition-content').value = content || DEFAULT_JOB_TEMPLATE;
    document.getElementById('definition-content').readOnly = !!readOnly;
    document.getElementById('modal-save').style.display = readOnly ? 'none' : '';
    applyScheduleFields(schedule, readOnly, !fileName);
    document.getElementById('modal').classList.remove('hidden');
}

function applyScheduleFields(schedule, readOnly, isNew) {
    const sched = schedule || { enabled: false, cron: '', editable: true };
    editingScheduleEditable = !readOnly && sched.editable !== false;
    document.getElementById('schedule-fields').classList.remove('hidden');
    document.getElementById('definition-schedule-enabled').checked = !!sched.enabled;
    document.getElementById('definition-schedule-cron').value = sched.cron || '';
    document.getElementById('definition-schedule-enabled').disabled = !editingScheduleEditable;
    document.getElementById('definition-schedule-cron').disabled = !editingScheduleEditable;
    const nextRunEl = document.getElementById('definition-schedule-next-run');
    const nextRunField = document.getElementById('definition-next-run-field');
    if (sched.nextRunAt) {
        nextRunEl.textContent = formatTime(sched.nextRunAt);
        nextRunField.classList.remove('hidden');
    } else if (isNew) {
        nextRunEl.textContent = '保存后根据 Cron 计算';
        nextRunField.classList.remove('hidden');
    } else if (!sched.enabled) {
        nextRunField.classList.add('hidden');
    } else {
        nextRunEl.textContent = '—';
        nextRunField.classList.remove('hidden');
    }
}

function readScheduleFromModal() {
    return {
        enabled: document.getElementById('definition-schedule-enabled').checked,
        cron: document.getElementById('definition-schedule-cron').value.trim()
    };
}

function closeModal() {
    document.getElementById('modal').classList.add('hidden');
    document.getElementById('definition-content').readOnly = false;
    editingDefinition = null;
    editingScheduleEditable = false;
}

async function viewDefinition(fileName) {
    try {
        const item = await api(`/job-definitions/${encodeURIComponent(fileName)}`);
        openDefinitionModal(fileName, item.content, true, item.schedule, item.name);
    } catch (err) {
        showToast('加载失败: ' + err.message);
    }
}

async function editDefinition(fileName) {
    try {
        const item = await api(`/job-definitions/${encodeURIComponent(fileName)}`);
        if (item.readOnly) {
            showToast('内置任务不可编辑');
            return;
        }
        openDefinitionModal(fileName, item.content, false, item.schedule, item.name);
    } catch (err) {
        showToast('加载失败: ' + err.message);
    }
}

async function saveDefinition() {
    const displayName = document.getElementById('definition-name').value.trim();
    const content = document.getElementById('definition-content').value;
    if (!displayName) {
        showToast('请输入任务名称');
        return;
    }
    const payload = {
        displayName,
        content
    };
    if (editingDefinition) {
        payload.name = editingDefinition;
    }
    if (editingScheduleEditable) {
        const schedule = readScheduleFromModal();
        if (schedule.enabled && !schedule.cron) {
            showToast('启用定时调度时请填写 Cron 表达式');
            return;
        }
        payload.schedule = schedule;
    }
    try {
        if (editingDefinition) {
            await api(`/job-definitions/${encodeURIComponent(editingDefinition)}`, {
                method: 'PUT',
                body: JSON.stringify(payload)
            });
            showToast('任务已更新');
        } else {
            await api('/job-definitions', {
                method: 'POST',
                body: JSON.stringify(payload)
            });
            showToast('任务已创建');
        }
        closeModal();
        loadDefinitions();
    } catch (err) {
        showToast('保存失败: ' + err.message);
    }
}

async function deleteDefinition(fileName) {
    if (!confirm(`确定删除任务配置 "${fileName}"？`)) return;
    try {
        await api(`/job-definitions/${encodeURIComponent(fileName)}`, { method: 'DELETE' });
        showToast('已删除');
        loadDefinitions();
    } catch (err) {
        showToast('删除失败: ' + err.message);
    }
}

function openPreviewModal(displayName, path) {
    previewContext = {
        displayName,
        path,
        rows: [],
        columns: [],
        page: 1
    };
    document.getElementById('preview-title').textContent = `数据预览: ${displayName}`;
    document.getElementById('preview-content').innerHTML = '<p class="preview-loading">正在生成预览数据...</p>';
    document.getElementById('preview-pagination').classList.add('hidden');
    document.getElementById('preview-pagination').innerHTML = '';
    document.getElementById('preview-modal').classList.remove('hidden');
}

function closePreviewModal() {
    document.getElementById('preview-modal').classList.add('hidden');
    previewContext = { displayName: null, path: null, rows: [], columns: [], page: 1 };
    document.getElementById('preview-content').innerHTML = '';
    document.getElementById('preview-pagination').classList.add('hidden');
    document.getElementById('preview-pagination').innerHTML = '';
}

async function previewDefinition(displayName, path) {
    openPreviewModal(displayName, path);
    await runPreview();
}

function flattenPreviewRows(rowsByTable) {
    const flat = [];
    for (const [tableName, tableRows] of Object.entries(rowsByTable)) {
        for (const row of tableRows) {
            flat.push({ _table: tableName, ...row });
        }
    }
    return flat;
}

function collectPreviewColumns(flatRows) {
    const columns = ['_table'];
    const seen = new Set(['_table']);
    for (const row of flatRows) {
        for (const key of Object.keys(row)) {
            if (!seen.has(key)) {
                seen.add(key);
                columns.push(key);
            }
        }
    }
    return columns;
}

function previewColumnLabel(column) {
    return column === '_table' ? '表名' : column;
}

function getPreviewTotalPages() {
    return Math.max(1, Math.ceil(previewContext.rows.length / PREVIEW_PAGE_SIZE));
}

function getPagedPreviewRows() {
    const start = (previewContext.page - 1) * PREVIEW_PAGE_SIZE;
    return previewContext.rows.slice(start, start + PREVIEW_PAGE_SIZE);
}

function renderPreviewPagination() {
    const pagination = document.getElementById('preview-pagination');
    const totalPages = getPreviewTotalPages();
    const { page, rows } = previewContext;

    if (rows.length <= PREVIEW_PAGE_SIZE) {
        pagination.classList.add('hidden');
        pagination.innerHTML = '';
        return;
    }

    pagination.classList.remove('hidden');
    pagination.innerHTML = `
        <button type="button" class="btn small" ${page <= 1 ? 'disabled' : ''} onclick="changePreviewPage(${page - 1})">上一页</button>
        <span class="pagination-info">第 ${page} / ${totalPages} 页，共 ${rows.length} 条</span>
        <button type="button" class="btn small" ${page >= totalPages ? 'disabled' : ''} onclick="changePreviewPage(${page + 1})">下一页</button>
    `;
}

function renderPreviewTable() {
    const panel = document.getElementById('preview-content');
    const { rows, columns } = previewContext;

    if (!rows.length) {
        panel.innerHTML = '<p class="preview-empty">无数据</p>';
        renderPreviewPagination();
        return;
    }

    const totalPages = getPreviewTotalPages();
    if (previewContext.page > totalPages) {
        previewContext.page = totalPages;
    }

    const pagedRows = getPagedPreviewRows();
    let html = '<div class="table-wrap preview-table-wrap"><table><thead><tr>';
    for (const col of columns) {
        html += `<th>${escapeHtml(previewColumnLabel(col))}</th>`;
    }
    html += '</tr></thead><tbody>';
    for (const row of pagedRows) {
        html += '<tr>';
        for (const col of columns) {
            html += `<td>${formatPreviewCell(row[col])}</td>`;
        }
        html += '</tr>';
    }
    html += '</tbody></table></div>';
    panel.innerHTML = html;
    renderPreviewPagination();
}

function changePreviewPage(page) {
    const totalPages = getPreviewTotalPages();
    if (page < 1 || page > totalPages) {
        return;
    }
    previewContext.page = page;
    renderPreviewTable();
}

function formatPreviewCell(value) {
    if (value == null) {
        return '<span class="muted">—</span>';
    }
    if (typeof value === 'object') {
        return `<code class="preview-cell-json">${escapeHtml(JSON.stringify(value))}</code>`;
    }
    return escapeHtml(String(value));
}

async function runPreview() {
    if (!previewContext.path) {
        return;
    }

    const panel = document.getElementById('preview-content');
    panel.innerHTML = '<p class="preview-loading">正在生成预览数据...</p>';
    document.getElementById('preview-pagination').classList.add('hidden');

    try {
        const result = await api('/preview', {
            method: 'POST',
            body: JSON.stringify({
                jobConfig: previewContext.path,
                preview: { limit: PREVIEW_FETCH_LIMIT }
            })
        });
        const rowsByTable = result.rows || {};
        previewContext.rows = flattenPreviewRows(rowsByTable);
        previewContext.columns = collectPreviewColumns(previewContext.rows);
        previewContext.page = 1;
        renderPreviewTable();
    } catch (err) {
        panel.innerHTML = `<p class="preview-error">预览失败: ${escapeHtml(err.message)}</p>`;
    }
}

async function runDefinition(path, scheduleEnabled = false) {
    if (scheduleEnabled) {
        const confirmed = confirm(
            '该任务已启用定时调度。确定要立即执行一次吗？\n\n立即执行不影响 Cron 定时计划，仅额外触发一轮。'
        );
        if (!confirmed) {
            return;
        }
    }
    try {
        const result = await api('/jobs', {
            method: 'POST',
            body: JSON.stringify({ jobConfig: path })
        });
        if (result.status === 'PENDING') {
            showToast(`任务已加入队列: ${result.jobId}`);
        } else {
            showToast(`任务已提交: ${result.jobId} (${result.status})`);
        }
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
        selectedJobId: null,
        logDetailPages: {},
        logDetailLines: {}
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
    if (logModalContext.selectedJobId === jobId) {
        logModalContext.selectedJobId = null;
    } else {
        logModalContext.selectedJobId = jobId;
        if (!logModalContext.logDetailPages[jobId]) {
            logModalContext.logDetailPages[jobId] = 1;
        }
    }
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
        logModalContext.logDetailLines[jobId] = logs;
        const logPage = logModalContext.logDetailPages[jobId] || 1;
        panel.innerHTML = `
            <div class="detail-summary log-detail-summary">
                <div class="detail-item"><label>状态</label>${statusBadge(job.status)}</div>
                <div class="detail-item"><label>提交时间</label>${formatTime(job.submittedAt)}</div>
                <div class="detail-item"><label>耗时</label>${escapeHtml(job.duration || '-')}</div>
                <div class="detail-item"><label>写入行数</label>${progress.writtenRows ?? 0} / ${progress.totalRows ?? 0}</div>
                ${job.errorMessage ? `<div class="detail-item detail-item-wide"><label>错误</label>${escapeHtml(job.errorMessage)}</div>` : ''}
            </div>
            <pre class="log-view">${renderLogLines(logs, logPage)}</pre>
            ${renderLogDetailPagination(jobId, logs.length, logPage)}
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
        selectedJobId: null,
        logDetailPages: {},
        logDetailLines: {}
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

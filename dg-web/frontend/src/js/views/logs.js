/** 运行日志弹窗视图：分页、行展开详情、滚动状态保持、自动刷新同步。 */

import { api } from '../core/api.js';
import {
    applyScrollState,
    cssEscape,
    escapeAttr,
    escapeHtml,
    formatTime,
    isScrollAtBottom,
    renderLogLines,
    showToast,
    statusBadge
} from '../core/ui.js';
import { resolveConfigDisplayName } from '../core/state.js';
import { ensureAutoRefresh } from '../core/refresh.js';
import { initOverlayScrollbars } from '../lib/scrollbar.js';

const LOG_PAGE_SIZE = 10;

let logModalContext = {
    definitionName: null,
    definitionPath: null,
    items: [],
    total: 0,
    page: 1,
    selectedRunId: null,
    logDetailLines: {}
};
/** 弹窗分页请求序号：晚到的响应直接丢弃，避免覆盖新页 */
let logModalRequestSeq = 0;

/** 绑定弹窗关闭按钮与行/分页委托事件 */
export function initLogs() {
    document.getElementById('log-close').addEventListener('click', closeLogModal);
    document.querySelector('#log-modal .modal-backdrop').addEventListener('click', closeLogModal);

    document.getElementById('log-runs-body').addEventListener('click', (event) => {
        const row = event.target.closest('tr[data-run-id]');
        if (row) {
            toggleRunLogDetail(row.dataset.runId);
        }
    });

    document.getElementById('log-pagination').addEventListener('click', (event) => {
        const button = event.target.closest('button[data-page]');
        if (button) {
            changeLogPage(parseInt(button.dataset.page, 10));
        }
    });
}

export function isLogModalOpen() {
    return !document.getElementById('log-modal').classList.contains('hidden');
}

function buildLogDetailSummaryHtml(taskRun, progress) {
    return `
            <div class="detail-summary log-detail-summary">
                <div class="detail-item"><label>状态</label>${statusBadge(taskRun.status)}</div>
                <div class="detail-item"><label>提交时间</label>${formatTime(taskRun.submittedAt)}</div>
                <div class="detail-item"><label>耗时</label>${escapeHtml(taskRun.duration || '-')}</div>
                <div class="detail-item"><label>写入行数</label>${progress.writtenRows ?? 0} / ${progress.totalRows ?? 0}</div>
                ${taskRun.errorMessage ? `<div class="detail-item detail-item-wide"><label>错误</label>${escapeHtml(taskRun.errorMessage)}</div>` : ''}
            </div>`;
}

function captureLogModalScrollState() {
    const modalBody = document.querySelector('#log-modal .modal-body');
    const logView = logModalContext.selectedRunId
        ? document.querySelector(`#log-modal [data-log-panel="${cssEscape(logModalContext.selectedRunId)}"] .log-view`)
        : null;
    return {
        modal: modalBody
            ? { scrollTop: modalBody.scrollTop, atBottom: isScrollAtBottom(modalBody) }
            : null,
        logView: logView
            ? { scrollTop: logView.scrollTop, atBottom: isScrollAtBottom(logView) }
            : null
    };
}

function applyLogModalScrollState(state) {
    if (!state) {
        return;
    }
    const modalBody = document.querySelector('#log-modal .modal-body');
    applyScrollState(modalBody, state.modal);
    const logView = logModalContext.selectedRunId
        ? document.querySelector(`#log-modal [data-log-panel="${cssEscape(logModalContext.selectedRunId)}"] .log-view`)
        : null;
    applyScrollState(logView, state.logView);
}

function updateRunRowCells(runId, taskRun, progress) {
    const row = document.querySelector(`#log-runs-body tr.log-run-row[data-run-id="${cssEscape(runId)}"]`);
    if (!row) {
        return;
    }
    row.cells[1].innerHTML = statusBadge(taskRun.status);
    row.cells[3].textContent = taskRun.duration || '-';
    row.cells[4].textContent = `${progress.writtenRows ?? 0} / ${progress.totalRows ?? 0}`;
}

function syncPagedRunRowsInPlace() {
    for (const run of logModalContext.items) {
        const latest = logModalContext.items.find(item => item.runId === run.runId) || run;
        updateRunRowCells(latest.runId, latest, {
            writtenRows: latest.writtenRows,
            totalRows: latest.totalRows
        });
    }
}

function getLogTotalPages(total) {
    return Math.max(1, Math.ceil(total / LOG_PAGE_SIZE));
}

function isSelectedRunOnCurrentPage() {
    if (!logModalContext.selectedRunId) {
        return false;
    }
    return logModalContext.items.some(run => run.runId === logModalContext.selectedRunId);
}

function renderLogPagination() {
    const pagination = document.getElementById('log-pagination');
    const totalPages = getLogTotalPages(logModalContext.total);
    const { page, total } = logModalContext;

    if (total <= LOG_PAGE_SIZE) {
        pagination.classList.add('hidden');
        pagination.innerHTML = '';
        return;
    }

    pagination.classList.remove('hidden');
    pagination.innerHTML = `
        <button type="button" class="btn small" data-page="${page - 1}" ${page <= 1 ? 'disabled' : ''}>上一页</button>
        <span class="pagination-info">第 ${page} / ${totalPages} 页，共 ${total} 条</span>
        <button type="button" class="btn small" data-page="${page + 1}" ${page >= totalPages ? 'disabled' : ''}>下一页</button>
    `;
}

function renderLogRunsTable() {
    const tbody = document.getElementById('log-runs-body');
    const { items, total } = logModalContext;
    const totalPages = getLogTotalPages(total);

    if (logModalContext.page > totalPages) {
        logModalContext.page = totalPages;
    }
    if (logModalContext.page < 1) {
        logModalContext.page = 1;
    }

    if (!items.length) {
        tbody.innerHTML = '<tr class="empty-row"><td colspan="5">暂无运行记录</td></tr>';
        renderLogPagination();
        return;
    }

    const selectedOnPage = isSelectedRunOnCurrentPage();
    if (!selectedOnPage) {
        logModalContext.selectedRunId = null;
    }

    const scrollState = captureLogModalScrollState();
    const preservedDetailHtml = logModalContext.selectedRunId
        ? document.querySelector(`#log-modal [data-log-panel="${cssEscape(logModalContext.selectedRunId)}"]`)?.innerHTML
        : null;

    let html = '';
    for (const run of items) {
        const expanded = logModalContext.selectedRunId === run.runId;
        html += `
        <tr class="log-run-row${expanded ? ' selected' : ''}" data-run-id="${escapeAttr(run.runId)}">
            <td><code>${escapeHtml(run.runId)}</code></td>
            <td>${statusBadge(run.status)}</td>
            <td>${formatTime(run.submittedAt)}</td>
            <td>${escapeHtml(run.duration || '-')}</td>
            <td>${run.writtenRows ?? 0} / ${run.totalRows ?? 0}</td>
        </tr>`;
        if (expanded) {
            const detailContent = preservedDetailHtml || '加载中...';
            html += `
        <tr class="log-detail-row">
            <td colspan="5">
                <div class="log-detail-panel" data-log-panel="${escapeAttr(run.runId)}">${detailContent}</div>
            </td>
        </tr>`;
        }
    }
    tbody.innerHTML = html;
    renderLogPagination();
    applyLogModalScrollState(scrollState);

    if (logModalContext.selectedRunId) {
        if (preservedDetailHtml) {
            refreshLogDetailContent(logModalContext.selectedRunId, scrollState?.logView);
        } else {
            loadRunLogDetailContent(logModalContext.selectedRunId);
        }
    }
}

function changeLogPage(page) {
    const totalPages = getLogTotalPages(logModalContext.total);
    if (page < 1 || page > totalPages) {
        return;
    }
    logModalContext.page = page;
    logModalContext.selectedRunId = null;
    fetchLogModalPage(page).catch(() => {});
}

/** 从后端分页拉取弹窗运行记录；过期响应直接丢弃 */
async function fetchLogModalPage(page, options = {}) {
    const { silent = false } = options;
    const seq = ++logModalRequestSeq;
    let data;
    try {
        data = await api(
            `/task-runs?configPath=${encodeURIComponent(logModalContext.definitionPath)}&page=${page}&size=${LOG_PAGE_SIZE}`
        );
    } catch (err) {
        if (seq === logModalRequestSeq) {
            showToast('运行记录加载失败: ' + err.message);
        }
        throw err;
    }
    if (seq !== logModalRequestSeq) {
        return null;
    }
    logModalContext.page = page;
    logModalContext.items = data.items || [];
    logModalContext.total = data.total || 0;
    if (!silent) {
        renderLogRunsTable();
    }
    return data;
}

function toggleRunLogDetail(runId) {
    if (logModalContext.selectedRunId === runId) {
        logModalContext.selectedRunId = null;
    } else {
        logModalContext.selectedRunId = runId;
    }
    renderLogRunsTable();
}

async function loadRunLogDetailContent(runId) {
    const panel = document.querySelector(`[data-log-panel="${cssEscape(runId)}"]`);
    if (!panel) {
        return;
    }
    const isFirstLoad = !panel.querySelector('.log-view');
    if (isFirstLoad) {
        panel.textContent = '加载中...';
    }

    try {
        const taskRun = await api(`/task-runs/${encodeURIComponent(runId)}`);
        const run = logModalContext.items.find(item => item.runId === runId) || taskRun;
        const progress = taskRun.progress || {};

        const logs = await api(`/task-runs/${encodeURIComponent(runId)}/logs`);
        logModalContext.logDetailLines[runId] = logs;
        panel.innerHTML = `
            ${buildLogDetailSummaryHtml(taskRun, progress)}
            <pre class="log-view scrollbar-overlay">${renderLogLines(logs)}</pre>
        `;
        initOverlayScrollbars(panel);

        if (run && run.status !== taskRun.status) {
            run.status = taskRun.status;
        }
        updateRunRowCells(runId, taskRun, progress);
    } catch (err) {
        panel.textContent = '加载失败: ' + err.message;
    }
}

async function refreshLogDetailContent(runId, previousLogScrollState) {
    const panel = document.querySelector(`[data-log-panel="${cssEscape(runId)}"]`);
    if (!panel) {
        await loadRunLogDetailContent(runId);
        return;
    }

    const logView = panel.querySelector('.log-view');
    const scrollState = previousLogScrollState || (logView
        ? { scrollTop: logView.scrollTop, atBottom: isScrollAtBottom(logView) }
        : null);

    try {
        const taskRun = await api(`/task-runs/${encodeURIComponent(runId)}`);
        const progress = taskRun.progress || {};
        const logs = await api(`/task-runs/${encodeURIComponent(runId)}/logs`);
        logModalContext.logDetailLines[runId] = logs;

        const summary = panel.querySelector('.log-detail-summary');
        if (summary) {
            summary.outerHTML = buildLogDetailSummaryHtml(taskRun, progress);
        } else {
            panel.innerHTML = `
                ${buildLogDetailSummaryHtml(taskRun, progress)}
                <pre class="log-view scrollbar-overlay">${renderLogLines(logs)}</pre>
            `;
            initOverlayScrollbars(panel);
        }

        const targetLogView = panel.querySelector('.log-view');
        if (targetLogView) {
            targetLogView.innerHTML = renderLogLines(logs);
            applyScrollState(targetLogView, scrollState);
        }

        const run = logModalContext.items.find(item => item.runId === runId);
        if (run) {
            run.status = taskRun.status;
            run.duration = taskRun.duration;
            run.writtenRows = progress.writtenRows;
            run.totalRows = progress.totalRows;
        }
        updateRunRowCells(runId, taskRun, progress);
    } catch (_) {
        // 自动刷新失败时不打断当前阅读
    }
}

/** 用最新运行记录同步打开的日志弹窗（自动刷新回调使用） */
export async function refreshOpenLogModal() {
    if (!isLogModalOpen() || !logModalContext.definitionPath) {
        return;
    }

    const seq = ++logModalRequestSeq;
    try {
        const data = await api(
            `/task-runs?configPath=${encodeURIComponent(logModalContext.definitionPath)}&page=${logModalContext.page}&size=${LOG_PAGE_SIZE}`
        );
        if (seq !== logModalRequestSeq) {
            return;
        }

        const newItems = data.items || [];
        const selectedRunId = logModalContext.selectedRunId;

        if (!newItems.length && !logModalContext.items.length) {
            logModalContext.total = data.total || 0;
            renderLogRunsTable();
            return;
        }

        const previousRunIds = new Set(logModalContext.items.map(run => run.runId));
        const runsChanged = newItems.length !== previousRunIds.size
            || newItems.some(run => !previousRunIds.has(run.runId));

        const selectedStillOnPage = selectedRunId
            && newItems.some(run => run.runId === selectedRunId);

        logModalContext.items = newItems;
        logModalContext.total = data.total || 0;

        if (runsChanged || !selectedRunId || !selectedStillOnPage) {
            renderLogRunsTable();
            return;
        }

        syncPagedRunRowsInPlace();
        await refreshLogDetailContent(selectedRunId);
    } catch (_) {
        // 自动刷新失败时不打断当前阅读
    }
}

function closeLogModal() {
    logModalRequestSeq++; // 使在途请求过期
    document.getElementById('log-modal').classList.add('hidden');
    logModalContext = {
        definitionName: null,
        definitionPath: null,
        items: [],
        total: 0,
        page: 1,
        selectedRunId: null,
        logDetailLines: {}
    };
    document.getElementById('log-runs-body').innerHTML = '';
    document.getElementById('log-pagination').classList.add('hidden');
    document.getElementById('log-pagination').innerHTML = '';
    ensureAutoRefresh();
}

/** 打开日志弹窗（概览行点击与任务动作菜单共用入口）：按后端分页加载，定位所选运行所在页 */
export async function openLogListModal(name, path, selectedRunId = null) {
    logModalContext = {
        definitionName: name,
        definitionPath: path,
        items: [],
        total: 0,
        page: 1,
        selectedRunId: selectedRunId || null,
        logDetailLines: {}
    };

    document.getElementById('log-title').textContent = `运行记录: ${name}`;
    document.getElementById('log-runs-body').innerHTML = '<tr class="empty-row"><td colspan="5">加载中...</td></tr>';
    document.getElementById('log-modal').classList.remove('hidden');
    ensureAutoRefresh();

    await locateLogModalPage();
}

/** 定位所选运行所在页：自第一页顺序向后查找（数据量通常较小，且所选运行多为最近记录） */
async function locateLogModalPage() {
    const { selectedRunId } = logModalContext;
    let page = 1;
    let data = null;
    while (true) {
        data = await fetchLogModalPage(page, { silent: true });
        if (!data) {
            return; // 已被更新的请求取代
        }
        const totalPages = getLogTotalPages(data.total);
        if (!selectedRunId || data.items.some(run => run.runId === selectedRunId) || page >= totalPages) {
            break;
        }
        page++;
    }
    // 所选运行已不存在（如被删除）：清空选择并渲染当前页
    if (selectedRunId && !logModalContext.items.some(run => run.runId === selectedRunId)) {
        logModalContext.selectedRunId = null;
    }
    renderLogRunsTable();
}

// ── 运行日志视图（独立页面，后端分页查询）──

const LOGS_VIEW_PAGE_SIZE = 20;

const logsViewContext = {
    page: 1,
    total: 0,
    items: [],
    filter: { status: '', configPath: '', from: '', to: '' },
    selectedRunId: null
};

/** 日志视图分页请求序号：晚到的响应直接丢弃，避免覆盖新页 */
let logsViewRequestSeq = 0;

/** 绑定运行日志视图的查询条件、分页与行展开委托事件 */
export function initLogsView() {
    document.getElementById('btn-refresh-logs').addEventListener('click', () => {
        fetchLogsViewPage().catch(err => showToast('运行日志加载失败: ' + err.message));
    });
    document.getElementById('btn-logs-search').addEventListener('click', applyLogsViewFilter);
    document.getElementById('btn-logs-reset').addEventListener('click', resetLogsViewFilter);
    document.getElementById('logs-view-body').addEventListener('click', (event) => {
        const row = event.target.closest('tr[data-run-id]');
        if (row) {
            toggleLogsViewDetail(row.dataset.runId);
        }
    });
    document.getElementById('logs-view-pagination').addEventListener('click', (event) => {
        const button = event.target.closest('button[data-page]');
        if (button) {
            changeLogsViewPage(parseInt(button.dataset.page, 10));
        }
    });
}

/** 加载运行日志视图：填充任务下拉并执行首屏查询 */
export async function loadLogsView() {
    await ensureLogsViewDefinitions();
    await fetchLogsViewPage();
}

/** 读取查询条件并回到第一页重新查询 */
function applyLogsViewFilter() {
    logsViewContext.filter = {
        status: document.getElementById('logs-filter-status').value,
        configPath: document.getElementById('logs-filter-config').value,
        from: document.getElementById('logs-filter-from').value,
        to: document.getElementById('logs-filter-to').value
    };
    logsViewContext.page = 1;
    logsViewContext.selectedRunId = null;
    fetchLogsViewPage().catch(err => showToast('运行日志加载失败: ' + err.message));
}

function resetLogsViewFilter() {
    document.getElementById('logs-filter-status').value = '';
    document.getElementById('logs-filter-config').value = '';
    document.getElementById('logs-filter-from').value = '';
    document.getElementById('logs-filter-to').value = '';
    applyLogsViewFilter();
}

/** 按当前条件从后端分页查询运行记录；过期响应直接丢弃 */
async function fetchLogsViewPage() {
    const seq = ++logsViewRequestSeq;
    const params = new URLSearchParams({
        page: String(logsViewContext.page),
        size: String(LOGS_VIEW_PAGE_SIZE)
    });
    const { status, configPath, from, to } = logsViewContext.filter;
    if (status) {
        params.set('status', status);
    }
    if (configPath) {
        params.set('configPath', configPath);
    }
    if (from) {
        params.set('from', new Date(`${from}T00:00:00`).toISOString());
    }
    if (to) {
        params.set('to', new Date(`${to}T23:59:59.999`).toISOString());
    }
    const tbody = document.getElementById('logs-view-body');
    tbody.innerHTML = '<tr class="empty-row"><td colspan="6">加载中...</td></tr>';
    let data;
    try {
        data = await api(`/task-runs?${params.toString()}`);
    } catch (err) {
        if (seq === logsViewRequestSeq) {
            tbody.innerHTML = `<tr class="empty-row"><td colspan="6">加载失败: ${escapeHtml(err.message)}</td></tr>`;
        }
        throw err;
    }
    if (seq !== logsViewRequestSeq) {
        return; // 过期响应丢弃，不覆盖新页数据
    }
    logsViewContext.items = data.items || [];
    logsViewContext.total = data.total || 0;
    renderLogsViewTable();
}

/** 进入视图时拉取任务配置列表重填下拉（配置增删后进入视图即可见） */
async function ensureLogsViewDefinitions() {
    try {
        const data = await api('/task-configs');
        const select = document.getElementById('logs-filter-config');
        const previous = select.value;
        select.innerHTML = '<option value="">全部任务</option>';
        for (const item of (data.items || [])) {
            const option = document.createElement('option');
            option.value = item.path;
            option.textContent = item.name || item.fileName;
            select.appendChild(option);
        }
        // 保留仍存在的选中项，否则回退为全部任务
        const previousExists = [...select.options].some(option => option.value === previous);
        select.value = previousExists ? previous : '';
        if (select.value !== logsViewContext.filter.configPath) {
            logsViewContext.filter.configPath = select.value;
        }
    } catch (_) {
        // 定义列表拉取失败时下拉保持为空，不影响按全部任务查询
    }
}

function getLogsViewTotalPages() {
    return Math.max(1, Math.ceil(logsViewContext.total / LOGS_VIEW_PAGE_SIZE));
}

function renderLogsViewPagination() {
    const pagination = document.getElementById('logs-view-pagination');
    const totalPages = getLogsViewTotalPages();
    const { page, total } = logsViewContext;

    // 分页控件始终可见：单页时按钮禁用，便于用户了解总数与分页状态
    pagination.classList.remove('hidden');
    pagination.innerHTML = `
        <button type="button" class="btn small" data-page="${page - 1}" ${page <= 1 ? 'disabled' : ''}>上一页</button>
        <span class="pagination-info">第 ${page} / ${totalPages} 页，共 ${total} 条</span>
        <button type="button" class="btn small" data-page="${page + 1}" ${page >= totalPages ? 'disabled' : ''}>下一页</button>
    `;
}

function renderLogsViewTable() {
    const tbody = document.getElementById('logs-view-body');
    const { items, total } = logsViewContext;

    document.getElementById('logs-view-meta').textContent = `共 ${total} 条`;

    if (!items.length) {
        tbody.innerHTML = '<tr class="empty-row"><td colspan="6">暂无匹配的运行记录</td></tr>';
        renderLogsViewPagination();
        return;
    }

    const selectedOnPage = logsViewContext.selectedRunId
            && items.some(run => run.runId === logsViewContext.selectedRunId);
    if (!selectedOnPage) {
        logsViewContext.selectedRunId = null;
    }

    tbody.innerHTML = items.map(run => {
        const expanded = logsViewContext.selectedRunId === run.runId;
        let html = `
        <tr class="log-run-row${expanded ? ' selected' : ''}" data-run-id="${escapeAttr(run.runId)}">
            <td><code>${escapeHtml(run.runId)}</code></td>
            <td title="${escapeAttr(resolveConfigDisplayName(run.configPath))}">${escapeHtml(resolveConfigDisplayName(run.configPath))}</td>
            <td>${statusBadge(run.status)}</td>
            <td>${escapeHtml(run.duration || '-')}</td>
            <td>${run.writtenRows ?? 0} / ${run.totalRows ?? 0}</td>
            <td>${formatTime(run.submittedAt)}</td>
        </tr>`;
        if (expanded) {
            html += `
        <tr class="log-detail-row">
            <td colspan="6">
                <div class="log-detail-panel" data-logs-view-panel="${escapeAttr(run.runId)}">加载中...</div>
            </td>
        </tr>`;
        }
        return html;
    }).join('');

    renderLogsViewPagination();

    if (logsViewContext.selectedRunId) {
        loadLogsViewDetail(logsViewContext.selectedRunId);
    }
}

function changeLogsViewPage(page) {
    const totalPages = getLogsViewTotalPages();
    if (page < 1 || page > totalPages) {
        return;
    }
    logsViewContext.page = page;
    logsViewContext.selectedRunId = null;
    fetchLogsViewPage().catch(err => showToast('运行日志加载失败: ' + err.message));
}

function toggleLogsViewDetail(runId) {
    if (logsViewContext.selectedRunId === runId) {
        logsViewContext.selectedRunId = null;
    } else {
        logsViewContext.selectedRunId = runId;
    }
    renderLogsViewTable();
}

async function loadLogsViewDetail(runId) {
    const panel = document.querySelector(`[data-logs-view-panel="${cssEscape(runId)}"]`);
    if (!panel) {
        return;
    }
    try {
        const taskRun = await api(`/task-runs/${encodeURIComponent(runId)}`);
        const logs = await api(`/task-runs/${encodeURIComponent(runId)}/logs`);
        panel.innerHTML = `
            ${buildLogDetailSummaryHtml(taskRun, taskRun.progress || {})}
            <pre class="log-view scrollbar-overlay">${renderLogLines(logs)}</pre>
        `;
        initOverlayScrollbars(panel);
    } catch (err) {
        panel.textContent = '加载失败: ' + err.message;
    }
}

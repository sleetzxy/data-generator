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
    statusBadge
} from '../core/ui.js';
import { getAllRunsCache } from '../core/state.js';
import { ensureAutoRefresh } from '../core/refresh.js';
import { initOverlayScrollbars } from '../lib/scrollbar.js';

const LOG_PAGE_SIZE = 10;

let logModalContext = {
    definitionName: null,
    definitionPath: null,
    runs: [],
    page: 1,
    selectedRunId: null,
    logDetailLines: {}
};

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
    const pagedRuns = getPagedRuns(logModalContext.runs, logModalContext.page);
    for (const run of pagedRuns) {
        const latest = logModalContext.runs.find(item => item.runId === run.runId) || run;
        updateRunRowCells(latest.runId, latest, {
            writtenRows: latest.writtenRows,
            totalRows: latest.totalRows
        });
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
    if (!logModalContext.selectedRunId) {
        return false;
    }
    return getPagedRuns(logModalContext.runs, logModalContext.page)
        .some(run => run.runId === logModalContext.selectedRunId);
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
        <button type="button" class="btn small" data-page="${page - 1}" ${page <= 1 ? 'disabled' : ''}>上一页</button>
        <span class="pagination-info">第 ${page} / ${totalPages} 页，共 ${runs.length} 条</span>
        <button type="button" class="btn small" data-page="${page + 1}" ${page >= totalPages ? 'disabled' : ''}>下一页</button>
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
        logModalContext.selectedRunId = null;
    }

    const scrollState = captureLogModalScrollState();
    const preservedDetailHtml = logModalContext.selectedRunId
        ? document.querySelector(`#log-modal [data-log-panel="${cssEscape(logModalContext.selectedRunId)}"]`)?.innerHTML
        : null;

    let html = '';
    for (const run of pagedRuns) {
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
    const totalPages = getLogTotalPages(logModalContext.runs.length);
    if (page < 1 || page > totalPages) {
        return;
    }
    logModalContext.page = page;
    logModalContext.selectedRunId = null;
    renderLogRunsTable();
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
        const run = logModalContext.runs.find(item => item.runId === runId) || taskRun;
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

        const run = logModalContext.runs.find(item => item.runId === runId);
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

    const runs = getAllRunsCache()
        .filter(run => run.configPath === logModalContext.definitionPath)
        .sort((a, b) => new Date(b.submittedAt) - new Date(a.submittedAt));

    if (!runs.length) {
        closeLogModal();
        return;
    }

    const previousRunIds = new Set(logModalContext.runs.map(run => run.runId));
    const selectedRunId = logModalContext.selectedRunId;
    logModalContext.runs = runs;

    const runsChanged = runs.length !== previousRunIds.size
        || runs.some(run => !previousRunIds.has(run.runId));

    const selectedStillOnPage = selectedRunId
        && getPagedRuns(runs, logModalContext.page).some(run => run.runId === selectedRunId);

    if (runsChanged || !selectedRunId || !selectedStillOnPage) {
        renderLogRunsTable();
        return;
    }

    syncPagedRunRowsInPlace();
    await refreshLogDetailContent(selectedRunId);
}

function closeLogModal() {
    document.getElementById('log-modal').classList.add('hidden');
    logModalContext = {
        definitionName: null,
        definitionPath: null,
        runs: [],
        page: 1,
        selectedRunId: null,
        logDetailLines: {}
    };
    document.getElementById('log-runs-body').innerHTML = '';
    document.getElementById('log-pagination').classList.add('hidden');
    document.getElementById('log-pagination').innerHTML = '';
    ensureAutoRefresh();
}

/** 打开日志弹窗（概览行点击与任务动作菜单共用入口） */
export function openLogListModal(name, path, runs, options = {}) {
    const selectedRunId = options.selectedRunId || null;
    let page = 1;
    if (selectedRunId) {
        const index = runs.findIndex(run => run.runId === selectedRunId);
        if (index >= 0) {
            page = Math.floor(index / LOG_PAGE_SIZE) + 1;
        }
    }

    logModalContext = {
        definitionName: name,
        definitionPath: path,
        runs,
        page,
        selectedRunId,
        logDetailLines: {}
    };

    document.getElementById('log-title').textContent = `运行记录: ${name}`;
    renderLogRunsTable();
    document.getElementById('log-modal').classList.remove('hidden');
    ensureAutoRefresh();
}

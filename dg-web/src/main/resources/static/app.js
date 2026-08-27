import {
    createYamlEditor,
    destroyYamlEditor,
    getYamlEditorValue
} from './yaml-editor.js';

const API = '/api/v1';
const DOCS_GUIDE_PATH = '/docs/config-guide.md';
const LOG_PAGE_SIZE = 10;
const DEFINITION_PAGE_SIZE = 20;
const PREVIEW_FETCH_LIMIT = 10;
const AUTO_REFRESH_INTERVAL_MS = 5000;
const AUTO_REFRESH_ACTIVE_INTERVAL_MS = 2000;

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
let definitionSearchQuery = '';
let definitionSearchTimer = null;
let guideLoaded = false;
let previewContext = {
    displayName: null,
    path: null,
    tables: [],
    activeTab: 0
};
let logModalContext = {
    definitionName: null,
    definitionPath: null,
    runs: [],
    page: 1,
    selectedRunId: null,
    logDetailLines: {}
};
let autoRefreshTimer = null;
let openActionMenuId = null;
let lastRenderedPageKey = null;
let latestRunByPath = new Map();
let activeRunByPath = new Map();
let definitionsUiFrame = null;

const VIEW_TITLES = {
    overview: '运行概览',
    tasks: '任务管理',
    ai: 'AI 助手',
    docs: '配置指南'
};

let currentView = 'tasks';
let overviewLoaded = false;
let docsLoaded = false;

function initNavigation() {
    window.addEventListener('hashchange', () => {
        const view = window.location.hash.replace('#', '');
        if (view && VIEW_TITLES[view] && view !== currentView) {
            switchView(view);
        }
    });

    const hash = window.location.hash.replace('#', '');
    if (hash && VIEW_TITLES[hash]) {
        switchView(hash);
    } else {
        switchView('overview');
        if (!window.location.hash) {
            window.location.hash = 'overview';
        }
    }
}

function navigateToView(view) {
    if (!VIEW_TITLES[view]) {
        return;
    }
    if (window.location.hash.replace('#', '') !== view) {
        window.location.hash = view;
        return;
    }
    switchView(view);
}

function switchView(view) {
    currentView = view;
    document.querySelectorAll('.sidebar-link[data-view]').forEach(link => {
        link.classList.toggle('active', link.dataset.view === view);
    });
    document.querySelectorAll('.app-view').forEach(section => {
        section.classList.toggle('hidden', section.id !== `view-${view}`);
    });
    document.getElementById('view-title').textContent = VIEW_TITLES[view];

    if (view === 'overview') {
        loadOverview().catch(err => showToast('概览加载失败: ' + err.message));
    } else if (view === 'docs') {
        loadDocsView().catch(err => showToast('配置指南加载失败: ' + err.message));
    } else if (view === 'ai' && typeof window.dgOnAiViewShown === 'function') {
        window.dgOnAiViewShown();
    }
    ensureAutoRefresh();
}

async function loadDocsView() {
    if (docsLoaded) {
        return;
    }
    if (typeof window.loadDocsGuide !== 'function') {
        throw new Error('文档模块未加载');
    }
    await window.loadDocsGuide();
    docsLoaded = true;
    const docsView = document.getElementById('view-docs');
    window.initOverlayScrollbars?.(docsView?.querySelector('.docs-sidebar'));
    window.initOverlayScrollbars?.(docsView?.querySelector('.docs-content'));
}

function computeRunStats(runs) {
    const stats = {
        total: runs.length,
        running: 0,
        pending: 0,
        completed: 0,
        failed: 0,
        cancelled: 0
    };
    for (const run of runs) {
        if (run.status === 'RUNNING') {
            stats.running++;
        } else if (run.status === 'PENDING') {
            stats.pending++;
        } else if (run.status === 'COMPLETED') {
            stats.completed++;
        } else if (run.status === 'FAILED') {
            stats.failed++;
        } else if (run.status === 'CANCELLED') {
            stats.cancelled++;
        }
    }
    return stats;
}

function resolveConfigDisplayName(configPath) {
    const def = definitionsCache.find(item => item.path === configPath);
    return def?.name || configPath?.split('/').pop()?.replace(/\.yaml$/, '') || configPath || '-';
}

function renderOverviewStats(stats, configCount) {
    const container = document.getElementById('overview-stats');
    container.innerHTML = `
        <div class="stat-card stat-accent-primary">
            <div class="stat-card-label">任务配置</div>
            <div class="stat-card-value stat-default">${configCount}</div>
        </div>
        <div class="stat-card stat-accent-neutral">
            <div class="stat-card-label">总运行次数</div>
            <div class="stat-card-value stat-default">${stats.total}</div>
        </div>
        <div class="stat-card stat-accent-running">
            <div class="stat-card-label">运行中</div>
            <div class="stat-card-value stat-running">${stats.running}</div>
        </div>
        <div class="stat-card stat-accent-pending">
            <div class="stat-card-label">等待中</div>
            <div class="stat-card-value stat-pending">${stats.pending}</div>
        </div>
        <div class="stat-card stat-accent-completed">
            <div class="stat-card-label">已完成</div>
            <div class="stat-card-value stat-completed">${stats.completed}</div>
        </div>
        <div class="stat-card stat-accent-failed">
            <div class="stat-card-label">失败</div>
            <div class="stat-card-value stat-failed">${stats.failed}</div>
        </div>
        <div class="stat-card stat-accent-cancelled">
            <div class="stat-card-label">已取消</div>
            <div class="stat-card-value stat-cancelled">${stats.cancelled}</div>
        </div>
    `;
}

function renderOverviewActiveRuns(runs) {
    const tbody = document.getElementById('overview-active-body');
    const activeRuns = runs
        .filter(run => isActiveRun(run.status))
        .sort((a, b) => new Date(b.submittedAt) - new Date(a.submittedAt));

    document.getElementById('overview-active-count').textContent = `${activeRuns.length} 个`;

    if (!activeRuns.length) {
        tbody.innerHTML = '<tr class="empty-row"><td colspan="5">当前无进行中的任务</td></tr>';
        return;
    }

    tbody.innerHTML = activeRuns.map(run => `
        <tr class="overview-run-row" onclick="viewOverviewRun('${escapeAttr(run.runId)}', '${escapeAttr(run.configPath)}')">
            <td><code>${escapeHtml(run.runId)}</code></td>
            <td class="overview-config-name" title="${escapeAttr(resolveConfigDisplayName(run.configPath))}">
                <code>${escapeHtml(resolveConfigDisplayName(run.configPath))}</code>
            </td>
            <td>${statusBadge(run.status)}</td>
            <td>${run.writtenRows ?? 0} / ${run.totalRows ?? 0}</td>
            <td>${formatTime(run.submittedAt)}</td>
        </tr>
    `).join('');
}

function renderOverviewRecentRuns(runs) {
    const tbody = document.getElementById('overview-recent-body');
    const recentRuns = [...runs]
        .sort((a, b) => new Date(b.submittedAt) - new Date(a.submittedAt))
        .slice(0, 15);

    if (!recentRuns.length) {
        tbody.innerHTML = '<tr class="empty-row"><td colspan="5">暂无运行记录</td></tr>';
        return;
    }

    tbody.innerHTML = recentRuns.map(run => `
        <tr class="overview-run-row" onclick="viewOverviewRun('${escapeAttr(run.runId)}', '${escapeAttr(run.configPath)}')">
            <td><code>${escapeHtml(run.runId)}</code></td>
            <td class="overview-config-name" title="${escapeAttr(resolveConfigDisplayName(run.configPath))}">
                <code>${escapeHtml(resolveConfigDisplayName(run.configPath))}</code>
            </td>
            <td>${statusBadge(run.status)}</td>
            <td>${escapeHtml(run.duration || '-')}</td>
            <td>${formatTime(run.submittedAt)}</td>
        </tr>
    `).join('');
}

async function loadOverview() {
    const [configs, runs] = await Promise.all([
        definitionsCache.length ? Promise.resolve(definitionsCache) : api('/task-configs'),
        fetchAllJobs()
    ]);
    if (!definitionsCache.length) {
        definitionsCache = configs;
    }
    allRunsCache = runs;
    rebuildRunIndexes();

    const stats = computeRunStats(runs);
    renderOverviewStats(stats, configs.length);
    renderOverviewActiveRuns(runs);
    renderOverviewRecentRuns(runs);
    overviewLoaded = true;
}

async function viewOverviewRun(runId, configPath) {
    const name = resolveConfigDisplayName(configPath);
    try {
        if (!allRunsCache.length) {
            allRunsCache = await fetchAllJobs();
        }
        const runs = allRunsCache
            .filter(run => run.configPath === configPath)
            .sort((a, b) => new Date(b.submittedAt) - new Date(a.submittedAt));
        if (!runs.length) {
            showToast('暂无运行记录');
            return;
        }
        logModalContext.selectedRunId = runId;
        openLogListModal(name, configPath, runs, { selectedRunId: runId });
    } catch (err) {
        showToast('加载失败: ' + err.message);
    }
}

function syncOverviewInPlace() {
    if (currentView !== 'overview' || !overviewLoaded) {
        return;
    }
    const stats = computeRunStats(allRunsCache);
    renderOverviewStats(stats, definitionsCache.length);
    renderOverviewActiveRuns(allRunsCache);
    renderOverviewRecentRuns(allRunsCache);
}

document.getElementById('btn-new-definition').addEventListener('click', () => {
    openDefinitionModal(null).catch(err => showToast('打开编辑器失败: ' + err.message));
});
document.getElementById('btn-refresh-definitions').addEventListener('click', () => loadDefinitions({ fullRender: true }));
document.getElementById('definition-search').addEventListener('input', (event) => {
    definitionSearchQuery = event.target.value.trim();
    definitionsPage = 1;
    clearTimeout(definitionSearchTimer);
    definitionSearchTimer = setTimeout(() => {
        loadDefinitions({ fullRender: true });
    }, 300);
});
document.getElementById('guide-toggle').addEventListener('click', toggleGuidePanel);

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

function renderLogLines(logs) {
    if (!logs.length) {
        return '暂无日志';
    }
    return logs.map(entry =>
        `<span class="log-line-${entry.level}">[${entry.timestamp}] ${entry.level} ${escapeHtml(entry.message)}</span>`
    ).join('\n');
}

function isScrollAtBottom(element, threshold = 48) {
    if (!element) {
        return false;
    }
    return element.scrollHeight - element.scrollTop - element.clientHeight <= threshold;
}

function applyScrollState(element, state) {
    if (!element || !state) {
        return;
    }
    if (state.atBottom) {
        element.scrollTop = element.scrollHeight;
    } else {
        element.scrollTop = state.scrollTop;
    }
}

function buildLogDetailSummaryHtml(job, progress) {
    return `
            <div class="detail-summary log-detail-summary">
                <div class="detail-item"><label>状态</label>${statusBadge(job.status)}</div>
                <div class="detail-item"><label>提交时间</label>${formatTime(job.submittedAt)}</div>
                <div class="detail-item"><label>耗时</label>${escapeHtml(job.duration || '-')}</div>
                <div class="detail-item"><label>写入行数</label>${progress.writtenRows ?? 0} / ${progress.totalRows ?? 0}</div>
                ${job.errorMessage ? `<div class="detail-item detail-item-wide"><label>错误</label>${escapeHtml(job.errorMessage)}</div>` : ''}
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

function updateRunRowCells(runId, job, progress) {
    const row = document.querySelector(`#log-runs-body tr.log-run-row[data-run-id="${cssEscape(runId)}"]`);
    if (!row) {
        return;
    }
    row.cells[1].innerHTML = statusBadge(job.status);
    row.cells[3].textContent = job.duration || '-';
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

function hasActiveRuns() {
    return allRunsCache.some(run => isActiveRun(run.status));
}

function isLogModalOpen() {
    return !document.getElementById('log-modal').classList.contains('hidden');
}

function shouldAutoRefresh() {
    if (currentView === 'overview') {
        return true;
    }
    if (isLogModalOpen()) {
        return true;
    }
    if (currentView === 'tasks' && hasActiveRuns()) {
        return true;
    }
    return false;
}

function resolveAutoRefreshIntervalMs() {
    if (hasActiveRuns() || isLogModalOpen()) {
        return AUTO_REFRESH_ACTIVE_INTERVAL_MS;
    }
    return AUTO_REFRESH_INTERVAL_MS;
}

async function refreshRuntimeSnapshot() {
    allRunsCache = await fetchAllJobs();
    rebuildRunIndexes();

    if (currentView === 'overview') {
        syncOverviewInPlace();
    }

    if (currentView === 'tasks' && canSyncDefinitionsInPlace()) {
        syncDefinitionsTableInPlace();
    }

    if (isLogModalOpen()) {
        await refreshOpenLogModal();
    }
}

function ensureAutoRefresh() {
    if (document.hidden || !shouldAutoRefresh()) {
        stopAutoRefresh();
        return;
    }
    if (autoRefreshTimer !== null) {
        return;
    }
    const tick = async () => {
        autoRefreshTimer = null;
        try {
            if (!document.hidden && shouldAutoRefresh()) {
                await refreshRuntimeSnapshot();
            }
        } catch (_) {
            // 自动刷新失败时不打断后续轮询
        }
        ensureAutoRefresh();
    };
    autoRefreshTimer = setTimeout(tick, resolveAutoRefreshIntervalMs());
}

function stopAutoRefresh() {
    if (autoRefreshTimer) {
        clearTimeout(autoRefreshTimer);
        autoRefreshTimer = null;
    }
}

function rebuildRunIndexes() {
    latestRunByPath = new Map();
    activeRunByPath = new Map();
    for (const run of allRunsCache) {
        const path = run.configPath;
        const latest = latestRunByPath.get(path);
        if (!latest || new Date(run.submittedAt) > new Date(latest.submittedAt)) {
            latestRunByPath.set(path, run);
        }
        if (isActiveRun(run.status)) {
            const active = activeRunByPath.get(path);
            if (!active || new Date(run.submittedAt) > new Date(active.submittedAt)) {
                activeRunByPath.set(path, run);
            }
        }
    }
}

function findLatestRun(path) {
    return latestRunByPath.get(path) || null;
}

function findActiveRun(path) {
    return activeRunByPath.get(path) || null;
}

async function fetchAllJobs() {
    const all = [];
    let page = 1;
    const size = 100;
    while (true) {
        const data = await api(`/task-runs?page=${page}&size=${size}`);
        all.push(...(data.items || []));
        if (all.length >= data.total || !data.items?.length) {
            break;
        }
        page++;
    }
    return all;
}

function buildTaskConfigListUrl() {
    const query = definitionSearchQuery.trim();
    if (!query) {
        return '/task-configs';
    }
    return `/task-configs?name=${encodeURIComponent(query)}`;
}

async function loadDefinitions(options = {}) {
    const fullRender = options.fullRender === true;
    const tbody = document.getElementById('definitions-body');
    try {
        const [items, runs] = await Promise.all([
            api(buildTaskConfigListUrl()),
            fetchAllJobs()
        ]);
        allRunsCache = runs;
        definitionsCache = items;
        rebuildRunIndexes();

        if (!fullRender && canSyncDefinitionsInPlace()) {
            scheduleDefinitionsUiSync();
            ensureAutoRefresh();
            return;
        }
        renderDefinitionsTable();
        ensureAutoRefresh();
    } catch (err) {
        tbody.innerHTML = `<tr class="empty-row"><td colspan="6">加载失败: ${escapeHtml(err.message)}</td></tr>`;
        renderDefinitionsPagination();
        lastRenderedPageKey = null;
    }
}

function currentPageDefinitionKey() {
    return getPagedDefinitions().map(item => item.path).join('|');
}

function canSyncDefinitionsInPlace() {
    if (!definitionsCache.length) {
        return false;
    }
    const tbody = document.getElementById('definitions-body');
    if (tbody.querySelector('.empty-row')) {
        return false;
    }
    const rows = tbody.querySelectorAll('tr[data-definition-path]');
    if (rows.length !== getPagedDefinitions().length) {
        return false;
    }
    return currentPageDefinitionKey() === lastRenderedPageKey;
}

function scheduleDefinitionsUiSync() {
    if (definitionsUiFrame !== null) {
        return;
    }
    definitionsUiFrame = requestAnimationFrame(async () => {
        definitionsUiFrame = null;
        syncDefinitionsTableInPlace();
        await refreshOpenLogModal();
    });
}

function syncDefinitionsTableInPlace() {
    const rows = document.querySelectorAll('#definitions-body tr[data-definition-path]');
    for (const row of rows) {
        const path = row.dataset.definitionPath;
        const latestRun = findLatestRun(path);
        const activeRun = findActiveRun(path);
        const statusCell = row.cells[4];
        if (statusCell) {
            const nextStatusHtml = statusBadge(latestRun?.status);
            if (statusCell.innerHTML !== nextStatusHtml) {
                statusCell.innerHTML = nextStatusHtml;
            }
        }
        const actionsCell = row.cells[5];
        if (actionsCell) {
            updateStopButtonInPlace(actionsCell, activeRun);
        }
    }
}

function updateStopButtonInPlace(actionsCell, activeRun) {
    const stopBtn = actionsCell.querySelector('.action-stop-btn');
    if (!stopBtn) {
        return;
    }
    const stopDisabled = !activeRun;
    const stopRunId = activeRun?.runId || '';
    stopBtn.disabled = stopDisabled;
    stopBtn.classList.toggle('disabled', stopDisabled);
    if (stopDisabled) {
        stopBtn.removeAttribute('onclick');
        stopBtn.title = '当前无运行中的任务';
    } else {
        stopBtn.setAttribute('onclick', `stopRun('${escapeAttr(stopRunId)}'); closeActionMenus()`);
        stopBtn.removeAttribute('title');
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

function captureOpenActionMenuId() {
    const openMenu = document.querySelector('.action-menu-dropdown:not(.hidden)');
    return openMenu ? openMenu.id : openActionMenuId;
}

function restoreOpenActionMenu(menuId) {
    if (!menuId) {
        return;
    }
    const menu = document.getElementById(menuId);
    if (menu) {
        menu.classList.remove('hidden');
        openActionMenuId = menuId;
    } else {
        openActionMenuId = null;
    }
}

function renderDefinitionsTable() {
    const preservedMenuId = captureOpenActionMenuId();
    const tbody = document.getElementById('definitions-body');
    const totalPages = getDefinitionTotalPages();

    if (definitionsPage > totalPages) {
        definitionsPage = totalPages;
    }
    if (definitionsPage < 1) {
        definitionsPage = 1;
    }

    if (!definitionsCache.length) {
        const message = definitionSearchQuery
            ? `未找到匹配「${escapeHtml(definitionSearchQuery)}」的任务`
            : '暂无任务配置';
        tbody.innerHTML = `<tr class="empty-row"><td colspan="6">${message}</td></tr>`;
        renderDefinitionsPagination();
        refreshOpenLogModal();
        lastRenderedPageKey = null;
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
            <tr data-definition-path="${escapeAttr(item.path)}">
                <td><code>${escapeHtml(item.id || '-')}</code></td>
                <td title="${escapeAttr(displayName)}">${escapeHtml(displayName)}</td>
                <td>${isBuiltin
                    ? '<span class="badge builtin">内置</span>'
                    : '<span class="badge custom">自定义</span>'}</td>
                <td>${renderScheduleCron(item.schedule)}</td>
                <td class="definition-status">${statusBadge(latestRun?.status)}</td>
                <td class="actions-cell"><div class="actions">${renderActionsCell(item, rowIndex, displayName, fileName, item.path, activeRun, isBuiltin, scheduleEnabled)}</div></td>
            </tr>`;
    }).join('');

    lastRenderedPageKey = currentPageDefinitionKey();

    renderDefinitionsPagination();
    refreshOpenLogModal();
    restoreOpenActionMenu(preservedMenuId);
}

function renderActionsCell(item, index, displayName, fileName, path, activeRun, isBuiltin, scheduleEnabled) {
    const menuId = `action-menu-${index}`;
    const stopDisabled = !activeRun;
    const stopRunId = activeRun?.runId || '';
    let menuItems = `
        <button type="button" class="action-menu-item" onclick="viewDefinition('${escapeAttr(fileName)}'); closeActionMenus()">查看</button>
        <button type="button" class="action-menu-item" onclick="previewDefinition('${escapeAttr(displayName)}', '${escapeAttr(path)}'); closeActionMenus()">预览</button>
        <button type="button" class="action-menu-item" onclick="viewDefinitionLogs('${escapeAttr(displayName)}', '${escapeAttr(path)}'); closeActionMenus()">日志</button>
        <button type="button" class="action-menu-item action-stop-btn${stopDisabled ? ' disabled' : ''}"
            ${stopDisabled ? 'disabled title="当前无运行中的任务"' : `onclick="stopRun('${escapeAttr(stopRunId)}'); closeActionMenus()"`}>停止</button>`;
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
        openActionMenuId = menuId;
    }
}

function closeActionMenus() {
    document.querySelectorAll('.action-menu-dropdown').forEach(el => el.classList.add('hidden'));
    openActionMenuId = null;
}

async function openDefinitionModal(fileName, content, readOnly = false, schedule = null, displayName = null) {
    editingDefinition = fileName || null;
    const title = fileName
        ? (readOnly ? `查看任务: ${displayName || fileName}` : `编辑任务: ${displayName || fileName}`)
        : '新建任务';
    document.getElementById('modal-title').textContent = title;
    document.getElementById('definition-name').value = displayName || fileName || '';
    document.getElementById('definition-name').disabled = !!readOnly;
    document.getElementById('name-field').style.display = 'flex';
    document.getElementById('definition-content').value = content || DEFAULT_JOB_TEMPLATE;
    document.getElementById('modal-save').style.display = readOnly ? 'none' : '';
    document.getElementById('guide-toggle').style.display = readOnly ? 'none' : '';
    applyScheduleFields(schedule, readOnly, !fileName);
    document.getElementById('modal').classList.remove('hidden');
    ensureGuideLoaded();
    await mountYamlEditor(content || DEFAULT_JOB_TEMPLATE, readOnly);
}

async function mountYamlEditor(content, readOnly) {
    const host = document.getElementById('definition-editor');
    await createYamlEditor(host, content, readOnly);
}

function toggleGuidePanel() {
    const panel = document.getElementById('guide-panel');
    const button = document.getElementById('guide-toggle');
    const hidden = panel.classList.toggle('hidden');
    button.setAttribute('aria-expanded', String(!hidden));
    button.textContent = hidden ? '显示参考' : '配置参考';
}

async function ensureGuideLoaded() {
    if (guideLoaded) {
        return;
    }
    const body = document.getElementById('guide-body');
    const toc = document.getElementById('guide-toc');
    try {
        const response = await fetch(DOCS_GUIDE_PATH, { credentials: 'same-origin' });
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
        const markdown = await response.text();
        body.innerHTML = renderMarkdown(markdown);
        buildMarkdownToc(body, toc);
        guideLoaded = true;
    } catch (err) {
        body.innerHTML = `<div class="docs-error">加载配置指南失败：${escapeHtml(err.message)}</div>`;
        toc.innerHTML = '';
    }
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
    destroyYamlEditor();
    editingDefinition = null;
    editingScheduleEditable = false;
}

async function viewDefinition(fileName) {
    try {
        const item = await api(`/task-configs/${encodeURIComponent(fileName)}`);
        openDefinitionModal(fileName, item.content, true, item.schedule, item.name);
    } catch (err) {
        showToast('加载失败: ' + err.message);
    }
}

async function editDefinition(fileName) {
    try {
        const item = await api(`/task-configs/${encodeURIComponent(fileName)}`);
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
    const content = getYamlEditorValue() || document.getElementById('definition-content').value;
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
            await api(`/task-configs/${encodeURIComponent(editingDefinition)}`, {
                method: 'PUT',
                body: JSON.stringify(payload)
            });
            showToast('任务已更新');
        } else {
            await api('/task-configs', {
                method: 'POST',
                body: JSON.stringify(payload)
            });
            showToast('任务已创建');
        }
        closeModal();
        loadDefinitions({ fullRender: true });
    } catch (err) {
        showToast('保存失败: ' + err.message);
    }
}

async function deleteDefinition(fileName) {
    if (!confirm(`确定删除任务配置 "${fileName}"？`)) return;
    try {
        await api(`/task-configs/${encodeURIComponent(fileName)}`, { method: 'DELETE' });
        showToast('已删除');
        loadDefinitions({ fullRender: true });
    } catch (err) {
        showToast('删除失败: ' + err.message);
    }
}

function openPreviewModal(displayName, path) {
    previewContext = {
        displayName,
        path,
        tables: [],
        activeTab: 0
    };
    document.getElementById('preview-title').textContent = `数据预览: ${displayName}`;
    document.getElementById('preview-content').innerHTML = '<p class="preview-loading">正在生成预览数据...</p>';
    document.getElementById('preview-pagination').classList.add('hidden');
    document.getElementById('preview-pagination').innerHTML = '';
    document.getElementById('preview-modal').classList.remove('hidden');
}

function closePreviewModal() {
    document.getElementById('preview-modal').classList.add('hidden');
    previewContext = { displayName: null, path: null, tables: [], activeTab: 0 };
    document.getElementById('preview-content').innerHTML = '';
    document.getElementById('preview-pagination').classList.add('hidden');
    document.getElementById('preview-pagination').innerHTML = '';
}

async function previewDefinition(displayName, path) {
    openPreviewModal(displayName, path);
    await runPreview();
}

function renderPreviewTableBody(table) {
    const columns = table.columns || [];
    const rows = table.rows || [];
    let html = '';
    if (table.schemaTable && table.schemaTable !== table.tableName) {
        html += `<p class="preview-table-meta">物理表: ${escapeHtml(table.schemaTable)}</p>`;
    }
    if (!rows.length) {
        return html + '<p class="preview-empty">该表无预览数据</p>';
    }
    html += '<div class="table-wrap preview-table-wrap scrollbar-overlay"><table><thead><tr>';
    for (const col of columns) {
        html += `<th>${escapeHtml(col)}</th>`;
    }
    html += '</tr></thead><tbody>';
    for (const row of rows) {
        html += '<tr>';
        for (const col of columns) {
            html += `<td>${formatPreviewCell(row[col])}</td>`;
        }
        html += '</tr>';
    }
    html += '</tbody></table></div>';
    return html;
}

function previewTabLabel(table) {
    const name = table.tableName || '未命名表';
    const count = (table.rows || []).length;
    return count > 0 ? `${name} (${count})` : name;
}

function switchPreviewTab(index) {
    if (index < 0 || index >= previewContext.tables.length) {
        return;
    }
    previewContext.activeTab = index;
    renderPreviewTables();
}

function renderPreviewTables() {
    const panel = document.getElementById('preview-content');
    const { tables, activeTab } = previewContext;

    if (!tables.length) {
        panel.innerHTML = '<p class="preview-empty">无数据</p>';
        return;
    }

    if (tables.length === 1) {
        panel.innerHTML = `<section class="preview-table-section">${renderPreviewTableBody(tables[0])}</section>`;
        window.initOverlayScrollbars?.(panel);
        return;
    }

    let html = '<div class="preview-tabs">';
    html += '<div class="preview-tab-bar" role="tablist">';
    tables.forEach((table, index) => {
        const active = index === activeTab ? ' active' : '';
        html += `<button type="button" class="preview-tab${active}" role="tab" aria-selected="${index === activeTab}"
            onclick="switchPreviewTab(${index})">${escapeHtml(previewTabLabel(table))}</button>`;
    });
    html += '</div>';
    html += '<div class="preview-tab-panels">';
    tables.forEach((table, index) => {
        const hidden = index === activeTab ? '' : ' hidden';
        html += `<section class="preview-tab-panel${hidden}" role="tabpanel">${renderPreviewTableBody(table)}</section>`;
    });
    html += '</div></div>';
    panel.innerHTML = html;
    window.initOverlayScrollbars?.(panel);
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
                configPath: previewContext.path,
                preview: { limit: PREVIEW_FETCH_LIMIT }
            })
        });
        previewContext.tables = result.tables || [];
        previewContext.activeTab = 0;
        renderPreviewTables();
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
        const result = await api('/task-runs', {
            method: 'POST',
            body: JSON.stringify({ configPath: path })
        });
        if (result.status === 'PENDING') {
            showToast(`任务已加入队列: ${result.runId}`);
        } else {
            showToast(`任务已提交: ${result.runId} (${result.status})`);
        }
        await loadDefinitions({ fullRender: true });
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
            .filter(run => run.configPath === path)
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
        <tr class="log-run-row${expanded ? ' selected' : ''}" data-run-id="${escapeAttr(run.runId)}" onclick="toggleRunLogDetail('${escapeAttr(run.runId)}')">
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

function openLogListModal(name, path, runs, options = {}) {
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
        const job = await api(`/task-runs/${encodeURIComponent(runId)}`);
        const run = logModalContext.runs.find(item => item.runId === runId) || job;
        const progress = job.progress || {};

        const logs = await api(`/task-runs/${encodeURIComponent(runId)}/logs`);
        logModalContext.logDetailLines[runId] = logs;
        panel.innerHTML = `
            ${buildLogDetailSummaryHtml(job, progress)}
            <pre class="log-view scrollbar-overlay">${renderLogLines(logs)}</pre>
        `;
        window.initOverlayScrollbars?.(panel);

        if (run && run.status !== job.status) {
            run.status = job.status;
        }
        updateRunRowCells(runId, job, progress);
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
        const job = await api(`/task-runs/${encodeURIComponent(runId)}`);
        const progress = job.progress || {};
        const logs = await api(`/task-runs/${encodeURIComponent(runId)}/logs`);
        logModalContext.logDetailLines[runId] = logs;

        const summary = panel.querySelector('.log-detail-summary');
        if (summary) {
            summary.outerHTML = buildLogDetailSummaryHtml(job, progress);
        } else {
            panel.innerHTML = `
                ${buildLogDetailSummaryHtml(job, progress)}
                <pre class="log-view scrollbar-overlay">${renderLogLines(logs)}</pre>
            `;
            window.initOverlayScrollbars?.(panel);
        }

        const targetLogView = panel.querySelector('.log-view');
        if (targetLogView) {
            targetLogView.innerHTML = renderLogLines(logs);
            applyScrollState(targetLogView, scrollState);
        }

        const run = logModalContext.runs.find(item => item.runId === runId);
        if (run) {
            run.status = job.status;
            run.duration = job.duration;
            run.writtenRows = progress.writtenRows;
            run.totalRows = progress.totalRows;
        }
        updateRunRowCells(runId, job, progress);
    } catch (_) {
        // 自动刷新失败时不打断当前阅读
    }
}

async function refreshOpenLogModal() {
    if (!isLogModalOpen() || !logModalContext.definitionPath) {
        return;
    }

    const runs = allRunsCache
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

async function stopRun(runId) {
    if (!runId) {
        showToast('当前无运行中的任务');
        return;
    }
    if (!confirm(`确定停止任务 ${runId}？`)) return;
    try {
        const runs = await fetchAllJobs();
        allRunsCache = runs;
        const current = runs.find(run => run.runId === runId);
        if (!current || !isActiveRun(current.status)) {
            showToast('任务已结束');
            await loadDefinitions({ fullRender: true });
            return;
        }
        await api(`/task-runs/${encodeURIComponent(runId)}`, { method: 'DELETE' });
        showToast('任务已停止');
        await loadDefinitions({ fullRender: false });
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

loadDefinitions({ fullRender: true });
initNavigation();
ensureAutoRefresh();

document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
        stopAutoRefresh();
        return;
    }
    if (currentView === 'overview') {
        loadOverview().catch(() => {});
    } else if (currentView === 'tasks') {
        refreshRuntimeSnapshot().catch(() => {});
    }
    ensureAutoRefresh();
});

function openDefinitionModalForAi(yaml) {
    openDefinitionModal(null, yaml, false, null, null).catch(err => showToast('打开编辑器失败: ' + err.message));
}
window.openDefinitionModalForAi = openDefinitionModalForAi;

Object.assign(window, {
    changeDefinitionsPage,
    navigateToView,
    viewOverviewRun,
    viewDefinition,
    previewDefinition,
    viewDefinitionLogs,
    stopRun,
    editDefinition,
    deleteDefinition,
    runDefinition,
    toggleActionMenu,
    closeActionMenus,
    switchPreviewTab,
    changeLogPage,
    toggleRunLogDetail
});

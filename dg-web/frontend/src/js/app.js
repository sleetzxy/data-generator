/** 应用入口：导航与视图切换、自动刷新回调注入、启动引导。 */

import { showToast } from './core/ui.js';
import {
    fetchAllTaskRuns,
    getCurrentView,
    rebuildRunIndexes,
    setAllRunsCache,
    setCurrentView
} from './core/state.js';
import { ensureAutoRefresh, setRefreshHandler, stopAutoRefresh } from './core/refresh.js';
import { initOverlayScrollbars } from './lib/scrollbar.js';
import { initOverview, loadOverview, syncOverviewInPlace } from './views/overview.js';
import { canSyncDefinitionsInPlace, initTasks, loadDefinitions, syncDefinitionsTableInPlace } from './views/tasks.js';
import { initLogs, refreshOpenLogModal } from './views/logs.js';
import { initPreview } from './views/preview.js';
import { loadGuide } from './views/docs.js';
import { initAgent, onAiViewShown } from './views/agent.js';

const VIEW_TITLES = {
    overview: '运行概览',
    tasks: '任务管理',
    ai: 'AI 助手',
    docs: '配置指南'
};

let docsLoaded = false;

function initNavigation() {
    window.addEventListener('hashchange', () => {
        const view = window.location.hash.replace('#', '');
        if (view && VIEW_TITLES[view] && view !== getCurrentView()) {
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

function switchView(view) {
    setCurrentView(view);
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
    } else if (view === 'ai') {
        onAiViewShown();
    }
    ensureAutoRefresh();
}

async function loadDocsView() {
    if (docsLoaded) {
        return;
    }
    await loadGuide();
    docsLoaded = true;
    const docsView = document.getElementById('view-docs');
    initOverlayScrollbars(docsView?.querySelector('.docs-sidebar'));
    initOverlayScrollbars(docsView?.querySelector('.docs-content'));
}

/** 自动刷新回调：拉取最新运行记录并同步各视图 */
async function refreshRuntimeSnapshot() {
    setAllRunsCache(await fetchAllTaskRuns());
    rebuildRunIndexes();

    if (getCurrentView() === 'overview') {
        syncOverviewInPlace();
    }

    if (getCurrentView() === 'tasks' && canSyncDefinitionsInPlace()) {
        syncDefinitionsTableInPlace();
    }

    await refreshOpenLogModal();
}

// ── 启动引导 ──

initOverlayScrollbars();
initOverview();
initTasks();
initLogs();
initPreview();
initAgent();
setRefreshHandler(refreshRuntimeSnapshot);
loadDefinitions({ fullRender: true });
initNavigation();
ensureAutoRefresh();

document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
        stopAutoRefresh();
        return;
    }
    if (getCurrentView() === 'overview') {
        loadOverview().catch(() => {});
    } else if (getCurrentView() === 'tasks') {
        refreshRuntimeSnapshot().catch(() => {});
    }
    ensureAutoRefresh();
});

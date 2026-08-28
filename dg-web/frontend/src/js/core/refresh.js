/** 自动刷新引擎：按视图与运行状态决定轮询节奏，回调由应用入口注入。 */

import { getCurrentView, hasActiveRuns } from './state.js';

const AUTO_REFRESH_INTERVAL_MS = 5000;
const AUTO_REFRESH_ACTIVE_INTERVAL_MS = 2000;

let refreshHandler = null;
let autoRefreshTimer = null;

/** 由入口注入刷新回调（拉取运行记录并同步各视图） */
export function setRefreshHandler(handler) {
    refreshHandler = handler;
}

/** 日志弹窗是否打开（直接查 DOM，避免与视图模块形成循环依赖） */
function isLogModalOpen() {
    return !document.getElementById('log-modal').classList.contains('hidden');
}

function shouldAutoRefresh() {
    if (getCurrentView() === 'overview') {
        return true;
    }
    if (isLogModalOpen()) {
        return true;
    }
    if (getCurrentView() === 'tasks' && hasActiveRuns()) {
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

export function ensureAutoRefresh() {
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
            if (!document.hidden && shouldAutoRefresh() && refreshHandler) {
                await refreshHandler();
            }
        } catch (_) {
            // 自动刷新失败时不打断后续轮询
        }
        ensureAutoRefresh();
    };
    autoRefreshTimer = setTimeout(tick, resolveAutoRefreshIntervalMs());
}

export function stopAutoRefresh() {
    if (autoRefreshTimer) {
        clearTimeout(autoRefreshTimer);
        autoRefreshTimer = null;
    }
}

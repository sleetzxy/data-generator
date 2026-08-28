/** 运行概览视图：统计卡片、进行中任务、最近运行。 */

import { api } from '../core/api.js';
import { escapeAttr, escapeHtml, formatTime, showToast, statusBadge } from '../core/ui.js';
import {
    fetchAllTaskRuns,
    getAllDefinitionsCache,
    getAllRunsCache,
    getCurrentView,
    isActiveRun,
    rebuildRunIndexes,
    resolveConfigDisplayName,
    setAllDefinitionsCache,
    setAllRunsCache
} from '../core/state.js';
import { openLogListModal } from './logs.js';

let overviewLoaded = false;

/** 绑定概览表格的行点击委托事件 */
export function initOverview() {
    document.getElementById('overview-active-body').addEventListener('click', (event) => {
        const row = event.target.closest('tr.overview-run-row');
        if (row) {
            viewOverviewRun(row.dataset.runId, row.dataset.configPath);
        }
    });

    document.getElementById('overview-recent-body').addEventListener('click', (event) => {
        const row = event.target.closest('tr.overview-run-row');
        if (row) {
            viewOverviewRun(row.dataset.runId, row.dataset.configPath);
        }
    });
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
        <tr class="overview-run-row" data-run-id="${escapeAttr(run.runId)}" data-config-path="${escapeAttr(run.configPath)}">
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
        <tr class="overview-run-row" data-run-id="${escapeAttr(run.runId)}" data-config-path="${escapeAttr(run.configPath)}">
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

export async function loadOverview() {
    const [configs, runs] = await Promise.all([
        api('/task-configs'),
        fetchAllTaskRuns()
    ]);
    setAllDefinitionsCache(configs);
    setAllRunsCache(runs);
    rebuildRunIndexes();

    const stats = computeRunStats(runs);
    renderOverviewStats(stats, getAllDefinitionsCache().length);
    renderOverviewActiveRuns(runs);
    renderOverviewRecentRuns(runs);
    overviewLoaded = true;
}

/** 概览页原地同步最新数据（自动刷新回调使用） */
export function syncOverviewInPlace() {
    if (getCurrentView() !== 'overview' || !overviewLoaded) {
        return;
    }
    const runs = getAllRunsCache();
    const stats = computeRunStats(runs);
    renderOverviewStats(stats, getAllDefinitionsCache().length);
    renderOverviewActiveRuns(runs);
    renderOverviewRecentRuns(runs);
}

async function viewOverviewRun(runId, configPath) {
    const name = resolveConfigDisplayName(configPath);
    try {
        let runs = getAllRunsCache();
        if (!runs.length) {
            runs = await fetchAllTaskRuns();
            setAllRunsCache(runs);
        }
        const matched = runs
            .filter(run => run.configPath === configPath)
            .sort((a, b) => new Date(b.submittedAt) - new Date(a.submittedAt));
        if (!matched.length) {
            showToast('暂无运行记录');
            return;
        }
        openLogListModal(name, configPath, matched, { selectedRunId: runId });
    } catch (err) {
        showToast('加载失败: ' + err.message);
    }
}

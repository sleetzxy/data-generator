/** 运行概览视图：统计卡片、进行中任务、最近运行。所有统计与聚合均由后端 /task-runs/stats 提供。 */

import { api } from '../core/api.js';
import { escapeAttr, escapeHtml, formatCompactNumber, formatTime, showToast, statusBadge } from '../core/ui.js';
import {
    getAllDefinitionsCache,
    getCurrentView,
    resolveConfigDisplayName,
    setAllDefinitionsCache
} from '../core/state.js';
import { openLogListModal } from './logs.js';

const RECENT_RUN_LIMIT = 15;
const ACTIVE_RUN_LIMIT = 200;

let overviewLoaded = false;
/** 同步刷新请求序号：晚到的响应直接丢弃，避免覆盖更新数据 */
let overviewSyncSeq = 0;
/** 上次渲染的完整快照（JSON），数据未变时跳过 DOM 重建 */
let lastRenderedSnapshot = null;

/** 绑定概览表格的行点击委托事件 */
export function initOverview() {
    document.getElementById('overview-active-body').addEventListener('click', (event) => {
        const row = event.target.closest('tr.overview-run-row');
        if (row) {
            viewOverviewRun(row.dataset.runId, row.dataset.configPath, row.dataset.displayName);
        }
    });

    document.getElementById('overview-recent-body').addEventListener('click', (event) => {
        const row = event.target.closest('tr.overview-run-row');
        if (row) {
            viewOverviewRun(row.dataset.runId, row.dataset.configPath, row.dataset.displayName);
        }
    });
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
            <div class="stat-card-value stat-default">${stats.totalRuns}</div>
        </div>
        <div class="stat-card stat-accent-volume">
            <div class="stat-card-label">累计写入行数</div>
            <div class="stat-card-value stat-default">${formatCompactNumber(stats.totalWritten)}</div>
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
    // 后端按 submitted_at DESC 返回，此处再排序仅作防御
    const activeRuns = [...runs]
        .sort((a, b) => new Date(b.submittedAt) - new Date(a.submittedAt));

    document.getElementById('overview-active-count').textContent = `${activeRuns.length} 个`;

    if (!activeRuns.length) {
        tbody.innerHTML = '<tr class="empty-row"><td colspan="5">当前无进行中的任务</td></tr>';
        return;
    }

    tbody.innerHTML = activeRuns.map(run => `
        <tr class="overview-run-row" data-run-id="${escapeAttr(run.runId)}" data-config-path="${escapeAttr(run.configPath)}" data-display-name="${escapeAttr(run.displayName || '')}">
            <td><code>${escapeHtml(run.runId)}</code></td>
            <td class="overview-config-name" title="${escapeAttr(run.displayName || resolveConfigDisplayName(run.configPath))}">
                <code>${escapeHtml(run.displayName || resolveConfigDisplayName(run.configPath))}</code>
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
        .sort((a, b) => new Date(b.submittedAt) - new Date(a.submittedAt));

    if (!recentRuns.length) {
        tbody.innerHTML = '<tr class="empty-row"><td colspan="5">暂无运行记录</td></tr>';
        return;
    }

    tbody.innerHTML = recentRuns.map(run => `
        <tr class="overview-run-row" data-run-id="${escapeAttr(run.runId)}" data-config-path="${escapeAttr(run.configPath)}" data-display-name="${escapeAttr(run.displayName || '')}">
            <td><code>${escapeHtml(run.runId)}</code></td>
            <td class="overview-config-name" title="${escapeAttr(run.displayName || resolveConfigDisplayName(run.configPath))}">
                <code>${escapeHtml(run.displayName || resolveConfigDisplayName(run.configPath))}</code>
            </td>
            <td>${statusBadge(run.status)}</td>
            <td>${escapeHtml(run.duration || '-')}</td>
            <td>${formatTime(run.submittedAt)}</td>
        </tr>
    `).join('');
}

/** 渲染任务数据量排行条形图（Top 10，按累计写入行数降序，由后端聚合） */
function renderOverviewVolumeBars(ranking) {
    const container = document.getElementById('overview-volume-bars');
    if (!ranking.length) {
        container.innerHTML = '<div class="volume-empty">暂无运行记录</div>';
        return;
    }
    const maxWritten = Math.max(...ranking.map(item => item.writtenRows), 1);
    container.innerHTML = ranking.map(item => {
        const displayName = item.displayName || resolveConfigDisplayName(item.configPath);
        const width = Math.max(2, Math.round((item.writtenRows / maxWritten) * 100));
        return `
            <div class="volume-bar-row" title="${escapeAttr(`${displayName}：累计写入 ${formatCompactNumber(item.writtenRows)} 行，共 ${item.runCount} 次运行`)}">
                <span class="volume-bar-name" title="${escapeAttr(displayName)}">${escapeHtml(displayName)}</span>
                <div class="volume-bar-track"><div class="volume-bar-fill" style="width: ${width}%"></div></div>
                <span class="volume-bar-value">${formatCompactNumber(item.writtenRows)}</span>
            </div>`;
    }).join('');
}

/** 渲染每日写入趋势 SVG 柱状图（后端返回最近 14 天完整序列，缺失日期补零） */
function renderDailyVolumeChart(days) {
    const container = document.getElementById('overview-daily-volume');
    const width = 600;
    const height = 210;
    const padLeft = 56;
    const padRight = 8;
    const padTop = 10;
    const padBottom = 26;
    const plotWidth = width - padLeft - padRight;
    const plotHeight = height - padTop - padBottom;
    const maxWritten = Math.max(...days.map(day => day.writtenRows), 1);
    const band = plotWidth / days.length;
    const barWidth = Math.min(28, band * 0.6);

    // y 轴刻度与网格线（0、1/2、最大）
    const gridLines = [0, maxWritten / 2, maxWritten].map(value => {
        const y = padTop + plotHeight - (value / maxWritten) * plotHeight;
        return `<line x1="${padLeft}" y1="${y}" x2="${width - padRight}" y2="${y}" class="daily-grid-line"/>`
                + `<text x="${padLeft - 8}" y="${y + 4}" class="daily-axis-label" text-anchor="end">${formatCompactNumber(value)}</text>`;
    }).join('');

    // 柱（零值画 2px 底边提示，悬停显示完整数据）
    const bars = days.map((day, index) => {
        const x = padLeft + index * band + (band - barWidth) / 2;
        const tip = `${day.date}：${formatCompactNumber(day.writtenRows)} 行 · ${day.runCount} 次运行`;
        if (day.writtenRows === 0) {
            return `<rect x="${x}" y="${padTop + plotHeight - 2}" width="${barWidth}" height="2" rx="1" class="daily-bar daily-bar-zero"><title>${tip}</title></rect>`;
        }
        const barHeight = Math.round((day.writtenRows / maxWritten) * plotHeight);
        return `<rect x="${x}" y="${padTop + plotHeight - barHeight}" width="${barWidth}" height="${barHeight}" rx="4" class="daily-bar"><title>${tip}</title></rect>`;
    }).join('');

    // x 轴日期标签：从最近一天向前每 2 天标一个
    const xLabels = days.map((day, index) => {
        if ((days.length - 1 - index) % 2 !== 0) {
            return '';
        }
        const label = day.date.slice(5).replace('-', '/');
        return `<text x="${padLeft + index * band + band / 2}" y="${height - 8}" class="daily-axis-label" text-anchor="middle">${label}</text>`;
    }).join('');

    container.innerHTML = `
        <svg class="daily-chart" viewBox="0 0 ${width} ${height}" role="img" aria-label="近 14 天每日写入行数趋势">
            ${gridLines}
            ${bars}
            ${xLabels}
        </svg>`;
}

/** 并行拉取概览所需数据：统计、配置缓存、活跃运行、最近运行 */
async function fetchOverviewData() {
    const [stats, configList, activeList, recentList] = await Promise.all([
        api('/task-runs/stats'),
        api('/task-configs'),
        api(`/task-runs?status=${encodeURIComponent('RUNNING,PENDING')}&page=1&size=${ACTIVE_RUN_LIMIT}`),
        api(`/task-runs?page=1&size=${RECENT_RUN_LIMIT}`)
    ]);
    return {
        stats,
        configList,
        activeRuns: activeList.items || [],
        recentRuns: recentList.items || []
    };
}

/** 组装渲染快照（含排序），用于渲染与变更比对 */
function toOverviewSnapshot(data) {
    const activeRuns = [...data.activeRuns]
        .sort((a, b) => new Date(b.submittedAt) - new Date(a.submittedAt));
    const recentRuns = [...data.recentRuns]
        .sort((a, b) => new Date(b.submittedAt) - new Date(a.submittedAt));
    return {
        stats: data.stats,
        activeRuns,
        recentRuns
    };
}

function renderOverview(snapshot) {
    renderOverviewStats(snapshot.stats, getAllDefinitionsCache().length);
    renderOverviewActiveRuns(snapshot.activeRuns);
    renderOverviewRecentRuns(snapshot.recentRuns);
    renderOverviewVolumeBars(snapshot.stats.topConfigs);
    renderDailyVolumeChart(snapshot.stats.daily);
}

export async function loadOverview() {
    const data = await fetchOverviewData();
    setAllDefinitionsCache(data.configList?.items || []);
    const snapshot = toOverviewSnapshot(data);
    lastRenderedSnapshot = JSON.stringify(snapshot);
    renderOverview(snapshot);
    overviewLoaded = true;
}

/** 概览页原地同步最新数据（自动刷新回调使用）；数据未变化时跳过重建 */
export async function syncOverviewInPlace() {
    if (getCurrentView() !== 'overview' || !overviewLoaded) {
        return;
    }
    const seq = ++overviewSyncSeq;
    try {
        const data = await fetchOverviewData();
        if (seq !== overviewSyncSeq) {
            return; // 已有更新的刷新在途，丢弃本次结果
        }
        setAllDefinitionsCache(data.configList?.items || []);
        const snapshot = toOverviewSnapshot(data);
        const key = JSON.stringify(snapshot);
        if (key === lastRenderedSnapshot) {
            return; // 数据未变化，跳过 DOM 重建，避免打断图表阅读
        }
        lastRenderedSnapshot = key;
        renderOverview(snapshot);
    } catch (_) {
        // 自动刷新失败时不打断当前展示
    }
}

async function viewOverviewRun(runId, configPath, displayName) {
    const name = displayName || resolveConfigDisplayName(configPath);
    try {
        await openLogListModal(name, configPath, runId);
    } catch (err) {
        showToast('加载失败: ' + err.message);
    }
}

/** 运行时共享状态：任务运行记录缓存、任务配置缓存与索引、当前视图。 */

import { api } from './api.js';

let allRunsCache = [];
let allDefinitionsCache = [];
let definitionsCache = [];
let currentView = 'tasks';

/** 每个配置路径的最新一次运行 */
let latestRunByPath = new Map();
/** 每个配置路径当前活跃（等待中/运行中）的运行 */
let activeRunByPath = new Map();

export const getAllRunsCache = () => allRunsCache;
export const setAllRunsCache = value => { allRunsCache = value; };

export const getAllDefinitionsCache = () => allDefinitionsCache;
export const setAllDefinitionsCache = value => { allDefinitionsCache = value; };

export const getDefinitionsCache = () => definitionsCache;
export const setDefinitionsCache = value => { definitionsCache = value; };

export const getCurrentView = () => currentView;
export const setCurrentView = value => { currentView = value; };

/** 运行状态是否为等待中或运行中 */
export function isActiveRun(status) {
    return status === 'PENDING' || status === 'RUNNING';
}

/** 依据全部运行记录重建「最新运行」与「活跃运行」索引 */
export function rebuildRunIndexes() {
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

export function findLatestRun(path) {
    return latestRunByPath.get(path) || null;
}

export function findActiveRun(path) {
    return activeRunByPath.get(path) || null;
}

export function hasActiveRuns() {
    return allRunsCache.some(run => isActiveRun(run.status));
}

/** 由配置路径解析展示名称：优先取任务名称，其次文件名，最后回退路径本身 */
export function resolveConfigDisplayName(configPath) {
    const cache = allDefinitionsCache.length ? allDefinitionsCache : definitionsCache;
    const def = cache.find(item => item.path === configPath);
    return def?.name || configPath?.split('/').pop()?.replace(/\.yaml$/, '') || configPath || '-';
}

/** 分页拉取全部运行记录（size=100 循环直到取完） */
export async function fetchAllTaskRuns() {
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

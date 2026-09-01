/** 运行时共享状态：任务配置缓存、按配置聚合的运行索引、当前视图。 */

import { api } from './api.js';

let allDefinitionsCache = [];
let definitionsCache = [];
let currentView = 'overview';

/** 每个配置路径的最新一次运行 */
let latestRunByPath = new Map();
/** 每个配置路径当前活跃（等待中/运行中）的运行 */
let activeRunByPath = new Map();

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

/** 由后端 /task-runs/by-config 聚合结果重建「最新运行」与「活跃运行」索引 */
export async function loadRunIndexes() {
    const data = await api('/task-runs/by-config');
    latestRunByPath = new Map();
    activeRunByPath = new Map();
    for (const run of (data.latestRuns || [])) {
        latestRunByPath.set(run.configPath, run);
    }
    for (const run of (data.activeRuns || [])) {
        activeRunByPath.set(run.configPath, run);
    }
}

export function findLatestRun(path) {
    return latestRunByPath.get(path) || null;
}

export function findActiveRun(path) {
    return activeRunByPath.get(path) || null;
}

export function hasActiveRuns() {
    return activeRunByPath.size > 0;
}

/** 由配置路径解析展示名称：优先取任务名称，其次文件名，最后回退路径本身 */
export function resolveConfigDisplayName(configPath) {
    const cache = allDefinitionsCache.length ? allDefinitionsCache : definitionsCache;
    const def = cache.find(item => item.path === configPath);
    return def?.name || configPath?.split('/').pop()?.replace(/\.yaml$/, '') || configPath || '-';
}

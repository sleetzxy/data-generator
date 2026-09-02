/** 任务管理视图：任务列表、搜索分页、动作菜单、编辑器弹窗、定时调度、运行/停止。 */

import { api } from '../core/api.js';
import { escapeAttr, escapeHtml, formatTime, showToast, statusBadge } from '../core/ui.js';
import {
    findActiveRun,
    findLatestRun,
    getDefinitionsCache,
    isActiveRun,
    loadRunIndexes,
    setAllDefinitionsCache,
    setDefinitionsCache
} from '../core/state.js';
import { ensureAutoRefresh } from '../core/refresh.js';
import { openLogListModal, refreshOpenLogModal } from './logs.js';
import { previewDefinition } from './preview.js';
import { buildMarkdownToc, renderMarkdown } from '../lib/markdown.js';
import { createYamlEditor, destroyYamlEditor, getYamlEditorValue } from '../lib/yaml-editor.js';

const DEFINITION_PAGE_SIZE = 20;
const DOCS_GUIDE_PATH = '/docs/config-guide.md';

const DEFAULT_TASK_TEMPLATE = `writer:
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
let definitionsPage = 1;
let definitionsTotal = 0;
let definitionSearchQuery = '';
let definitionSearchTimer = null;
let guideLoaded = false;
let openActionMenuId = null;
let lastRenderedPageKey = null;
let definitionsUiFrame = null;

/** 绑定工具栏、编辑器弹窗与表格/分页委托事件 */
export function initTasks() {
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

    // 表格委托：动作菜单与运行按钮
    document.getElementById('definitions-body').addEventListener('click', (event) => {
        const toggle = event.target.closest('.action-menu-toggle');
        if (toggle) {
            toggleActionMenu(toggle.dataset.menuId);
            return;
        }
        const item = event.target.closest('.action-menu-item');
        if (item) {
            const action = item.dataset.action;
            // 先执行动作（confirm 等同步交互优先呈现），再关闭菜单，与原内联顺序一致
            switch (action) {
                case 'preview': previewDefinition(item.dataset.name, item.dataset.path); break;
                case 'logs': viewDefinitionLogs(item.dataset.name, item.dataset.path); break;
                case 'stop': stopRun(item.dataset.runId); break;
                case 'edit': editDefinition(item.dataset.file); break;
                case 'delete': deleteDefinition(item.dataset.file); break;
            }
            closeActionMenus();
            return;
        }
        const runButton = event.target.closest('.action-run-btn');
        if (runButton) {
            runDefinition(runButton.dataset.path, runButton.dataset.scheduleEnabled === 'true');
        }
    });

    // 分页委托
    document.getElementById('definitions-pagination').addEventListener('click', (event) => {
        const button = event.target.closest('button[data-page]');
        if (button) {
            changeDefinitionsPage(parseInt(button.dataset.page, 10));
        }
    });

    // 点击动作菜单外部时关闭所有菜单
    document.addEventListener('click', (event) => {
        if (!event.target.closest('.action-menu')) {
            closeActionMenus();
        }
    });
}

function renderScheduleCron(schedule) {
    if (!schedule || !schedule.enabled || !schedule.cron) {
        return '<span class="muted">—</span>';
    }
    return `<code>${escapeHtml(schedule.cron)}</code>`;
}

function buildTaskConfigListUrl() {
    const query = definitionSearchQuery.trim();
    const params = new URLSearchParams({
        page: String(definitionsPage),
        size: String(DEFINITION_PAGE_SIZE)
    });
    if (query) {
        params.set('name', query);
    }
    return `/task-configs?${params.toString()}`;
}

/** 拉取当前页的任务配置列表（后端分页）；页数收缩时回退到最后一页重取 */
async function fetchDefinitionsPage() {
    let listResult = await api(buildTaskConfigListUrl());
    const totalPages = getDefinitionTotalPages(listResult.total || 0);
    if (definitionsPage > totalPages) {
        definitionsPage = totalPages;
        listResult = await api(buildTaskConfigListUrl());
    }
    return listResult;
}

export async function loadDefinitions(options = {}) {
    const fullRender = options.fullRender === true;
    const tbody = document.getElementById('definitions-body');
    try {
        const [listResult, allListResult] = await Promise.all([
            fetchDefinitionsPage(),
            api('/task-configs'),
            loadRunIndexes()
        ]);
        const items = listResult.items || [];
        const allItems = allListResult.items || [];
        definitionsTotal = listResult.total || 0;
        setAllDefinitionsCache(allItems);
        setDefinitionsCache(items);

        if (!fullRender && canSyncDefinitionsInPlace()) {
            scheduleDefinitionsUiSync();
            ensureAutoRefresh();
            return;
        }
        renderDefinitionsTable();
        ensureAutoRefresh();
    } catch (err) {
        tbody.innerHTML = `<tr class="empty-row"><td colspan="5">加载失败: ${escapeHtml(err.message)}</td></tr>`;
        renderDefinitionsPagination();
        lastRenderedPageKey = null;
    }
}

function currentPageDefinitionKey() {
    return getDefinitionsCache().map(item => item.path).join('|');
}

export function canSyncDefinitionsInPlace() {
    if (!getDefinitionsCache().length) {
        return false;
    }
    const tbody = document.getElementById('definitions-body');
    if (tbody.querySelector('.empty-row')) {
        return false;
    }
    const rows = tbody.querySelectorAll('tr[data-definition-path]');
    if (rows.length !== getDefinitionsCache().length) {
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

export function syncDefinitionsTableInPlace() {
    const rows = document.querySelectorAll('#definitions-body tr[data-definition-path]');
    for (const row of rows) {
        const path = row.dataset.definitionPath;
        const latestRun = findLatestRun(path);
        const activeRun = findActiveRun(path);
        const statusCell = row.cells[3];
        if (statusCell) {
            const nextStatusHtml = statusBadge(latestRun?.status);
            if (statusCell.innerHTML !== nextStatusHtml) {
                statusCell.innerHTML = nextStatusHtml;
            }
        }
        const actionsCell = row.cells[4];
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
        stopBtn.dataset.runId = '';
        stopBtn.title = '当前无运行中的任务';
    } else {
        stopBtn.dataset.runId = stopRunId;
        stopBtn.removeAttribute('title');
    }
}

function getDefinitionTotalPages(total) {
    return Math.max(1, Math.ceil(total / DEFINITION_PAGE_SIZE));
}

function renderDefinitionsPagination() {
    const pagination = document.getElementById('definitions-pagination');
    const totalPages = getDefinitionTotalPages(definitionsTotal);

    if (definitionsTotal <= DEFINITION_PAGE_SIZE) {
        pagination.classList.add('hidden');
        pagination.innerHTML = '';
        return;
    }

    pagination.classList.remove('hidden');
    pagination.innerHTML = `
        <button type="button" class="btn small" data-page="${definitionsPage - 1}" ${definitionsPage <= 1 ? 'disabled' : ''}>上一页</button>
        <span class="pagination-info">第 ${definitionsPage} / ${totalPages} 页，共 ${definitionsTotal} 条</span>
        <button type="button" class="btn small" data-page="${definitionsPage + 1}" ${definitionsPage >= totalPages ? 'disabled' : ''}>下一页</button>
    `;
}

function changeDefinitionsPage(page) {
    const totalPages = getDefinitionTotalPages(definitionsTotal);
    if (page < 1 || page > totalPages) {
        return;
    }
    definitionsPage = page;
    loadDefinitions({ fullRender: true }).catch(err => showToast('加载失败: ' + err.message));
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
    const totalPages = getDefinitionTotalPages(definitionsTotal);

    if (definitionsPage > totalPages) {
        definitionsPage = totalPages;
    }
    if (definitionsPage < 1) {
        definitionsPage = 1;
    }

    if (!getDefinitionsCache().length) {
        const message = definitionSearchQuery
            ? `未找到匹配「${escapeHtml(definitionSearchQuery)}」的任务`
            : '暂无任务配置';
        tbody.innerHTML = `<tr class="empty-row"><td colspan="5">${message}</td></tr>`;
        renderDefinitionsPagination();
        refreshOpenLogModal();
        lastRenderedPageKey = null;
        return;
    }

    const pagedItems = getDefinitionsCache();
    const startIndex = (definitionsPage - 1) * DEFINITION_PAGE_SIZE;
    tbody.innerHTML = pagedItems.map((item, index) => {
        const latestRun = findLatestRun(item.path);
        const activeRun = findActiveRun(item.path);
        const fileName = item.fileName;
        const displayName = item.name || fileName;
        const scheduleEnabled = item.schedule?.enabled === true;
        const rowIndex = startIndex + index;
        return `
            <tr data-definition-path="${escapeAttr(item.path)}">
                <td><code>${escapeHtml(item.id || '-')}</code></td>
                <td title="${escapeAttr(displayName)}">${escapeHtml(displayName)}</td>
                <td>${renderScheduleCron(item.schedule)}</td>
                <td class="definition-status">${statusBadge(latestRun?.status)}</td>
                <td class="actions-cell"><div class="actions">${renderActionsCell(item, rowIndex, displayName, fileName, item.path, activeRun, scheduleEnabled)}</div></td>
            </tr>`;
    }).join('');

    lastRenderedPageKey = currentPageDefinitionKey();

    renderDefinitionsPagination();
    refreshOpenLogModal();
    restoreOpenActionMenu(preservedMenuId);
}

function renderActionsCell(item, index, displayName, fileName, path, activeRun, scheduleEnabled) {
    const menuId = `action-menu-${index}`;
    const stopDisabled = !activeRun;
    const stopRunId = activeRun?.runId || '';
    const menuItems = `
        <button type="button" class="action-menu-item" data-action="preview" data-name="${escapeAttr(displayName)}" data-path="${escapeAttr(path)}">预览</button>
        <button type="button" class="action-menu-item" data-action="logs" data-name="${escapeAttr(displayName)}" data-path="${escapeAttr(path)}">日志</button>
        <button type="button" class="action-menu-item action-stop-btn${stopDisabled ? ' disabled' : ''}" data-action="stop" data-run-id="${escapeAttr(stopRunId)}"
            ${stopDisabled ? 'disabled title="当前无运行中的任务"' : ''}>停止</button>
        <button type="button" class="action-menu-item" data-action="edit" data-file="${escapeAttr(fileName)}">编辑</button>
        <button type="button" class="action-menu-item danger" data-action="delete" data-file="${escapeAttr(fileName)}">删除</button>`;
    return `
        <button type="button" class="btn small primary action-run-btn" data-path="${escapeAttr(path)}" data-schedule-enabled="${scheduleEnabled}">运行</button>
        <div class="action-menu">
            <button type="button" class="btn small action-menu-toggle" data-menu-id="${menuId}">更多</button>
            <div id="${menuId}" class="action-menu-dropdown hidden">${menuItems}</div>
        </div>`;
}

function toggleActionMenu(menuId) {
    const menu = document.getElementById(menuId);
    if (!menu) {
        return;
    }
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

async function openDefinitionModal(fileName, content, schedule = null, displayName = null) {
    editingDefinition = fileName || null;
    const title = fileName
        ? `编辑任务: ${displayName || fileName}`
        : '新建任务';
    document.getElementById('modal-title').textContent = title;
    document.getElementById('definition-name').value = displayName || fileName || '';
    document.getElementById('name-field').style.display = 'flex';
    document.getElementById('definition-content').value = content || DEFAULT_TASK_TEMPLATE;
    applyScheduleFields(schedule, !fileName);
    document.getElementById('modal').classList.remove('hidden');
    ensureGuideLoaded();
    await mountYamlEditor(content || DEFAULT_TASK_TEMPLATE);
}

async function mountYamlEditor(content) {
    const host = document.getElementById('definition-editor');
    await createYamlEditor(host, content, false);
}

function toggleGuidePanel() {
    const panel = document.getElementById('guide-panel');
    const button = document.getElementById('guide-toggle');
    const hidden = panel.classList.toggle('hidden');
    button.setAttribute('aria-expanded', String(!hidden));
    button.textContent = hidden ? '显示指南' : '配置指南';
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

function applyScheduleFields(schedule, isNew) {
    const sched = schedule || { enabled: false, cron: '' };
    document.getElementById('schedule-fields').classList.remove('hidden');
    document.getElementById('definition-schedule-enabled').checked = !!sched.enabled;
    document.getElementById('definition-schedule-cron').value = sched.cron || '';
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
}

async function editDefinition(fileName) {
    try {
        const item = await api(`/task-configs/${encodeURIComponent(fileName)}`);
        openDefinitionModal(fileName, item.content, item.schedule, item.name);
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
        payload.fileName = editingDefinition;
    }
    const schedule = readScheduleFromModal();
    if (schedule.enabled && !schedule.cron) {
        showToast('启用定时调度时请填写 Cron 表达式');
        return;
    }
    payload.schedule = schedule;
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

async function viewDefinitionLogs(name, path) {
    try {
        await openLogListModal(name, path);
    } catch (err) {
        showToast('加载失败: ' + err.message);
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

async function stopRun(runId) {
    if (!runId) {
        showToast('当前无运行中的任务');
        return;
    }
    if (!confirm(`确定停止任务 ${runId}？`)) return;
    try {
        const current = await api(`/task-runs/${encodeURIComponent(runId)}`);
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

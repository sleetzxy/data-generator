/** 数据预览弹窗视图。 */

import { api } from '../core/api.js';
import { escapeHtml } from '../core/ui.js';
import { initOverlayScrollbars } from '../lib/scrollbar.js';

const PREVIEW_FETCH_LIMIT = 10;

let previewContext = {
    displayName: null,
    path: null,
    tables: [],
    activeTab: 0
};

/** 绑定弹窗关闭与预览页签委托事件 */
export function initPreview() {
    document.getElementById('preview-close').addEventListener('click', closePreviewModal);
    document.querySelector('#preview-modal .modal-backdrop').addEventListener('click', closePreviewModal);

    document.getElementById('preview-content').addEventListener('click', (event) => {
        const tab = event.target.closest('.preview-tab');
        if (tab) {
            switchPreviewTab(parseInt(tab.dataset.tabIndex, 10));
        }
    });
}

/** 打开预览弹窗并立即拉取预览数据 */
export async function previewDefinition(displayName, path) {
    openPreviewModal(displayName, path);
    await runPreview();
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
        initOverlayScrollbars(panel);
        return;
    }

    let html = '<div class="preview-tabs">';
    html += '<div class="preview-tab-bar" role="tablist">';
    tables.forEach((table, index) => {
        const active = index === activeTab ? ' active' : '';
        html += `<button type="button" class="preview-tab${active}" role="tab" aria-selected="${index === activeTab}"
            data-tab-index="${index}">${escapeHtml(previewTabLabel(table))}</button>`;
    });
    html += '</div>';
    html += '<div class="preview-tab-panels">';
    tables.forEach((table, index) => {
        const hidden = index === activeTab ? '' : ' hidden';
        html += `<section class="preview-tab-panel${hidden}" role="tabpanel">${renderPreviewTableBody(table)}</section>`;
    });
    html += '</div></div>';
    panel.innerHTML = html;
    initOverlayScrollbars(panel);
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

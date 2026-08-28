/** 通用 UI 工具：DOM 转义、格式化、状态徽章、Toast、滚动辅助。 */

/** HTML 文本转义，防止注入 */
export function escapeHtml(text) {
    if (text == null) return '';
    return String(text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

/** 属性值转义（在 escapeHtml 基础上额外处理单引号） */
export function escapeAttr(text) {
    return escapeHtml(text).replace(/'/g, '&#39;');
}

/** CSS 选择器转义（供 querySelector 中的动态值使用） */
export function cssEscape(value) {
    if (window.CSS && typeof window.CSS.escape === 'function') {
        return window.CSS.escape(value);
    }
    return String(value).replace(/\\/g, '\\\\').replace(/"/g, '\\"');
}

/** ISO 时间格式化（zh-CN 本地格式） */
export function formatTime(iso) {
    if (!iso) return '-';
    try {
        return new Date(iso).toLocaleString('zh-CN');
    } catch (_) {
        return iso;
    }
}

/** 运行状态徽章 */
export function statusBadge(status) {
    if (!status) {
        return '<span class="badge status-none">未运行</span>';
    }
    return `<span class="badge status-${status}">${status}</span>`;
}

/** 渲染日志行为 HTML 行（每行带级别样式） */
export function renderLogLines(logs) {
    if (!logs.length) {
        return '暂无日志';
    }
    return logs.map(entry =>
        `<span class="log-line-${entry.level}">[${entry.timestamp}] ${entry.level} ${escapeHtml(entry.message)}</span>`
    ).join('\n');
}

/** 全局 Toast 提示 */
export function showToast(message) {
    const toast = document.getElementById('toast');
    toast.textContent = message;
    toast.classList.remove('hidden');
    setTimeout(() => toast.classList.add('hidden'), 3000);
}

/** 判断元素是否已滚动到底部（阈值内视为到底） */
export function isScrollAtBottom(element, threshold = 48) {
    if (!element) {
        return false;
    }
    return element.scrollHeight - element.scrollTop - element.clientHeight <= threshold;
}

/** 恢复滚动状态：到底时贴底，否则还原 scrollTop */
export function applyScrollState(element, state) {
    if (!element || !state) {
        return;
    }
    if (state.atBottom) {
        element.scrollTop = element.scrollHeight;
    } else {
        element.scrollTop = state.scrollTop;
    }
}

/** 滚动条悬浮显隐：滚动时显示，停止滚动 700ms 后淡出。 */

const HIDE_DELAY_MS = 700;

function bindOverlayScrollbar(el) {
    if (el.dataset.overlayScrollbarBound === 'true') {
        return;
    }
    el.dataset.overlayScrollbarBound = 'true';
    let timer;
    el.addEventListener('scroll', () => {
        el.classList.add('is-scrolling');
        clearTimeout(timer);
        timer = setTimeout(() => el.classList.remove('is-scrolling'), HIDE_DELAY_MS);
    }, { passive: true });
}

/** 为 root 下所有 .scrollbar-overlay 元素绑定悬浮显隐；动态渲染的内容需重新调用 */
export function initOverlayScrollbars(root) {
    (root || document).querySelectorAll('.scrollbar-overlay').forEach(bindOverlayScrollbar);
}

(function () {
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

    function initOverlayScrollbars(root) {
        (root || document).querySelectorAll('.scrollbar-overlay').forEach(bindOverlayScrollbar);
    }

    initOverlayScrollbars();
    window.initOverlayScrollbars = initOverlayScrollbars;
})();

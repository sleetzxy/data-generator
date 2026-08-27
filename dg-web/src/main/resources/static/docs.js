const DOCS_PATH = '/docs/config-guide.md';

async function loadGuide() {
    const body = document.getElementById('docs-body');
    const toc = document.getElementById('docs-toc');

    try {
        const response = await fetch(DOCS_PATH, { credentials: 'same-origin' });
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
        const markdown = await response.text();
        body.innerHTML = renderMarkdown(markdown);
        buildToc(body, toc);
        bindTocScroll();
        const sectionId = location.hash.replace('#', '');
        if (sectionId && document.getElementById(sectionId)) {
            scrollToHash(location.hash);
        }
    } catch (err) {
        body.innerHTML = `<div class="docs-error">加载配置指南失败：${escapeHtml(err.message)}</div>`;
        toc.innerHTML = '';
    }
}

function buildToc(container, tocNav) {
    buildMarkdownToc(container, tocNav);
}

function bindTocScroll() {
    const scrollContainer = document.querySelector('#view-docs .docs-content')
        || document.querySelector('.docs-content');
    document.querySelectorAll('.docs-toc .toc-link').forEach(link => {
        link.addEventListener('click', event => {
            event.preventDefault();
            const targetId = link.getAttribute('href').slice(1);
            const target = document.getElementById(targetId);
            if (!target || !scrollContainer) {
                return;
            }
            const offset = target.getBoundingClientRect().top
                - scrollContainer.getBoundingClientRect().top
                + scrollContainer.scrollTop
                - 12;
            scrollContainer.scrollTo({ top: offset, behavior: 'smooth' });
        });
    });
}

function scrollToHash(hash) {
    const target = document.getElementById(hash.slice(1));
    const scrollContainer = document.querySelector('#view-docs .docs-content')
        || document.querySelector('.docs-content');
    if (!target || !scrollContainer) {
        return;
    }
    const offset = target.getBoundingClientRect().top
        - scrollContainer.getBoundingClientRect().top
        + scrollContainer.scrollTop
        - 12;
    scrollContainer.scrollTo({ top: offset, behavior: 'auto' });
}

window.loadDocsGuide = loadGuide;

/** 配置指南视图：加载 Markdown 指南、构建目录并绑定滚动定位。 */

import { escapeHtml } from '../core/ui.js';
import { buildMarkdownToc, renderMarkdown } from '../lib/markdown.js';

const DOCS_PATH = '/docs/config-guide.md';

/** 加载并渲染配置指南（幂等，由应用入口在首次进入视图时调用） */
export async function loadGuide() {
    const body = document.getElementById('docs-body');
    const toc = document.getElementById('docs-toc');

    try {
        const response = await fetch(DOCS_PATH, { credentials: 'same-origin' });
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
        const markdown = await response.text();
        body.innerHTML = renderMarkdown(markdown);
        buildMarkdownToc(body, toc);
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

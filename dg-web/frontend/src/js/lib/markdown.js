/** 轻量 Markdown 渲染，供配置指南与编辑器参考面板复用。 */

import { escapeHtml } from '../core/ui.js';

function inlineFormat(text) {
    let result = escapeHtml(text);
    result = result.replace(/`([^`]+)`/g, '<code>$1</code>');
    result = result.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    result = result.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2">$1</a>');
    return result;
}

function slugify(text) {
    return text.trim()
        .toLowerCase()
        .replace(/[^\w一-鿿\s-]/g, '')
        .replace(/\s+/g, '-');
}

export function renderMarkdown(text) {
    const lines = text.replace(/\r\n/g, '\n').split('\n');
    const html = [];
    let inCode = false;
    let codeLang = '';
    let codeLines = [];
    let inTable = false;
    let tableRows = [];

    function flushTable() {
        if (!inTable || !tableRows.length) {
            inTable = false;
            tableRows = [];
            return;
        }
        html.push('<div class="table-wrap"><table>');
        tableRows.forEach((row, index) => {
            const tag = index === 0 ? 'th' : 'td';
            const cells = row.split('|').slice(1, -1).map(cell => cell.trim());
            if (index === 1 && cells.every(cell => /^:?-+:?$/.test(cell))) {
                return;
            }
            html.push('<tr>' + cells.map(cell => `<${tag}>${inlineFormat(cell)}</${tag}>`).join('') + '</tr>');
        });
        html.push('</table></div>');
        inTable = false;
        tableRows = [];
    }

    function flushCode() {
        if (!inCode) {
            return;
        }
        html.push(`<pre><code class="language-${escapeHtml(codeLang)}">${escapeHtml(codeLines.join('\n'))}</code></pre>`);
        inCode = false;
        codeLang = '';
        codeLines = [];
    }

    for (const line of lines) {
        if (line.startsWith('```')) {
            if (inCode) {
                flushCode();
            } else {
                flushTable();
                inCode = true;
                codeLang = line.slice(3).trim();
            }
            continue;
        }

        if (inCode) {
            codeLines.push(line);
            continue;
        }

        if (line.trim().startsWith('|')) {
            flushCode();
            inTable = true;
            tableRows.push(line);
            continue;
        }
        flushTable();

        const heading = line.match(/^(#{1,6})\s+(.+)$/);
        if (heading) {
            flushCode();
            const level = heading[1].length;
            const title = heading[2];
            const id = slugify(title);
            html.push(`<h${level} id="${id}">${inlineFormat(title)}</h${level}>`);
            continue;
        }

        if (line.trim() === '') {
            continue;
        }

        if (line.match(/^[-*]\s+/)) {
            html.push(`<li>${inlineFormat(line.replace(/^[-*]\s+/, ''))}</li>`);
            continue;
        }

        html.push(`<p>${inlineFormat(line)}</p>`);
    }

    flushCode();
    flushTable();
    return html.join('\n');
}

/** 基于容器内 h2/h3 标题构建目录导航 */
export function buildMarkdownToc(container, tocNav) {
    const headings = container.querySelectorAll('h2, h3');
    if (!headings.length) {
        tocNav.innerHTML = '';
        return;
    }
    tocNav.innerHTML = Array.from(headings).map(heading => {
        const level = heading.tagName === 'H3' ? ' toc-h3' : '';
        const id = heading.id || slugify(heading.textContent);
        if (!heading.id) {
            heading.id = id;
        }
        return `<a class="toc-link${level}" href="#${id}">${escapeHtml(heading.textContent)}</a>`;
    }).join('');
}

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
        if (location.hash) {
            scrollToHash(location.hash);
        }
    } catch (err) {
        body.innerHTML = `<div class="docs-error">加载配置指南失败：${escapeHtml(err.message)}</div>`;
        toc.innerHTML = '';
    }
}

function renderMarkdown(text) {
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

        if (line.includes('|') && line.trim().startsWith('|')) {
            flushCode();
            inTable = true;
            tableRows.push(line.trim());
            continue;
        }

        flushTable();

        if (line.startsWith('### ')) {
            html.push(`<h3 id="${slugify(line.slice(4))}">${inlineFormat(line.slice(4))}</h3>`);
        } else if (line.startsWith('## ')) {
            html.push(`<h2 id="${slugify(line.slice(3))}">${inlineFormat(line.slice(3))}</h2>`);
        } else if (line.startsWith('# ')) {
            html.push(`<h1 id="${slugify(line.slice(2))}">${inlineFormat(line.slice(2))}</h1>`);
        } else if (line.startsWith('- ')) {
            html.push(`<ul><li>${inlineFormat(line.slice(2))}</li></ul>`);
        } else if (/^\d+\.\s/.test(line)) {
            html.push(`<ol start="${line.match(/^(\d+)/)[1]}"><li>${inlineFormat(line.replace(/^\d+\.\s/, ''))}</li></ol>`);
        } else if (line.trim() === '---') {
            html.push('<hr>');
        } else if (line.trim() === '') {
            /* skip */
        } else {
            html.push(`<p>${inlineFormat(line)}</p>`);
        }
    }

    flushCode();
    flushTable();
    return mergeAdjacentLists(html.join('\n'));
}

function mergeAdjacentLists(html) {
    return html
        .replace(/<\/ul>\s*<ul>/g, '')
        .replace(/<\/ol>\s*<ol start="(\d+)">/g, '');
}

function inlineFormat(text) {
    return escapeHtml(text)
        .replace(/`([^`]+)`/g, '<code>$1</code>')
        .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
        .replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2">$1</a>');
}

function slugify(text) {
    return text
        .trim()
        .toLowerCase()
        .replace(/[^\w\u4e00-\u9fff]+/g, '-')
        .replace(/^-+|-+$/g, '');
}

function buildToc(body, toc) {
    const headings = body.querySelectorAll('h2, h3');
    if (!headings.length) {
        toc.innerHTML = '<p class="docs-toc-empty">暂无目录</p>';
        return;
    }

    const items = [];
    headings.forEach(heading => {
        if (!heading.id) {
            heading.id = slugify(heading.textContent);
        }
        const level = heading.tagName === 'H2' ? 2 : 3;
        items.push(`<a class="toc-h${level}" href="#${heading.id}">${escapeHtml(heading.textContent)}</a>`);
    });
    toc.innerHTML = items.join('');

    toc.querySelectorAll('a').forEach(link => {
        link.addEventListener('click', event => {
            event.preventDefault();
            const id = link.getAttribute('href');
            history.replaceState(null, '', id);
            scrollToHash(id);
        });
    });
}

function scrollToHash(hash) {
    const target = document.querySelector(hash);
    if (target) {
        target.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
}

function bindTocScroll() {
    const links = [...document.querySelectorAll('#docs-toc a')];
    const headings = links
        .map(link => document.querySelector(link.getAttribute('href')))
        .filter(Boolean);

    if (!headings.length) {
        return;
    }

    const observer = new IntersectionObserver(entries => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                links.forEach(link => link.classList.remove('active'));
                const active = links.find(link => link.getAttribute('href') === `#${entry.target.id}`);
                if (active) {
                    active.classList.add('active');
                }
            }
        });
    }, { rootMargin: '-20% 0px -70% 0px', threshold: 0 });

    headings.forEach(heading => observer.observe(heading));
}

function escapeHtml(text) {
    return String(text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

loadGuide();

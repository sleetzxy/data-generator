const AGENT_API = '/api/v1/agent';

let aiEnabled = false;
let aiChatId = null;
let aiSending = false;
let aiAssistantBubble = null;
let aiDeepThink = false;
let aiThinkBlock = null;   // 思考过程 DOM 块

/** 欢迎说明 */
const AGENT_WELCOME_TEXT = `我是 Data Generator 的 AI 配置顾问，基于 ReAct（推理-行动）范式工作。

我可以帮你：
· 创建新的数据生成 Job 配置（表结构、生成策略、Writer、约束、Seed）
· 编辑已有配置
· 查询和删除配置
· 校验 YAML 语法

我会自动规划复杂任务的执行步骤，并逐步完成。直接描述你的造数需求即可开始。`;

const FAB_POSITION_KEY = 'dg-ai-fab-position';
const FAB_DRAG_THRESHOLD_PX = 4;

document.addEventListener('DOMContentLoaded', initAgentUi);

async function initAgentUi() {
    const fab = document.getElementById('ai-fab');
    const drawer = document.getElementById('ai-drawer');
    if (!fab || !drawer) {
        return;
    }

    aiEnabled = await probeAgentApi();
    if (!aiEnabled) {
        fab.classList.add('hidden');
        return;
    }

    fab.classList.remove('hidden');

    initFabDrag(fab);
    fab.addEventListener('click', handleFabClick);
    document.getElementById('ai-drawer-close').addEventListener('click', closeDrawer);
    document.getElementById('ai-chat-form').addEventListener('submit', handleSend);
    document.getElementById('ai-end-session').addEventListener('click', handleEndSession);

    const thinkToggle = document.getElementById('ai-think-toggle');
    if (thinkToggle) {
        thinkToggle.addEventListener('change', function () {
            aiDeepThink = this.checked;
        });
    }

    const input = document.getElementById('ai-input');
    input.addEventListener('input', handleInputChange);
    input.addEventListener('keydown', handleInputKeydown);

    updateNewChatButtonState();
}

async function probeAgentApi() {
    try {
        const response = await fetch(`${AGENT_API}/chat/open`, { method: 'POST', credentials: 'same-origin' });
        if (response.status === 401) {
            window.location.href = '/login.html';
            return false;
        }
        return response.ok;
    } catch (_) {
        return false;
    }
}

function handleFabClick() {
    const fab = document.getElementById('ai-fab');
    if (fab && fab.dataset.suppressClick === '1') {
        fab.dataset.suppressClick = '0';
        return;
    }
    openDrawer();
}

function initFabDrag(fab) {
    restoreFabPosition(fab);

    let activePointerId = null;
    let startX = 0;
    let startY = 0;
    let originLeft = 0;
    let originTop = 0;
    let didDrag = false;

    fab.addEventListener('pointerdown', event => {
        if (event.button !== 0) {
            return;
        }
        activePointerId = event.pointerId;
        didDrag = false;
        fab.setPointerCapture(activePointerId);
        fab.classList.add('ai-fab-dragging');

        const rect = fab.getBoundingClientRect();
        originLeft = rect.left;
        originTop = rect.top;
        startX = event.clientX;
        startY = event.clientY;

        fab.style.left = `${originLeft}px`;
        fab.style.top = `${originTop}px`;
        fab.style.right = 'auto';
        fab.style.bottom = 'auto';
    });

    fab.addEventListener('pointermove', event => {
        if (activePointerId === null || event.pointerId !== activePointerId) {
            return;
        }

        const dx = event.clientX - startX;
        const dy = event.clientY - startY;
        if (!didDrag && (Math.abs(dx) > FAB_DRAG_THRESHOLD_PX || Math.abs(dy) > FAB_DRAG_THRESHOLD_PX)) {
            didDrag = true;
        }
        if (!didDrag) {
            return;
        }

        const rect = fab.getBoundingClientRect();
        const margin = 8;
        let left = originLeft + dx;
        let top = originTop + dy;
        left = Math.max(margin, Math.min(left, window.innerWidth - rect.width - margin));
        top = Math.max(margin, Math.min(top, window.innerHeight - rect.height - margin));

        fab.style.left = `${left}px`;
        fab.style.top = `${top}px`;
    });

    const endDrag = event => {
        if (activePointerId === null || event.pointerId !== activePointerId) {
            return;
        }
        fab.releasePointerCapture(activePointerId);
        fab.classList.remove('ai-fab-dragging');
        if (didDrag) {
            saveFabPosition(fab);
            fab.dataset.suppressClick = '1';
        }
        activePointerId = null;
    };

    fab.addEventListener('pointerup', endDrag);
    fab.addEventListener('pointercancel', endDrag);

    window.addEventListener('resize', () => clampFabInViewport(fab));
}

function saveFabPosition(fab) {
    const left = parseFloat(fab.style.left);
    const top = parseFloat(fab.style.top);
    if (Number.isFinite(left) && Number.isFinite(top)) {
        try {
            localStorage.setItem(FAB_POSITION_KEY, JSON.stringify({ left, top }));
        } catch (_) { /* 隐私模式等 */ }
    }
}

function restoreFabPosition(fab) {
    try {
        const raw = localStorage.getItem(FAB_POSITION_KEY);
        if (!raw) {
            return;
        }
        const pos = JSON.parse(raw);
        if (!Number.isFinite(pos.left) || !Number.isFinite(pos.top)) {
            return;
        }
        fab.style.left = `${pos.left}px`;
        fab.style.top = `${pos.top}px`;
        fab.style.right = 'auto';
        fab.style.bottom = 'auto';
        clampFabInViewport(fab);
    } catch (_) { /* ignore */ }
}

function clampFabInViewport(fab) {
    if (!fab.style.left || !fab.style.top) {
        return;
    }
    const rect = fab.getBoundingClientRect();
    const margin = 8;
    let left = rect.left;
    let top = rect.top;
    left = Math.max(margin, Math.min(left, window.innerWidth - rect.width - margin));
    top = Math.max(margin, Math.min(top, window.innerHeight - rect.height - margin));
    fab.style.left = `${left}px`;
    fab.style.top = `${top}px`;
}

async function openDrawer() {
    document.getElementById('ai-drawer').classList.remove('hidden');
    document.getElementById('ai-fab').classList.add('hidden');
    updateNewChatButtonState();
    showWelcomeIfEmpty();
}

function closeDrawer() {
    document.getElementById('ai-drawer').classList.add('hidden');
    const fab = document.getElementById('ai-fab');
    if (fab && aiEnabled) {
        fab.classList.remove('hidden');
    }
}

async function ensureChat() {
    const response = await agentFetch('/chat/open', { method: 'POST' });
    aiChatId = (response.data && response.data.chatId) || response.chatId;
    updateNewChatButtonState();
}

function handleInputKeydown(event) {
    if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        document.getElementById('ai-chat-form').requestSubmit();
    }
}

function updateSendButtonState() {
    const input = document.getElementById('ai-input');
    const sendBtn = document.getElementById('ai-send-btn');
    const hasText = input.value.trim().length > 0;
    sendBtn.disabled = aiSending || !hasText;
    sendBtn.classList.toggle('active', hasText && !aiSending);
    sendBtn.classList.toggle('is-loading', aiSending);
    sendBtn.setAttribute('aria-label', aiSending ? '正在生成' : '发送');
}

/** 输入变化时：更新按钮状态 + 自动调整输入框高度 */
function handleInputChange() {
    updateSendButtonState();
    autoResizeTextarea();
}

/** 根据内容自动调整 textarea 高度 */
function autoResizeTextarea() {
    const input = document.getElementById('ai-input');
    if (!input) return;
    // 先重置高度以获取正确的 scrollHeight
    input.style.height = 'auto';
    const maxHeight = 128; // 8rem ≈ 128px
    const newHeight = Math.min(input.scrollHeight, maxHeight);
    input.style.height = `${newHeight}px`;
    // 内容超出最大高度时显示滚动条
    input.style.overflowY = input.scrollHeight > maxHeight ? 'auto' : 'hidden';
}

async function handleSend(event) {
    event.preventDefault();
    if (aiSending) {
        return;
    }

    const input = document.getElementById('ai-input');
    const content = input.value.trim();
    if (!content) {
        return;
    }

    appendMessage('user', content);
    input.value = '';
    input.style.height = 'auto';
    input.style.overflowY = 'hidden';
    setSending(true);

    try {
        if (!aiChatId) {
            await ensureChat();
        }
    } catch (err) {
        showAgentToast('创建会话失败: ' + err.message);
        setSending(false);
        return;
    }

    aiAssistantBubble = appendMessage('assistant', '');
    showTypingIndicator(aiAssistantBubble);

    try {
        await sendAgentMessage(aiChatId, content, handleSseEvent);
    } catch (err) {
        appendToAssistantBubble('\n[错误] ' + err.message);
        showAgentToast('发送失败: ' + err.message);
        setSending(false);
    }
}

async function handleEndSession() {
    resetSession();
    showAgentToast('已开始新对话');
}

function resetSession() {
    aiChatId = null;
    aiAssistantBubble = null;
    aiThinkBlock = null;
    document.getElementById('ai-messages').innerHTML = '';
    document.getElementById('ai-input').value = '';
    const input = document.getElementById('ai-input');
    input.style.height = 'auto';
    input.style.overflowY = 'hidden';
    setSending(false);
    updateNewChatButtonState();
    showWelcomeIfEmpty();
}

function showWelcomeIfEmpty() {
    const messages = document.getElementById('ai-messages');
    if (!messages || messages.childElementCount > 0) {
        return;
    }
    const bubble = document.createElement('div');
    bubble.className = 'ai-message ai-message-welcome';
    bubble.setAttribute('role', 'note');
    bubble.textContent = AGENT_WELCOME_TEXT;
    messages.appendChild(bubble);
}

function updateNewChatButtonState() {
    const btn = document.getElementById('ai-end-session');
    if (!btn) {
        return;
    }
    btn.disabled = !aiChatId;
    btn.classList.toggle('disabled', !aiChatId);
}

function setSending(sending) {
    aiSending = sending;
    const input = document.getElementById('ai-input');
    const form = document.getElementById('ai-chat-form');
    const messages = document.getElementById('ai-messages');
    input.disabled = sending;
    form.classList.toggle('is-sending', sending);
    messages.setAttribute('aria-busy', sending ? 'true' : 'false');
    updateSendButtonState();
}

function appendMessage(role, text) {
    const messages = document.getElementById('ai-messages');
    const bubble = document.createElement('div');
    bubble.className = `ai-message ai-message-${role}`;
    if (role === 'assistant') {
        const content = document.createElement('span');
        content.className = 'ai-message-content';
        content.textContent = text;
        bubble.appendChild(content);
    } else {
        bubble.textContent = text;
    }
    messages.appendChild(bubble);
    messages.scrollTop = messages.scrollHeight;
    return bubble;
}

function showTypingIndicator(bubble) {
    if (!bubble || bubble.querySelector('.ai-typing-indicator')) {
        return;
    }
    const indicator = document.createElement('span');
    indicator.className = 'ai-typing-indicator';
    indicator.setAttribute('aria-label', '正在思考');
    indicator.innerHTML = '<span></span><span></span><span></span>';
    bubble.classList.add('is-waiting');
    bubble.appendChild(indicator);
}

function hideTypingIndicator(bubble) {
    if (!bubble) {
        return;
    }
    bubble.classList.remove('is-waiting');
    const indicator = bubble.querySelector('.ai-typing-indicator');
    if (indicator) {
        indicator.remove();
    }
}


function stripInternalAgentHints(text) {
    if (!text) {
        return text;
    }
    return text
        .replace(/<!--\s*dg-(?:intent|ref):[^>]*-->/g, '')
        .replace(/\[Job YAML 草稿已存入会话[\s\S]*?\]/g, '')
        .replace(/\[意图快照已存入会话[\s\S]*?\]/g, '')
        .replace(/\[参考 Job「[\s\S]*?\]/g, '')
        .replace(/\n\[提示\][^\n]*/g, '');
}

/** 创建思考过程块 — DeepSeek 风格 */
function createThinkBlock() {
    const block = document.createElement('div');
    block.className = 'ai-think-block is-expanded is-thinking';

    const header = document.createElement('div');
    header.className = 'ai-think-header';

    // 大脑图标（小号）
    const icon = document.createElement('span');
    icon.className = 'ai-think-header-icon';
    icon.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path d="M12 2a4 4 0 0 1 4 4c0 1.1-.44 2.1-1.17 2.83A4 4 0 0 1 12 10a4 4 0 0 1-2.83-1.17A4 4 0 0 1 8 6a4 4 0 0 1 4-4z"/>
        <path d="M12 10v4"/><path d="M8 14a4 4 0 0 0-4 4 2 2 0 0 0 2 2h12a2 2 0 0 0 2-2 4 4 0 0 0-4-4"/>
        <circle cx="10" cy="5" r="0.5" fill="currentColor" stroke="none"/>
        <circle cx="14" cy="5" r="0.5" fill="currentColor" stroke="none"/>
    </svg>`;

    const text = document.createElement('span');
    text.className = 'ai-think-header-text';
    text.textContent = '思考中';

    const dots = document.createElement('span');
    dots.className = 'ai-think-dots';
    dots.innerHTML = '<span></span><span></span><span></span>';

    const arrow = document.createElement('span');
    arrow.className = 'ai-think-arrow';
    arrow.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
        <polyline points="6 9 12 15 18 9"/>
    </svg>`;

    header.appendChild(icon);
    header.appendChild(text);
    header.appendChild(dots);
    header.appendChild(arrow);

    header.addEventListener('click', () => {
        block.classList.toggle('is-expanded');
    });

    const content = document.createElement('div');
    content.className = 'ai-think-content';

    block.appendChild(header);
    block.appendChild(content);
    return block;
}

/** 思考完成，更新块状态 — 默认折叠 */
function finalizeThinkBlock(block) {
    if (!block) return;
    block.classList.remove('is-thinking', 'is-expanded');
    block.classList.add('is-done');
    const textEl = block.querySelector('.ai-think-header-text');
    if (textEl) textEl.textContent = '已深度思考';
    const dots = block.querySelector('.ai-think-dots');
    if (dots) dots.remove();
}

function handleAgentJobSaved() {
    if (typeof loadDefinitions === 'function') {
        loadDefinitions({ fullRender: true });
    }
}

function appendToAssistantBubble(text) {
    const cleaned = stripInternalAgentHints(text);
    if (!cleaned) {
        return;
    }
    if (!aiAssistantBubble) {
        aiAssistantBubble = appendMessage('assistant', '');
    }
    hideTypingIndicator(aiAssistantBubble);
    let content = aiAssistantBubble.querySelector('.ai-message-content');
    if (!content) {
        content = document.createElement('span');
        content.className = 'ai-message-content';
        aiAssistantBubble.prepend(content);
    }
    content.textContent += cleaned;
    // 延迟到浏览器 reflow 后再滚动，确保 scrollHeight 已更新
    requestAnimationFrame(() => {
        const messages = document.getElementById('ai-messages');
        if (messages) {
            messages.scrollTop = messages.scrollHeight;
        }
    });
}

function handleSseEvent(eventName, data) {
    switch (eventName) {
        case 'text':
        case 'token': {
            try {
                const parsed = JSON.parse(data);
                if (parsed.delta) {
                    appendToAssistantBubble(parsed.delta);
                }
            } catch (_) {
                appendToAssistantBubble(data);
            }
            break;
        }
        case 'tool': {
            let label = data;
            try {
                const parsed = JSON.parse(data);
                label = parsed.name ? `[调用 ${parsed.name}]\n` : data;
                if (parsed.name === 'saveConfig') {
                    handleAgentJobSaved();
                }
            } catch (_) { /* ignore */ }
            appendToAssistantBubble(label);
            break;
        }
        case 'job_saved':
            handleAgentJobSaved();
            break;
        case 'validation_error': {
            try {
                const parsed = JSON.parse(data);
                const errors = Array.isArray(parsed.errors) ? parsed.errors : [];
                const message = errors.length > 0 ? errors.join('\n') : '未知校验错误';
                appendToAssistantBubble('\n[校验失败] ' + message);
            } catch (_) { /* ignore */ }
            break;
        }
        case 'thinking': {
            try {
                const parsed = JSON.parse(data);
                if (parsed.delta) {
                    if (!aiThinkBlock) {
                        aiThinkBlock = createThinkBlock();
                        if (aiAssistantBubble) {
                            // 移除 is-waiting 让气泡恢复 block 布局，
                            // 避免 flex row 导致思考块与 typing 指示器水平排列重叠
                            aiAssistantBubble.classList.remove('is-waiting');
                            aiAssistantBubble.insertBefore(aiThinkBlock, aiAssistantBubble.firstChild);
                        }
                    }
                    const content = aiThinkBlock.querySelector('.ai-think-content');
                    if (content) {
                        content.textContent += parsed.delta;
                    }
                    // 延迟到浏览器 reflow 后再滚动，确保 scrollHeight 已更新
                    requestAnimationFrame(() => {
                        if (content) {
                            content.scrollTop = content.scrollHeight;
                        }
                        const messages = document.getElementById('ai-messages');
                        if (messages) {
                            messages.scrollTop = messages.scrollHeight;
                        }
                    });
                }
            } catch (_) { /* ignore */ }
            break;
        }
        case 'error': {
            let message = data;
            try {
                const parsed = JSON.parse(data);
                message = parsed.message || data;
            } catch (_) { /* ignore */ }
            appendToAssistantBubble('\n[错误] ' + message);
            showAgentToast(message);
            setSending(false);
            break;
        }
        case 'done':
            hideTypingIndicator(aiAssistantBubble);
            if (aiThinkBlock) {
                finalizeThinkBlock(aiThinkBlock);
                aiThinkBlock = null;
            }
            setSending(false);
            aiAssistantBubble = null;
            break;
        default:
            break;
    }
}

async function sendAgentMessage(chatId, content, onEvent) {
    const headers = {
        'Content-Type': 'application/json',
        'Accept': 'text/event-stream'
    };
    if (typeof getCsrfToken === 'function') {
        const csrfToken = getCsrfToken();
        if (csrfToken) {
            headers['X-XSRF-TOKEN'] = csrfToken;
        }
    }

    const mode = aiDeepThink ? 'verbose' : 'token';
    const response = await fetch(
        `${AGENT_API}/chat/${encodeURIComponent(chatId)}?mode=${mode}`,
        {
            method: 'POST',
            credentials: 'same-origin',
            headers,
            body: JSON.stringify({ content })
        }
    );

    if (response.status === 401) {
        window.location.href = '/login.html';
        return;
    }
    if (!response.ok) {
        let message = response.statusText;
        try {
            const body = await response.json();
            message = body.message || body.error || message;
        } catch (_) { /* ignore */ }
        throw new Error(message);
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    while (true) {
        const { done, value } = await reader.read();
        if (done) {
            break;
        }
        buffer += decoder.decode(value, { stream: true });
        buffer = consumeSseBuffer(buffer, onEvent);
    }
    consumeSseBuffer(buffer + '\n\n', onEvent);
    hideTypingIndicator(aiAssistantBubble);
    setSending(false);
}

function consumeSseBuffer(buffer, onEvent) {
    const blocks = buffer.split('\n\n');
    const remainder = blocks.pop() || '';

    blocks.forEach(block => {
        const lines = block.split('\n');
        let eventName = 'message';
        const dataLines = [];

        lines.forEach(line => {
            if (line.startsWith('event:')) {
                eventName = line.slice(6).trim();
            } else if (line.startsWith('data:')) {
                dataLines.push(line.slice(5).trimStart());
            }
        });

        if (dataLines.length > 0) {
            onEvent(eventName, dataLines.join('\n'));
        }
    });

    return remainder;
}

async function agentFetch(path, options = {}) {
    const method = (options.method || 'GET').toUpperCase();
    const headers = { 'Content-Type': 'application/json', ...options.headers };
    if (method !== 'GET' && method !== 'HEAD' && method !== 'OPTIONS' && typeof getCsrfToken === 'function') {
        const csrfToken = getCsrfToken();
        if (csrfToken) {
            headers['X-XSRF-TOKEN'] = csrfToken;
        }
    }

    const response = await fetch(`${AGENT_API}${path}`, {
        credentials: 'same-origin',
        headers,
        ...options
    });

    if (response.status === 401) {
        window.location.href = '/login.html';
        return null;
    }
    if (!response.ok) {
        let message = response.statusText;
        try {
            const body = await response.json();
            message = body.message || body.error || message;
        } catch (_) { /* ignore */ }
        throw new Error(message);
    }
    if (response.status === 204) {
        return null;
    }
    const data = await response.json();
    return data;
}

function showAgentToast(message) {
    if (typeof showToast === 'function') {
        showToast(message);
    }
}

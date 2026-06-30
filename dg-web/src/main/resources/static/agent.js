const AGENT_API = '/api/v1/agent';

let aiEnabled = false;
let aiSessionId = null;
let aiAgentId = 'job-generator';
let aiSending = false;
let aiAssistantBubble = null;
/** 本轮对话是否已调用保存类 Tool */
let aiDraftSavedInTurn = false;

/** 对话面板欢迎说明（固定文案，描述智能体身份与能力） */
const AGENT_WELCOME_TEXT = `我是 Data Generator 的 Job 配置助手，帮你在对话里编写、校验测试数据任务的 YAML。

我可以帮你：
· 生成 Job YAML（writer、tables、seeds、约束、多写等）
· 读取已有 Job、连接与 Schema 作参考（不会臆造连接名）
· 自动校验草稿；你说「保存到控制台」会写入 Job 定义

直接描述目标表、参考 Job 或造数需求即可开始。`;

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

    await loadAgentDefaults();

    fab.classList.remove('hidden');

    initFabDrag(fab);
    fab.addEventListener('click', handleFabClick);
    document.getElementById('ai-drawer-close').addEventListener('click', closeDrawer);
    document.getElementById('ai-chat-form').addEventListener('submit', handleSend);
    document.getElementById('ai-end-session').addEventListener('click', handleEndSession);

    const input = document.getElementById('ai-input');
    input.addEventListener('input', updateSendButtonState);
    input.addEventListener('keydown', handleInputKeydown);

    updateNewChatButtonState();
}

async function probeAgentApi() {
    try {
        const response = await fetch(`${AGENT_API}/agents`, { credentials: 'same-origin' });
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

async function loadAgentDefaults() {
    try {
        const agents = await agentFetch('/agents');
        if (Array.isArray(agents) && agents.length > 0) {
            aiAgentId = agents[0].id || aiAgentId;
        }
    } catch (_) {
        /* 使用内置默认值 */
    }
}

async function ensureSession() {
    const response = await agentFetch('/sessions', {
        method: 'POST',
        body: JSON.stringify({ agentId: aiAgentId })
    });

    aiSessionId = response.sessionId;
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
    aiDraftSavedInTurn = false;
    setSending(true);

    try {
        if (!aiSessionId) {
            await ensureSession();
        }
    } catch (err) {
        showAgentToast('创建会话失败: ' + err.message);
        setSending(false);
        return;
    }

    aiAssistantBubble = appendMessage('assistant', '');
    showTypingIndicator(aiAssistantBubble);

    try {
        await sendAgentMessage(aiSessionId, content, handleSseEvent);
    } catch (err) {
        appendToAssistantBubble('\n[错误] ' + err.message);
        showAgentToast('发送失败: ' + err.message);
        setSending(false);
    }
}

async function handleEndSession() {
    if (aiSessionId) {
        try {
            await agentFetch(`/sessions/${encodeURIComponent(aiSessionId)}`, { method: 'DELETE' });
        } catch (err) {
            showAgentToast('结束会话失败: ' + err.message);
            return;
        }
    }
    resetSession();
    showAgentToast('已开始新对话');
}

function resetSession() {
    aiSessionId = null;
    aiAssistantBubble = null;
    document.getElementById('ai-messages').innerHTML = '';
    document.getElementById('ai-input').value = '';
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
    btn.disabled = !aiSessionId;
    btn.classList.toggle('disabled', !aiSessionId);
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
        .replace(/<!--\s*dg-(?:draft|ref):[^>]*-->/g, '')
        .replace(/\[Job YAML 草稿已存入会话[\s\S]*?\]/g, '')
        .replace(/\[参考 Job「[\s\S]*?\]/g, '')
        .replace(/\n\[提示\][^\n]*/g, '');
}

function handleAgentJobSaved() {
    aiDraftSavedInTurn = true;
    if (typeof loadDefinitions === 'function') {
        loadDefinitions({ fullRender: true });
    }
}

async function openValidatedDraftModal() {
    if (!aiSessionId) {
        return;
    }
    try {
        const response = await fetch(
            `${AGENT_API}/sessions/${encodeURIComponent(aiSessionId)}/draft`,
            { credentials: 'same-origin' }
        );
        if (!response.ok) {
            return;
        }
        const body = await response.json();
        const yaml = body.draftYaml;
        if (!yaml || typeof yaml !== 'string' || !yaml.trim()) {
            return;
        }
        if (typeof window.openDefinitionModalForAi === 'function') {
            window.openDefinitionModalForAi(yaml);
            appendToAssistantBubble('\n[提示] 草稿已校验通过，已打开 Job 编辑窗口，确认后可保存到控制台。');
        }
    } catch (_) {
        /* ignore */
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
    const messages = document.getElementById('ai-messages');
    messages.scrollTop = messages.scrollHeight;
}

function handleSseEvent(eventName, data) {
    switch (eventName) {
        case 'token': {
            let delta = data;
            try {
                const parsed = JSON.parse(data);
                delta = parsed.delta != null ? parsed.delta : data;
            } catch (_) { /* 纯文本 token */ }
            appendToAssistantBubble(delta);
            break;
        }
        case 'tool': {
            let label = data;
            try {
                const parsed = JSON.parse(data);
                label = parsed.name ? `[调用 ${parsed.name}]\n` : data;
                if (parsed.name === 'saveDraftJobDefinition' || parsed.name === 'createJobDefinition') {
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
            try {
                const parsed = JSON.parse(data);
                if (!aiDraftSavedInTurn && parsed.draftValidated && parsed.hasDraft) {
                    openValidatedDraftModal();
                } else if (parsed.draftIncomplete && parsed.hasDraft) {
                    appendToAssistantBubble('\n[提示] YAML 草稿尚未完成，可继续说明需求或等待自动续写。');
                }
            } catch (_) { /* ignore */ }
            aiDraftSavedInTurn = false;
            setSending(false);
            aiAssistantBubble = null;
            break;
        default:
            break;
    }
}

async function sendAgentMessage(sessionId, content, onEvent) {
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

    const response = await fetch(
        `${AGENT_API}/sessions/${encodeURIComponent(sessionId)}/messages`,
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

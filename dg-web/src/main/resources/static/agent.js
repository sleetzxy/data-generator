const AGENT_API = '/api/v1/agent';

let aiProviderOptions = [];

let aiEnabled = false;
let aiSessionId = null;
let aiSending = false;
let aiAssistantBubble = null;
let aiSkills = [];
let aiSelectedSkillId = null;

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
    await loadProviders();
    await loadSkills();

    initFabDrag(fab);
    fab.addEventListener('click', handleFabClick);
    document.getElementById('ai-drawer-close').addEventListener('click', closeDrawer);
    document.getElementById('ai-chat-form').addEventListener('submit', handleSend);
    document.getElementById('ai-end-session').addEventListener('click', handleEndSession);

    const skillTrigger = document.getElementById('ai-skill-trigger');
    skillTrigger.addEventListener('click', toggleSkillMenu);

    const input = document.getElementById('ai-input');
    input.addEventListener('input', updateSendButtonState);
    input.addEventListener('keydown', handleInputKeydown);

    document.addEventListener('click', handleDocumentClick);
    updateNewChatButtonState();
}

async function probeAgentApi() {
    try {
        const response = await fetch(`${AGENT_API}/skills`, { credentials: 'same-origin' });
        if (response.status === 401) {
            window.location.href = '/login.html';
            return false;
        }
        return response.ok;
    } catch (_) {
        return false;
    }
}

async function loadProviders() {
    const select = document.getElementById('ai-provider-select');
    try {
        aiProviderOptions = await agentFetch('/providers') || [];
        select.innerHTML = '';
        if (aiProviderOptions.length === 0) {
            select.innerHTML = '<option value="">未配置可用模型</option>';
            select.disabled = true;
            return;
        }
        select.disabled = false;
        let defaultIndex = 0;
        aiProviderOptions.forEach((provider, index) => {
            if (provider.defaultProvider) {
                defaultIndex = index;
            }
        });
        aiProviderOptions.forEach((provider, index) => {
            const option = document.createElement('option');
            option.value = provider.id;
            option.textContent = provider.label || provider.id;
            if (index === defaultIndex) {
                option.selected = true;
            }
            select.appendChild(option);
        });
    } catch (err) {
        select.innerHTML = '<option value="">加载失败</option>';
        select.disabled = true;
        showAgentToast('加载模型列表失败: ' + err.message);
    }
}

async function loadSkills() {
    const menu = document.getElementById('ai-skill-menu');
    try {
        aiSkills = await agentFetch('/skills');
        renderSkillMenu();
        if (aiSkills.length > 0) {
            selectSkill(aiSkills[0].id, false);
        } else {
            updateSkillLabel(null);
        }
    } catch (err) {
        menu.innerHTML = '';
        updateSkillLabel(null);
        showAgentToast('加载 Skill 失败: ' + err.message);
    }
}

function renderSkillMenu() {
    const menu = document.getElementById('ai-skill-menu');
    menu.innerHTML = '';
    aiSkills.forEach(skill => {
        const item = document.createElement('button');
        item.type = 'button';
        item.className = 'ai-skill-menu-item';
        item.dataset.skillId = skill.id;
        item.setAttribute('role', 'option');
        item.innerHTML = `
            <span class="ai-skill-menu-name">${escapeHtml(skill.name || skill.id)}</span>
            ${skill.description ? `<span class="ai-skill-menu-desc">${escapeHtml(skill.description)}</span>` : ''}`;
        item.addEventListener('click', () => {
            selectSkill(skill.id, true);
            closeSkillMenu();
        });
        menu.appendChild(item);
    });
    highlightSelectedSkillMenuItem();
}

function selectSkill(skillId, userInitiated) {
    const skill = aiSkills.find(s => s.id === skillId);
    if (!skill) {
        return;
    }
    if (userInitiated && aiSessionId) {
        showAgentToast('进行中的对话无法切换 Skill，请点击「新对话」');
        return;
    }
    aiSelectedSkillId = skillId;
    updateSkillLabel(skill);
    highlightSelectedSkillMenuItem();
}

function updateSkillLabel(skill) {
    const label = document.getElementById('ai-skill-label');
    const trigger = document.getElementById('ai-skill-trigger');
    if (!skill) {
        label.textContent = '—';
        label.title = '';
        trigger.setAttribute('aria-label', '选择 Skill');
        return;
    }
    const name = skill.name || skill.id;
    label.textContent = name;
    label.title = skill.description || name;
    trigger.setAttribute('aria-label', `Skill：${name}，点击切换`);
}

function highlightSelectedSkillMenuItem() {
    document.querySelectorAll('.ai-skill-menu-item').forEach(item => {
        const selected = item.dataset.skillId === aiSelectedSkillId;
        item.classList.toggle('selected', selected);
        item.setAttribute('aria-selected', selected ? 'true' : 'false');
    });
}

function toggleSkillMenu(event) {
    event.stopPropagation();
    if (document.getElementById('ai-skill-trigger').disabled) {
        return;
    }
    const menu = document.getElementById('ai-skill-menu');
    const trigger = document.getElementById('ai-skill-trigger');
    const isHidden = menu.classList.toggle('hidden');
    trigger.setAttribute('aria-expanded', isHidden ? 'false' : 'true');
}

function closeSkillMenu() {
    document.getElementById('ai-skill-menu').classList.add('hidden');
    document.getElementById('ai-skill-trigger').setAttribute('aria-expanded', 'false');
}

function handleDocumentClick(event) {
    const picker = document.querySelector('.ai-skill-picker');
    if (picker && !picker.contains(event.target)) {
        closeSkillMenu();
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
    setSessionControlsLocked(!!aiSessionId);
    updateNewChatButtonState();
}

function closeDrawer() {
    closeSkillMenu();
    document.getElementById('ai-drawer').classList.add('hidden');
    const fab = document.getElementById('ai-fab');
    if (fab && aiEnabled) {
        fab.classList.remove('hidden');
    }
}

async function ensureSession() {
    if (!aiSelectedSkillId) {
        throw new Error('请选择 Skill');
    }
    const provider = document.getElementById('ai-provider-select').value;

    const response = await agentFetch('/sessions', {
        method: 'POST',
        body: JSON.stringify({ skillId: aiSelectedSkillId, provider: provider || undefined })
    });

    aiSessionId = response.sessionId;
    setSessionControlsLocked(true);
    updateNewChatButtonState();
}

function setSessionControlsLocked(locked) {
    document.getElementById('ai-skill-trigger').disabled = locked;
    document.getElementById('ai-provider-select').disabled = locked;
    if (locked) {
        closeSkillMenu();
    }
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
    setSessionControlsLocked(false);
    setSending(false);
    updateNewChatButtonState();
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

function appendToAssistantBubble(text) {
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
    content.textContent += text;
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
            } catch (_) { /* ignore */ }
            appendToAssistantBubble(label);
            break;
        }
        case 'artifact': {
            try {
                const artifact = JSON.parse(data);
                if (artifact.content && typeof window.openDefinitionModalForAi === 'function') {
                    window.openDefinitionModalForAi(artifact.content);
                    showAgentToast('已生成 Job 配置，请在弹窗中核对并保存');
                }
            } catch (err) {
                showAgentToast('解析 artifact 失败: ' + err.message);
            }
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

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

function showAgentToast(message) {
    if (typeof showToast === 'function') {
        showToast(message);
    }
}

const AGENT_API = '/api/v1/agent';

let aiEnabled = false;
let aiChatId = null;
let aiSending = false;
let aiAssistantBubble = null;
/** toolCallId → 工具调用 DOM 块 */
let aiToolBlocks = new Map();
/** 当前思考过程块 */
let aiThinkBlock = null;

/** localStorage 键名 */
const HISTORY_KEY = 'dg-ai-chat-history';
/** 最多保留的历史条数 */
const MAX_HISTORY = 20;

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
    document.getElementById('ai-new-chat').addEventListener('click', handleNewChat);
    document.getElementById('ai-history-btn').addEventListener('click', toggleHistoryPanel);
    document.getElementById('ai-clear-chat').addEventListener('click', handleClearChat);

    const input = document.getElementById('ai-input');
    input.addEventListener('input', handleInputChange);
    input.addEventListener('keydown', handleInputKeydown);

    // 点击抽屉外部关闭历史面板
    document.addEventListener('click', function (e) {
        const panel = document.getElementById('ai-history-panel');
        const btn = document.getElementById('ai-history-btn');
        if (!panel || panel.classList.contains('hidden')) return;
        if (!panel.contains(e.target) && e.target !== btn && !btn.contains(e.target)) {
            panel.classList.add('hidden');
        }
    });
}

async function probeAgentApi() {
    try {
        const response = await agentFetch('/chat/open', { method: 'POST' });
        return response != null;
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
    showWelcomeIfEmpty();
}

function closeDrawer() {
    document.getElementById('ai-drawer').classList.add('hidden');
    document.getElementById('ai-history-panel').classList.add('hidden');
    const fab = document.getElementById('ai-fab');
    if (fab && aiEnabled) {
        fab.classList.remove('hidden');
    }
}

async function ensureChat() {
    const response = await agentFetch('/chat/open', { method: 'POST' });
    aiChatId = (response.data && response.data.chatId) || response.chatId;
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
    input.style.height = 'auto';
    const maxHeight = 128;
    const newHeight = Math.min(input.scrollHeight, maxHeight);
    input.style.height = `${newHeight}px`;
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
            // 保存新会话到历史
            saveCurrentChatToHistory(content);
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

/** ── 新对话 ── */
async function handleNewChat() {
    document.getElementById('ai-history-panel').classList.add('hidden');
    if (aiChatId) {
        // 保存当前会话到历史
        saveCurrentChatToHistory();
    }
    resetSession();
    showAgentToast('已开始新对话');
}

/** ── 清空当前会话 ── */
function handleClearChat() {
    document.getElementById('ai-messages').innerHTML = '';
    aiAssistantBubble = null;
    aiToolBlocks.clear();
    aiThinkBlock = null;
    showWelcomeIfEmpty();
    showAgentToast('会话已清空');
}

/** ── 切换历史面板 ── */
function toggleHistoryPanel() {
    const panel = document.getElementById('ai-history-panel');
    panel.classList.toggle('hidden');
    if (!panel.classList.contains('hidden')) {
        renderHistoryList();
    }
}

/** ── 渲染历史列表 ── */
function renderHistoryList() {
    const list = document.getElementById('ai-history-list');
    const history = loadHistory();
    if (history.length === 0) {
        list.innerHTML = '<div class="ai-history-empty">暂无历史对话</div>';
        return;
    }
    list.innerHTML = history.map((item, idx) => {
        const title = item.title || '未命名对话';
        const time = item.time ? formatTime(item.time) : '';
        return `<div class="ai-history-item" data-index="${idx}">
            <span class="ai-history-item-title">${escapeHtml(title)}</span>
            <span class="ai-history-item-time">${time}</span>
            <button class="ai-history-item-del" data-index="${idx}" title="删除">×</button>
        </div>`;
    }).join('');

    // 点击切换
    list.querySelectorAll('.ai-history-item').forEach(el => {
        el.addEventListener('click', function (e) {
            if (e.target.classList.contains('ai-history-item-del')) return;
            const idx = parseInt(this.dataset.index);
            switchToHistory(idx);
        });
    });
    // 删除按钮
    list.querySelectorAll('.ai-history-item-del').forEach(btn => {
        btn.addEventListener('click', function (e) {
            e.stopPropagation();
            const idx = parseInt(this.dataset.index);
            deleteHistoryItem(idx);
        });
    });
}

function switchToHistory(idx) {
    const history = loadHistory();
    const item = history[idx];
    if (!item) return;
    aiChatId = item.chatId;
    const messagesEl = document.getElementById('ai-messages');
    messagesEl.innerHTML = '';
    aiAssistantBubble = null;
    aiToolBlocks.clear();
    aiThinkBlock = null;

    // 恢复历史消息
    if (item.messages && item.messages.length > 0) {
        item.messages.forEach(msg => {
            appendMessage(msg.role, msg.content);
        });
    } else {
        showWelcomeIfEmpty();
    }

    document.getElementById('ai-history-panel').classList.add('hidden');
    showAgentToast('已切换到: ' + (item.title || '历史对话'));
}

function saveCurrentChatToHistory(firstMessage) {
    if (!aiChatId) return;
    const messagesEl = document.getElementById('ai-messages');
    let title = firstMessage || '';
    if (!title) {
        // 取第一条用户消息作为标题
        const firstUserMsg = messagesEl.querySelector('.ai-message-user');
        if (firstUserMsg) {
            title = firstUserMsg.textContent || '';
        }
    }
    title = title.substring(0, 40);

    // 提取消息数据用于恢复
    const msgData = [];
    messagesEl.querySelectorAll('.ai-message').forEach(el => {
        if (el.classList.contains('ai-message-user')) {
            msgData.push({ role: 'user', content: el.textContent || '' });
        } else if (el.classList.contains('ai-message-assistant')) {
            const contentEl = el.querySelector('.ai-message-content');
            const text = contentEl ? contentEl.textContent : (el.textContent || '');
            msgData.push({ role: 'assistant', content: text });
        }
    });

    const history = loadHistory();
    // 移除同一 chatId 的旧记录
    const filtered = history.filter(h => h.chatId !== aiChatId);
    filtered.unshift({
        chatId: aiChatId,
        title: title,
        time: Date.now(),
        messages: msgData
    });
    // 限制条数
    if (filtered.length > MAX_HISTORY) {
        filtered.length = MAX_HISTORY;
    }
    saveHistory(filtered);
}

function loadHistory() {
    try {
        const raw = localStorage.getItem(HISTORY_KEY);
        return raw ? JSON.parse(raw) : [];
    } catch (_) {
        return [];
    }
}

function saveHistory(history) {
    try {
        localStorage.setItem(HISTORY_KEY, JSON.stringify(history));
    } catch (_) { /* ignore */ }
}

function deleteHistoryItem(idx) {
    const history = loadHistory();
    if (idx < 0 || idx >= history.length) return;
    const item = history[idx];
    history.splice(idx, 1);
    saveHistory(history);
    renderHistoryList();
    if (item.chatId === aiChatId) {
        aiChatId = null;
    }
}

function formatTime(ts) {
    const d = new Date(ts);
    const now = new Date();
    const pad = n => String(n).padStart(2, '0');
    const time = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
    if (d.toDateString() === now.toDateString()) {
        return time;
    }
    return `${pad(d.getMonth() + 1)}/${pad(d.getDate())} ${time}`;
}

function escapeHtml(str) {
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}

function resetSession() {
    aiChatId = null;
    aiAssistantBubble = null;
    aiToolBlocks.clear();
    aiThinkBlock = null;
    document.getElementById('ai-messages').innerHTML = '';
    document.getElementById('ai-input').value = '';
    const input = document.getElementById('ai-input');
    input.style.height = 'auto';
    input.style.overflowY = 'hidden';
    setSending(false);
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
        // 不预建 content span，由 appendToAssistantBubble / createToolBlock 按时间顺序追加
        if (text) {
            const content = document.createElement('span');
            content.className = 'ai-message-content';
            content.textContent = text;
            bubble.appendChild(content);
        }
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

/** ── 思考过程块 ── */

/** 创建思考过程块 */
function createThinkBlock() {
    const block = document.createElement('div');
    block.className = 'ai-think-block is-expanded is-thinking';

    const header = document.createElement('div');
    header.className = 'ai-think-header';

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

/** ── 工具调用块 ── */

/** 创建工具调用块 */
function createToolBlock(toolName, toolCallId) {
    const block = document.createElement('div');
    block.className = 'ai-tool-block is-running';
    block.dataset.toolCallId = toolCallId;

    const header = document.createElement('div');
    header.className = 'ai-tool-header';

    // 工具图标
    const icon = document.createElement('span');
    icon.className = 'ai-tool-icon';
    icon.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/>
    </svg>`;

    const name = document.createElement('span');
    name.className = 'ai-tool-name';
    name.textContent = toolName;

    const status = document.createElement('span');
    status.className = 'ai-tool-status';
    status.innerHTML = '<span class="ai-tool-spinner"></span>';

    const arrow = document.createElement('span');
    arrow.className = 'ai-tool-arrow';
    arrow.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
        <polyline points="6 9 12 15 18 9"/>
    </svg>`;

    header.appendChild(icon);
    header.appendChild(name);
    header.appendChild(status);
    header.appendChild(arrow);

    header.addEventListener('click', () => {
        block.classList.toggle('is-expanded');
    });

    // 结果内容区
    const content = document.createElement('div');
    content.className = 'ai-tool-content';

    block.appendChild(header);
    block.appendChild(content);

    // 按时间顺序插入到 assistant bubble 中
    if (aiAssistantBubble) {
        aiAssistantBubble.classList.remove('is-waiting');
        aiAssistantBubble.appendChild(block);
        // 确保 typing 指示器始终在末尾
        const indicator = aiAssistantBubble.querySelector('.ai-typing-indicator');
        if (indicator) {
            aiAssistantBubble.appendChild(indicator);
        }
    }

    aiToolBlocks.set(toolCallId, block);
    return block;
}

/** 完成工具调用 — 仅更新视觉状态，保留 map 引用供 observation 使用 */
function completeToolBlock(toolCallId) {
    const block = aiToolBlocks.get(toolCallId);
    if (!block) return;
    block.classList.remove('is-running');
    block.classList.add('is-done');
    // 如果有结果内容则展开
    const content = block.querySelector('.ai-tool-content');
    if (content && content.textContent.trim()) {
        block.classList.add('is-expanded');
    }
    const status = block.querySelector('.ai-tool-status');
    if (status) {
        status.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" class="ai-tool-check">
            <polyline points="20 6 9 17 4 12"/>
        </svg>`;
    }
    // 不删除 map 引用，observation 事件可能在 tool_end 之后到达
}

/** 设置工具调用结果 */
function setToolResult(toolCallId, text) {
    const block = aiToolBlocks.get(toolCallId);
    if (!block) return;
    const content = block.querySelector('.ai-tool-content');
    if (content) {
        content.textContent += text;
        content.scrollTop = content.scrollHeight;
        // 有结果时自动展开
        block.classList.add('is-expanded');
    }
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

    // 按时间顺序追加：如果最后一个子元素是文本段就续写，否则新建文本段
    const lastChild = aiAssistantBubble.lastElementChild;
    let content;
    if (lastChild && lastChild.classList.contains('ai-message-content')) {
        content = lastChild;
    } else {
        content = document.createElement('span');
        content.className = 'ai-message-content';
        aiAssistantBubble.appendChild(content);
    }
    content.textContent += cleaned;

    // 确保 typing 指示器始终在末尾
    const indicator = aiAssistantBubble.querySelector('.ai-typing-indicator');
    if (indicator) {
        aiAssistantBubble.appendChild(indicator);
    }

    requestAnimationFrame(() => {
        const messages = document.getElementById('ai-messages');
        if (messages) {
            messages.scrollTop = messages.scrollHeight;
        }
    });
}

function handleSseEvent(eventName, data) {
    switch (eventName) {
        case 'connected': {
            // 连接已建立，无需前端处理
            break;
        }
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
        case 'tool':
        case 'tool_start': {
            try {
                const parsed = JSON.parse(data);
                const toolName = parsed.tool || parsed.name || '未知工具';
                const toolCallId = parsed.toolCallId || parsed.id || Date.now().toString();
                createToolBlock(toolName, toolCallId);
                if (parsed.name === 'saveConfig') {
                    handleAgentJobSaved();
                }
            } catch (_) {
                createToolBlock(data, Date.now().toString());
            }
            break;
        }
        case 'tool_end': {
            try {
                const parsed = JSON.parse(data);
                const toolCallId = parsed.toolCallId || '';
                completeToolBlock(toolCallId);
            } catch (_) { /* ignore */ }
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
        case 'observation': {
            try {
                const parsed = JSON.parse(data);
                const toolCallId = parsed.toolCallId || '';
                if (parsed.delta) {
                    setToolResult(toolCallId, parsed.delta);
                }
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
                            aiAssistantBubble.classList.remove('is-waiting');
                            aiAssistantBubble.appendChild(aiThinkBlock);
                            // 确保 typing 指示器始终在末尾
                            const indicator = aiAssistantBubble.querySelector('.ai-typing-indicator');
                            if (indicator) {
                                aiAssistantBubble.appendChild(indicator);
                            }
                        }
                    }
                    const content = aiThinkBlock.querySelector('.ai-think-content');
                    if (content) {
                        content.textContent += parsed.delta;
                    }
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
            // 完成所有未结束的工具调用块
            aiToolBlocks.forEach((block, id) => {
                block.classList.remove('is-running');
                block.classList.add('is-done');
                block.classList.remove('is-expanded');
                const status = block.querySelector('.ai-tool-status');
                if (status) {
                    status.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" class="ai-tool-check">
                        <polyline points="20 6 9 17 4 12"/>
                    </svg>`;
                }
            });
            aiToolBlocks.clear();
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

    // 不再传递 mode 参数，模型自动处理思考
    const response = await fetch(
        `${AGENT_API}/chat/${encodeURIComponent(chatId)}`,
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

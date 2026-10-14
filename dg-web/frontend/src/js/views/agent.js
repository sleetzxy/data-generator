/** AI 助手视图：SSE 流式对话、思考/工具调用块渲染、会话历史管理。 */

import { api, fetchAuth } from '../core/api.js';
import { escapeHtml, showToast } from '../core/ui.js';
import { loadDefinitions } from './tasks.js';

const AGENT_API_BASE = '/api/v1/agent';

let aiChatId = null;
let aiSending = false;
let aiAssistantBubble = null;
/** toolCallId → 工具调用 DOM 块 */
let aiToolBlocks = new Map();
/** 当前思考过程块 */
let aiThinkBlock = null;

/** 当前 SSE 流的 AbortController，用于取消进行中的请求 */
let aiAbortController = null;

/** 缓存最近一次获取的会话列表 */
let aiSessionsCache = [];
/** 当前加载的会话消息（用于标题提取） */
let aiCurrentMessages = [];

/** 欢迎说明（LLM 用工具探库、建配置、预览并保存） */
const AGENT_WELCOME_TEXT = `我是 Data Generator 助手。

描述需求即可，我会用工具探库、建配置、预览并保存。
例如：给 users 表造 100 条测试数据，并粘贴数据库连接信息。`;

/** 绑定聊天表单、头部按钮与历史面板事件（由应用入口调用） */
export function initAgent() {
    const chatForm = document.getElementById('ai-chat-form');
    if (!chatForm) {
        return;
    }

    chatForm.addEventListener('submit', handleSend);
    document.getElementById('ai-new-chat').addEventListener('click', handleNewChat);
    document.getElementById('ai-history-btn').addEventListener('click', toggleHistoryPanel);
    document.getElementById('ai-clear-chat').addEventListener('click', handleClearChat);

    const input = document.getElementById('ai-input');
    input.addEventListener('input', handleInputChange);
    input.addEventListener('keydown', handleInputKeydown);

    // 点击面板外部关闭历史对话列表
    document.addEventListener('click', function (e) {
        const panel = document.getElementById('ai-history-panel');
        const btn = document.getElementById('ai-history-btn');
        if (!panel || panel.classList.contains('hidden')) {
            return;
        }
        if (!panel.contains(e.target) && e.target !== btn && !btn.contains(e.target)) {
            panel.classList.add('hidden');
        }
    });

    // 悬浮球开合抽屉：FAB / 关闭按钮 / 遮罩 / Escape
    initAiFabDrag();
    document.getElementById('ai-fab').addEventListener('click', function () {
        if (aiFabWasDragged) {
            return; // 拖动结束时触发的 click，不打开抽屉
        }
        openAiDrawer();
    });
    document.getElementById('ai-close').addEventListener('click', closeAiDrawer);
    document.getElementById('ai-drawer-backdrop').addEventListener('click', closeAiDrawer);
    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') {
            const enlarge = document.getElementById('ai-preview-enlarge-modal');
            if (enlarge && !enlarge.classList.contains('hidden')) {
                closeAiPreviewEnlarge();
                e.stopPropagation();
                return;
            }
            if (isAiDrawerOpen()) {
                closeAiDrawer();
            }
        }
    });

    const enlargeClose = document.getElementById('ai-preview-enlarge-close');
    if (enlargeClose) {
        enlargeClose.addEventListener('click', closeAiPreviewEnlarge);
    }
    const enlargeModal = document.getElementById('ai-preview-enlarge-modal');
    if (enlargeModal) {
        enlargeModal.querySelector('[data-ai-preview-enlarge-close]')
            ?.addEventListener('click', closeAiPreviewEnlarge);
    }
}

/** 悬浮球拖动状态：Pointer Events + setPointerCapture，位移超阈值视为拖动 */
let aiFabDragState = null;
let aiFabWasDragged = false;

const FAB_DRAG_THRESHOLD_PX = 4;
const FAB_VIEWPORT_MARGIN_PX = 8;

function clampFabPosition(value, max) {
    return Math.min(Math.max(value, FAB_VIEWPORT_MARGIN_PX), max - FAB_VIEWPORT_MARGIN_PX);
}

function initAiFabDrag() {
    const fab = document.getElementById('ai-fab');

    fab.addEventListener('pointerdown', function (e) {
        aiFabDragState = { pointerId: e.pointerId, startX: e.clientX, startY: e.clientY, moved: false };
        fab.setPointerCapture(e.pointerId);
    });

    fab.addEventListener('pointermove', function (e) {
        if (!aiFabDragState || aiFabDragState.pointerId !== e.pointerId) {
            return;
        }
        const dx = e.clientX - aiFabDragState.startX;
        const dy = e.clientY - aiFabDragState.startY;
        if (!aiFabDragState.moved && Math.hypot(dx, dy) < FAB_DRAG_THRESHOLD_PX) {
            return;
        }
        if (!aiFabDragState.moved) {
            // 首次移动：记录起始位置并切换到 left/top 定位
            aiFabDragState.moved = true;
            aiFabWasDragged = true;
            fab.classList.add('dragging');
            const rect = fab.getBoundingClientRect();
            aiFabDragState.originLeft = rect.left;
            aiFabDragState.originTop = rect.top;
            fab.style.left = rect.left + 'px';
            fab.style.top = rect.top + 'px';
            fab.style.right = 'auto';
            fab.style.bottom = 'auto';
        }
        const size = fab.offsetWidth;
        fab.style.left = clampFabPosition(aiFabDragState.originLeft + dx, window.innerWidth - size) + 'px';
        fab.style.top = clampFabPosition(aiFabDragState.originTop + dy, window.innerHeight - size) + 'px';
    });

    const endDrag = function (e) {
        if (!aiFabDragState || aiFabDragState.pointerId !== e.pointerId) {
            return;
        }
        const wasMoved = aiFabDragState.moved;
        fab.classList.remove('dragging');
        aiFabDragState = null;
        if (wasMoved) {
            // click 在 pointerup 之后派发，延迟重置避免下一次点击被吞
            setTimeout(() => { aiFabWasDragged = false; }, 0);
        }
    };
    fab.addEventListener('pointerup', endDrag);
    fab.addEventListener('pointercancel', endDrag);
}

function isAiDrawerOpen() {
    return document.getElementById('ai-drawer').classList.contains('open');
}

/** 打开抽屉浮层：显示欢迎语并聚焦输入框 */
function openAiDrawer() {
    document.getElementById('ai-drawer').classList.add('open');
    document.getElementById('ai-drawer-backdrop').classList.add('open');
    document.getElementById('ai-fab').classList.add('hidden');
    document.getElementById('ai-history-panel').classList.add('hidden');
    showWelcomeIfEmpty();
    setTimeout(() => document.getElementById('ai-input').focus(), 250);
}

function closeAiDrawer() {
    document.getElementById('ai-drawer').classList.remove('open');
    document.getElementById('ai-drawer-backdrop').classList.remove('open');
    document.getElementById('ai-fab').classList.remove('hidden');
}

async function ensureChat() {
    const response = await api('/chat/open', { method: 'POST' }, AGENT_API_BASE);
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

    // 中止进行中的旧 SSE 流（防御：理论上 aiSending 为 true 时会提前返回）
    if (aiAbortController) {
        aiAbortController.abort();
        aiAbortController = null;
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
        showToast('创建会话失败: ' + err.message);
        setSending(false);
        return;
    }

    aiAssistantBubble = appendMessage('assistant', '');
    showTypingIndicator(aiAssistantBubble);

    try {
        await sendAgentMessage(aiChatId, content, handleSseEvent);
    } catch (err) {
        appendToAssistantBubble('\n[错误] ' + err.message);
        showToast('发送失败: ' + err.message);
        setSending(false);
    }
}

/** ── 新对话 ── */
async function handleNewChat() {
    // 先中止进行中的 SSE 流，再重置会话
    if (aiAbortController) {
        aiAbortController.abort();
        aiAbortController = null;
    }
    document.getElementById('ai-history-panel').classList.add('hidden');
    resetSession();
    showToast('已开始新对话');
}

/** ── 清空当前会话 ── */
async function handleClearChat() {
    // 先中止进行中的 SSE 流
    if (aiAbortController) {
        aiAbortController.abort();
        aiAbortController = null;
    }
    // 删除服务端 Agent 状态
    if (aiChatId) {
        try {
            await api(`/chat/${encodeURIComponent(aiChatId)}`, { method: 'DELETE' }, AGENT_API_BASE);
        } catch (e) {
            // 删除失败不阻塞前端重置
            console.warn('删除服务端会话失败:', e);
        }
    }
    resetSession();
    showToast('会话已清空');
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
async function renderHistoryList() {
    const list = document.getElementById('ai-history-list');
    list.innerHTML = '<div class="ai-history-empty">加载中...</div>';

    let sessions;
    try {
        sessions = await fetchSessions();
    } catch (e) {
        list.innerHTML = '<div class="ai-history-empty">加载失败，请重试</div>';
        return;
    }

    if (!sessions || sessions.length === 0) {
        list.innerHTML = '<div class="ai-history-empty">暂无历史对话</div>';
        return;
    }

    list.innerHTML = sessions.map((item, idx) => {
        const title = item.title || '未命名对话';
        const time = formatSessionTime(item.updatedAt);
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

async function switchToHistory(idx) {
    const item = aiSessionsCache[idx];
    if (!item) return;

    // 先中止进行中的 SSE 流
    if (aiAbortController) {
        aiAbortController.abort();
        aiAbortController = null;
    }

    const messagesEl = document.getElementById('ai-messages');
    messagesEl.innerHTML = '<div class="ai-history-empty">加载中...</div>';
    aiAssistantBubble = null;
    aiToolBlocks.clear();
    aiThinkBlock = null;
    aiLastPreviewFingerprint = '';
    // 提前设置 chatId，防止加载期间用户发送消息到错误会话
    aiChatId = item.chatId;

    let sessionMessages;
    try {
        sessionMessages = await fetchSessionMessages(item.chatId);
    } catch (e) {
        showToast('加载会话失败: ' + e.message);
        messagesEl.innerHTML = '';
        showWelcomeIfEmpty();
        return;
    }

    messagesEl.innerHTML = '';

    // 恢复历史消息（含 blocks 结构）
    const messages = sessionMessages.messages || [];
    if (messages.length > 0) {
        messages.forEach(msg => {
            const blocks = msg.blocks || [];
            if (msg.role === 'user') {
                // 用户消息：取第一个 text block 作为内容
                const textBlock = blocks.find(b => b.type === 'text');
                appendMessage('user', textBlock ? textBlock.text : '');
                // 重置 assistant bubble 引用，确保下一轮对话创建新的 assistant 气泡
                // 否则多轮历史中后续 assistant 消息会错误地追加到第一个 assistant 气泡
                aiAssistantBubble = null;
                aiToolBlocks.clear();
            } else {
                // 非用户消息：按 block 顺序渲染
                renderHistoryBlocks(msg.role, blocks);
            }
        });
    } else {
        showWelcomeIfEmpty();
    }

    document.getElementById('ai-history-panel').classList.add('hidden');
    showToast('已切换到: ' + (item.title || '历史对话'));
}

/**
 * 按 block 顺序渲染历史消息中的结构化内容。
 * 与直播 SSE 事件处理保持一致的视觉结构：文本段、思考块、工具调用块、工具结果。
 */
function renderHistoryBlocks(role, blocks) {
    for (const block of blocks) {
        switch (block.type) {
            case 'text': {
                if (role === 'tool') {
                    // tool 角色的文本追加到最近的工具结果块
                    // 历史恢复中 TOOL 消息不太常见，直接追加到 assistant bubble
                    if (aiAssistantBubble) {
                        appendTextToAssistantBubble(block.text);
                    } else {
                        aiAssistantBubble = appendMessage('assistant', '');
                        appendTextToAssistantBubble(block.text);
                    }
                } else {
                    if (aiAssistantBubble) {
                        appendTextToAssistantBubble(block.text);
                    } else {
                        aiAssistantBubble = appendMessage('assistant', '');
                        appendTextToAssistantBubble(block.text);
                    }
                }
                break;
            }
            case 'thinking': {
                if (!aiAssistantBubble) {
                    aiAssistantBubble = appendMessage('assistant', '');
                }
                const thinkBlock = createThinkBlock();
                thinkBlock.classList.remove('is-thinking', 'is-expanded');
                thinkBlock.classList.add('is-done');
                const textEl = thinkBlock.querySelector('.ai-think-header-text');
                if (textEl) textEl.textContent = '思考过程';
                const dots = thinkBlock.querySelector('.ai-think-dots');
                if (dots) {
                    dots.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" class="ai-tool-check">
                        <polyline points="20 6 9 17 4 12"/>
                    </svg>`;
                }
                const content = thinkBlock.querySelector('.ai-think-content');
                if (content) content.textContent = block.thinking;
                aiAssistantBubble.appendChild(thinkBlock);
                break;
            }
            case 'tool_call': {
                if (!aiAssistantBubble) {
                    aiAssistantBubble = appendMessage('assistant', '');
                }
                const toolBlock = createToolBlock(block.toolName, block.toolCallId);
                toolBlock.classList.remove('is-running');
                toolBlock.classList.add('is-done');
                const status = toolBlock.querySelector('.ai-tool-status');
                if (status) {
                    status.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" class="ai-tool-check">
                        <polyline points="20 6 9 17 4 12"/>
                    </svg>`;
                }
                aiToolBlocks.set(block.toolCallId, toolBlock);
                break;
            }
            case 'tool_result': {
                const toolBlock = aiToolBlocks.get(block.toolCallId);
                if (toolBlock) {
                    applyToolResultContent(toolBlock, block.text || '');
                }
                break;
            }
        }
    }
}

/** 追加文本到 assistant bubble，复用现有文本段或创建新段。 */
function appendTextToAssistantBubble(text) {
    const cleaned = stripInternalAgentHints(text);
    if (!cleaned) return;
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
}

/** ── 服务端会话 API ── */

async function fetchSessions() {
    const response = await api('/sessions', {}, AGENT_API_BASE);
    if (!response || !response.data) {
        return [];
    }
    aiSessionsCache = response.data;
    return aiSessionsCache;
}

async function fetchSessionMessages(chatId) {
    const response = await api(`/chat/${encodeURIComponent(chatId)}/messages`, {}, AGENT_API_BASE);
    if (!response || !response.data) {
        return { chatId, messages: [] };
    }
    aiCurrentMessages = response.data.messages || [];
    return response.data;
}

async function deleteSessionRemote(chatId) {
    await api(`/chat/${encodeURIComponent(chatId)}`, { method: 'DELETE' }, AGENT_API_BASE);
}

/** ── 删除历史项 ── */
async function deleteHistoryItem(idx) {
    const item = aiSessionsCache[idx];
    if (!item) return;
    try {
        await deleteSessionRemote(item.chatId);
    } catch (e) {
        showToast('删除失败: ' + e.message);
        return;
    }
    if (item.chatId === aiChatId) {
        aiChatId = null;
    }
    aiSessionsCache.splice(idx, 1);
    renderHistoryList();
}

/** 会话时间显示：今天 HH:mm，否则 M/D HH:mm */
function formatSessionTime(updatedAt) {
    if (!updatedAt) return '';
    try {
        const d = new Date(updatedAt);
        if (isNaN(d.getTime())) return '';
        const now = new Date();
        const pad = n => String(n).padStart(2, '0');
        const time = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
        if (d.toDateString() === now.toDateString()) {
            return time;
        }
        return `${pad(d.getMonth() + 1)}/${pad(d.getDate())} ${time}`;
    } catch (_) {
        return '';
    }
}

function resetSession() {
    // 中止进行中的 SSE 流
    if (aiAbortController) {
        aiAbortController.abort();
        aiAbortController = null;
    }
    aiChatId = null;
    aiAssistantBubble = null;
    aiToolBlocks.clear();
    aiThinkBlock = null;
    aiLastPreviewFingerprint = '';
    aiCurrentMessages = [];
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
        .replace(/\[任务配置 YAML 草稿已存入会话[\s\S]*?\]/g, '')
        .replace(/\[意图快照已存入会话[\s\S]*?\]/g, '')
        .replace(/\[参考任务配置「[\s\S]*?\]/g, '')
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
    text.textContent = '思考过程';

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

/** 思考完成，更新块状态 — 默认折叠并显示完成勾 */
function finalizeThinkBlock(block) {
    if (!block) return;
    block.classList.remove('is-thinking', 'is-expanded');
    block.classList.add('is-done');
    const dots = block.querySelector('.ai-think-dots');
    if (dots) {
        // 替换动画点为完成勾
        dots.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" class="ai-tool-check">
            <polyline points="20 6 9 17 4 12"/>
        </svg>`;
    }
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
    if (!aiAssistantBubble) {
        aiAssistantBubble = appendMessage('assistant', '');
    }
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
    const status = block.querySelector('.ai-tool-status');
    if (status) {
        status.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" class="ai-tool-check">
            <polyline points="20 6 9 17 4 12"/>
        </svg>`;
    }
    // 不删除 map 引用，observation 事件可能在 tool_end 之后到达
}

/** 设置工具调用结果（追加） */
function setToolResult(toolCallId, text) {
    const block = aiToolBlocks.get(toolCallId);
    if (!block) return;
    const content = block.querySelector('.ai-tool-content');
    if (content) {
        content.textContent += text;
        content.scrollTop = content.scrollHeight;
    }
}

/** 覆盖工具结果文本（纯文本，不美化） */
function setToolResultReplace(toolCallId, text) {
    const block = aiToolBlocks.get(toolCallId);
    if (!block) return;
    const content = block.querySelector('.ai-tool-content');
    if (content) {
        content.textContent = text == null ? '' : String(text);
        content.scrollTop = content.scrollHeight;
    }
}

/** 避免同一预览结果重复插入气泡卡片 */
let aiLastPreviewFingerprint = '';

/**
 * observation 结束：按工具返回 JSON 约定渲染；无约定则原样文本。
 */
function finalizeToolObservation(toolCallId, resultText) {
    const block = aiToolBlocks.get(toolCallId);
    if (!block) {
        return;
    }
    const content = block.querySelector('.ai-tool-content');
    if (!content) {
        return;
    }

    let raw = resultText;
    if (raw == null || String(raw).trim() === '') {
        raw = content.textContent || '';
    }
    raw = String(raw || '').trim();
    if (!raw) {
        return;
    }

    applyToolResultContent(block, raw);
}

/**
 * 工具结果约定（与后端 Tool 对齐）：
 * { "ui":"table", "title"?, "message"?, "tables":[...] }
 * 前端只认 ui，不认工具名。直播 SSE 与历史恢复共用。
 */
function applyToolResultContent(toolBlock, rawText) {
    const content = toolBlock.querySelector('.ai-tool-content');
    if (!content) {
        return;
    }
    const raw = String(rawText || '').trim();
    if (!raw) {
        return;
    }
    const uiPayload = tryParseUiPayload(raw);
    if (uiPayload && uiPayload.ui === 'table' && uiPayload.tables.length > 0) {
        content.textContent = uiPayload.message || '预览成功';
        appendAiPreviewCard(uiPayload.tables, uiPayload.title);
        return;
    }
    content.textContent = raw;
    toolBlock.classList.add('is-expanded');
    content.scrollTop = content.scrollHeight;
}

/** 解析带 ui 约定的工具 JSON；失败或不含 ui 返回 null */
function tryParseUiPayload(text) {
    const trimmed = String(text || '').trim();
    if (!trimmed.startsWith('{')) {
        return null;
    }
    try {
        const obj = JSON.parse(trimmed);
        if (!obj || typeof obj.ui !== 'string' || !obj.ui) {
            return null;
        }
        return {
            ui: obj.ui,
            title: obj.title || '',
            message: obj.message || obj.summary || '',
            tables: Array.isArray(obj.tables) ? normalizePreviewTables(obj.tables) : []
        };
    } catch (_) {
        return null;
    }
}

function normalizePreviewTables(tables) {
    return tables.map((t) => ({
        tableName: t.tableName || '未命名表',
        columns: Array.isArray(t.columns) ? t.columns : [],
        rows: Array.isArray(t.rows) ? t.rows : []
    }));
}

/** 在助手气泡内追加独立预览卡（非工具样式），支持放大 */
function appendAiPreviewCard(tables, cardTitle) {
    const safeTables = normalizePreviewTables(tables);
    if (!safeTables.length) {
        return;
    }
    const displayTitle = (cardTitle && String(cardTitle).trim()) || '样例预览';
    let fingerprint = '';
    try {
        fingerprint = JSON.stringify({ title: displayTitle, tables: safeTables });
    } catch (_) {
        fingerprint = String(safeTables.length);
    }
    if (fingerprint && fingerprint === aiLastPreviewFingerprint) {
        return;
    }
    aiLastPreviewFingerprint = fingerprint;

    if (!aiAssistantBubble) {
        aiAssistantBubble = appendMessage('assistant', '');
    }
    hideTypingIndicator(aiAssistantBubble);

    const card = document.createElement('div');
    card.className = 'ai-preview-card';
    card.setAttribute('data-ai-preview', '1');

    const header = document.createElement('div');
    header.className = 'ai-preview-header';
    const title = document.createElement('span');
    title.className = 'ai-preview-title';
    title.textContent = displayTitle;
    const enlargeBtn = document.createElement('button');
    enlargeBtn.type = 'button';
    enlargeBtn.className = 'ai-preview-enlarge-btn';
    enlargeBtn.title = '放大查看';
    enlargeBtn.setAttribute('aria-label', '放大查看');
    enlargeBtn.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="14" height="14" aria-hidden="true">
        <polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/>
        <line x1="21" y1="3" x2="14" y2="10"/><line x1="3" y1="21" x2="10" y2="14"/>
    </svg>`;
    enlargeBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        openAiPreviewEnlarge(safeTables, displayTitle);
    });
    header.appendChild(title);
    header.appendChild(enlargeBtn);
    card.appendChild(header);
    card.appendChild(buildPreviewTablesDom(safeTables, { compact: true }));

    const messages = document.getElementById('ai-messages');
    if (aiAssistantBubble && aiAssistantBubble.isConnected) {
        aiAssistantBubble.appendChild(card);
        const indicator = aiAssistantBubble.querySelector('.ai-typing-indicator');
        if (indicator) {
            aiAssistantBubble.appendChild(indicator);
        }
    } else if (messages) {
        messages.appendChild(card);
    }

    requestAnimationFrame(() => {
        if (messages) {
            messages.scrollTop = messages.scrollHeight;
        }
    });
}

function openAiPreviewEnlarge(tables, cardTitle) {
    const modal = document.getElementById('ai-preview-enlarge-modal');
    const body = document.getElementById('ai-preview-enlarge-body');
    const title = document.getElementById('ai-preview-enlarge-title');
    if (!modal || !body) {
        return;
    }
    if (title) {
        title.textContent = (cardTitle && String(cardTitle).trim()) || '样例预览';
    }
    body.replaceChildren();
    body.appendChild(buildPreviewTablesDom(normalizePreviewTables(tables), { compact: false }));
    modal.classList.remove('hidden');
}

function closeAiPreviewEnlarge() {
    const modal = document.getElementById('ai-preview-enlarge-modal');
    const body = document.getElementById('ai-preview-enlarge-body');
    if (modal) {
        modal.classList.add('hidden');
    }
    if (body) {
        body.replaceChildren();
    }
}

/**
 * @param {Array} tables 已 normalize
 * @param {{ compact?: boolean }} options
 */
function buildPreviewTablesDom(tables, options = {}) {
    const compact = options.compact !== false;
    const wrap = document.createElement('div');
    wrap.className = 'ai-preview-tables' + (compact ? ' is-compact' : ' is-enlarge');

    if (tables.length > 1) {
        const tabs = document.createElement('div');
        tabs.className = 'ai-preview-tabs';
        tabs.setAttribute('role', 'tablist');
        tables.forEach((table, index) => {
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'ai-preview-tab' + (index === 0 ? ' active' : '');
            btn.setAttribute('role', 'tab');
            btn.dataset.tabIndex = String(index);
            btn.setAttribute('aria-selected', index === 0 ? 'true' : 'false');
            const count = table.rows.length;
            btn.textContent = count > 0 ? `${table.tableName} (${count})` : table.tableName;
            tabs.appendChild(btn);
        });
        wrap.appendChild(tabs);

        const panels = document.createElement('div');
        panels.className = 'ai-preview-panels';
        tables.forEach((table, index) => {
            const panel = document.createElement('div');
            panel.className = 'ai-preview-panel' + (index === 0 ? '' : ' hidden');
            panel.dataset.panelIndex = String(index);
            panel.setAttribute('role', 'tabpanel');
            panel.appendChild(buildSinglePreviewTable(table, compact));
            panels.appendChild(panel);
        });
        wrap.appendChild(panels);

        tabs.querySelectorAll('.ai-preview-tab').forEach((tab) => {
            tab.addEventListener('click', () => {
                const index = Number(tab.dataset.tabIndex);
                tabs.querySelectorAll('.ai-preview-tab').forEach((t) => {
                    const on = Number(t.dataset.tabIndex) === index;
                    t.classList.toggle('active', on);
                    t.setAttribute('aria-selected', on ? 'true' : 'false');
                });
                panels.querySelectorAll('.ai-preview-panel').forEach((panel) => {
                    panel.classList.toggle('hidden', Number(panel.dataset.panelIndex) !== index);
                });
            });
        });
        return wrap;
    }

    wrap.appendChild(buildSinglePreviewTable(tables[0], compact));
    return wrap;
}

function buildSinglePreviewTable(table, compact) {
    const columns = table.columns && table.columns.length
        ? table.columns
        : Object.keys((table.rows && table.rows[0]) || {});
    const rows = table.rows || [];

    if (!rows.length) {
        const empty = document.createElement('p');
        empty.className = 'ai-preview-empty';
        empty.textContent = '该表无样例行';
        return empty;
    }

    const scroll = document.createElement('div');
    scroll.className = 'ai-preview-table-wrap';
    const el = document.createElement('table');
    const thead = document.createElement('thead');
    const headRow = document.createElement('tr');
    for (const col of columns) {
        const th = document.createElement('th');
        th.textContent = String(col);
        th.title = String(col);
        headRow.appendChild(th);
    }
    thead.appendChild(headRow);
    el.appendChild(thead);

    const tbody = document.createElement('tbody');
    for (const row of rows) {
        const tr = document.createElement('tr');
        for (const col of columns) {
            const td = document.createElement('td');
            td.appendChild(formatAiPreviewCell(row ? row[col] : null, compact));
            tr.appendChild(td);
        }
        tbody.appendChild(tr);
    }
    el.appendChild(tbody);
    scroll.appendChild(el);
    return scroll;
}

function formatAiPreviewCell(value, compact) {
    const span = document.createElement('span');
    if (value == null) {
        span.className = 'ai-preview-null';
        span.textContent = '—';
        return span;
    }
    let text;
    if (typeof value === 'object') {
        try {
            text = JSON.stringify(value);
        } catch (_) {
            text = String(value);
        }
    } else {
        text = String(value);
    }
    span.className = 'ai-preview-cell' + (compact ? ' is-compact' : '');
    span.textContent = text;
    span.title = text;
    return span;
}

function handleAgentTaskConfigSaved() {
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
                // 幂等处理：TOOL_CALL_START 和 TOOL_RESULT_START 都可能触发 tool_start，
                // 避免为同一 toolCallId 创建重复的 DOM 块
                if (!aiToolBlocks.has(toolCallId)) {
                    createToolBlock(toolName, toolCallId);
                }
                if (toolName === 'saveConfig' || toolName === 'saveConfigDraft') {
                    handleAgentTaskConfigSaved();
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
            handleAgentTaskConfigSaved();
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
                // delta: 流式逐段追加（正常路径）
                if (parsed.delta) {
                    setToolResult(toolCallId, parsed.delta);
                }
                if (parsed.end) {
                    finalizeToolObservation(toolCallId, parsed.result);
                } else if (parsed.result) {
                    setToolResultReplace(toolCallId, parsed.result);
                }
            } catch (_) { /* ignore */ }
            break;
        }
        case 'thinking': {
            try {
                const parsed = JSON.parse(data);
                if (parsed.end) {
                    // 思考阶段结束：finalize 当前块并重置引用，下次 delta 创建新块
                    if (aiThinkBlock) {
                        finalizeThinkBlock(aiThinkBlock);
                        aiThinkBlock = null;
                    }
                } else if (parsed.delta) {
                    if (!aiThinkBlock) {
                        aiThinkBlock = createThinkBlock();
                        // 确保有 assistant bubble 可容纳思考块
                        if (!aiAssistantBubble) {
                            aiAssistantBubble = appendMessage('assistant', '');
                        }
                        if (aiAssistantBubble) {
                            aiAssistantBubble.classList.remove('is-waiting');
                            aiAssistantBubble.appendChild(aiThinkBlock);
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
            showToast(message);
            setSending(false);
            break;
        }
        case 'done':
            hideTypingIndicator(aiAssistantBubble);
            if (aiThinkBlock) {
                finalizeThinkBlock(aiThinkBlock);
                aiThinkBlock = null;
            }
            // 完成所有未结束的工具调用块——全部折叠
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
    // 创建 AbortController 以支持取消进行中的流
    aiAbortController = new AbortController();

    // 不再传递 mode 参数，模型自动处理思考
    const response = await fetchAuth(
        `${AGENT_API_BASE}/chat/${encodeURIComponent(chatId)}`,
        {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Accept': 'text/event-stream'
            },
            body: JSON.stringify({ content }),
            signal: aiAbortController.signal
        }
    );
    if (!response) {
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

    try {
        while (true) {
            const { done, value } = await reader.read();
            if (done) {
                break;
            }
            buffer += decoder.decode(value, { stream: true });
            buffer = consumeSseBuffer(buffer, onEvent);
        }
        consumeSseBuffer(buffer + '\n\n', onEvent);
    } catch (err) {
        if (err.name === 'AbortError') {
            // 用户主动取消，无需报错
            return;
        }
        throw err;
    } finally {
        hideTypingIndicator(aiAssistantBubble);
        setSending(false);
        aiAbortController = null;
    }
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

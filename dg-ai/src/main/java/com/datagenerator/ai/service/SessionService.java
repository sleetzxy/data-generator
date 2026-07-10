package com.datagenerator.ai.service;

import com.datagenerator.ai.dto.SessionInfo;
import com.datagenerator.ai.dto.SessionMessages;
import com.datagenerator.ai.dto.SessionMessages.Block;
import com.datagenerator.ai.dto.SessionMessages.MessageItem;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 会话管理服务，封装基于 AgentStateStore 的会话列表、消息查询与删除操作。
 *
 * <p>所有操作使用统一的默认 userId（与 {@link AgentService} 保持一致），
 * 确保 Agent 持久化的会话数据与 API 接口操作同一命名空间。
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    /** 日期时间格式化器：ISO 格式（前端可解析） */
    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    private final AgentStateStore stateStore;

    public SessionService(AgentStateStore stateStore) {
        this.stateStore = stateStore;
    }

    /**
     * 列出当前用户的所有会话摘要。
     *
     * @return 按更新时间倒序排列的会话信息列表
     */
    public List<SessionInfo> listSessions() {
        var sessionIds = stateStore.listSessionIds(AgentService.DEFAULT_USER_ID);
        log.debug("查询到 {} 个会话", sessionIds.size());

        List<SessionInfo> result = new ArrayList<>();
        for (String sessionId : sessionIds) {
            try {
                readSessionInfo(sessionId).ifPresent(result::add);
            } catch (Exception e) {
                log.warn("读取会话 {} 摘要失败: {}", sessionId, e.getMessage());
            }
        }

        // 按 chatId 倒序（UUID 时间戳序），最新会话在前
        result.sort(Comparator.comparing(SessionInfo::chatId).reversed());
        return result;
    }

    /**
     * 读取指定会话的消息历史，包含完整的 block 结构信息。
     *
     * @param chatId 会话标识（即 sessionId）
     * @return 消息列表，会话不存在时返回空列表
     */
    public SessionMessages getMessages(String chatId) {
        Optional<AgentState> stateOpt =
                stateStore.get(AgentService.DEFAULT_USER_ID, chatId, "agent_state", AgentState.class);

        if (stateOpt.isEmpty()) {
            log.debug("会话 {} 不存在", chatId);
            return new SessionMessages(chatId, List.of());
        }

        AgentState state = stateOpt.get();
        List<Msg> context = state.getContext();
        List<MessageItem> messages = new ArrayList<>(context.size());

        for (Msg msg : context) {
            String role = mapRole(msg.getRole());
            List<ContentBlock> content = msg.getContent();
            if (content == null || content.isEmpty()) {
                continue;
            }

            List<Block> blocks = new ArrayList<>(content.size());
            for (ContentBlock cb : content) {
                Block item = mapBlock(cb);
                if (item != null) {
                    blocks.add(item);
                }
            }

            if (!blocks.isEmpty()) {
                messages.add(new MessageItem(role, blocks));
            }
        }

        return new SessionMessages(chatId, messages);
    }

    /** 将 AgentScope ContentBlock 映射为 DTO Block。 */
    private static Block mapBlock(ContentBlock cb) {
        if (cb instanceof io.agentscope.core.message.TextBlock tb) {
            String text = tb.getText();
            if (text != null && !text.isBlank()) {
                return new SessionMessages.TextBlock(text);
            }
            return null;
        }
        if (cb instanceof io.agentscope.core.message.ThinkingBlock tkb) {
            String thinking = tkb.getThinking();
            if (thinking != null && !thinking.isBlank()) {
                return new SessionMessages.ThinkingBlock(thinking);
            }
            return null;
        }
        if (cb instanceof io.agentscope.core.message.ToolUseBlock tub) {
            return new SessionMessages.ToolCallBlock(tub.getId(), tub.getName());
        }
        if (cb instanceof io.agentscope.core.message.ToolResultBlock trb) {
            // 提取 tool result 中的文本输出
            StringBuilder sb = new StringBuilder();
            for (ContentBlock outBlock : trb.getOutput()) {
                if (outBlock instanceof io.agentscope.core.message.TextBlock otb) {
                    String t = otb.getText();
                    if (t != null && !t.isBlank()) {
                        if (!sb.isEmpty()) {
                            sb.append('\n');
                        }
                        sb.append(t);
                    }
                }
            }
            String resultText = sb.toString();
            if (!resultText.isBlank()) {
                return new SessionMessages.ToolResultBlock(trb.getId(), resultText);
            }
            return null;
        }
        return null;
    }

    /**
     * 删除指定会话的全部数据。
     *
     * @param chatId 会话标识
     * @return 是否真正删除了数据（会话存在时返回 true）
     */
    public boolean deleteSession(String chatId) {
        boolean existed = stateStore.exists(AgentService.DEFAULT_USER_ID, chatId);
        if (!existed) {
            log.debug("会话 {} 不存在，跳过删除", chatId);
            return false;
        }
        stateStore.delete(AgentService.DEFAULT_USER_ID, chatId);
        log.info("已删除会话: {}", chatId);
        return true;
    }

    /** 从 AgentState 读取单个会话的摘要信息。 */
    private Optional<SessionInfo> readSessionInfo(String sessionId) {
        Optional<AgentState> stateOpt =
                stateStore.get(AgentService.DEFAULT_USER_ID, sessionId, "agent_state", AgentState.class);

        if (stateOpt.isEmpty()) {
            return Optional.empty();
        }

        AgentState state = stateOpt.get();
        List<Msg> context = state.getContext();
        int messageCount = context.size();

        // 取第一条 user 消息作为标题
        String title = "空会话";
        for (Msg msg : context) {
            if (msg.getRole() == MsgRole.USER) {
                String text = msg.getTextContent();
                if (text != null && !text.isBlank()) {
                    title = text.length() > 40 ? text.substring(0, 40) : text;
                    break;
                }
            }
        }

        // 从文件修改时间获取实际会话时间
        String updatedAt = readSessionTime(sessionId);

        return Optional.of(new SessionInfo(sessionId, title, messageCount, updatedAt));
    }

    /**
     * 读取会话文件的最后修改时间作为会话时间。
     * 对于 JsonFileAgentStateStore，直接读取 agent_state.json 的文件时间；
     * 其他 store 类型回退到当前时间。
     */
    private String readSessionTime(String sessionId) {
        if (stateStore instanceof JsonFileAgentStateStore jfs) {
            try {
                // 构造 agent_state.json 路径：<root>/<safe(userId)>/<safe(sessionId)>/agent_state.json
                // 使用反射诱导的路径计算：先获取 session dir，再拼 agent_state.json
                Path root = jfs.getRootDirectory();
                // 编码逻辑从 JsonFileAgentStateStore 复制（safeSegment）
                String userDir = safeSegment(AgentService.DEFAULT_USER_ID);
                String sessionDir = safeSegment(sessionId);
                Path stateFile = root.resolve(userDir).resolve(sessionDir).resolve("agent_state.json");
                if (Files.exists(stateFile)) {
                    FileTime ft = Files.getLastModifiedTime(stateFile);
                    return ISO_FORMATTER.format(ft.toInstant());
                }
            } catch (Exception e) {
                log.debug("读取会话 {} 文件时间失败: {}", sessionId, e.getMessage());
            }
        }
        return ISO_FORMATTER.format(Instant.now());
    }

    /** 文件名安全编码，与 JsonFileAgentStateStore 保持一致。 */
    private static String safeSegment(String value) {
        if (value == null || value.isBlank()) {
            return "__anon__";
        }
        if (value.matches("^[a-zA-Z0-9_\\-.]+$")) {
            return value;
        }
        return java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 将 AgentScope MsgRole 映射为前端可用的角色字符串。 */
    private static String mapRole(MsgRole role) {
        return switch (role) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM -> "system";
            case TOOL -> "tool";
            default -> "unknown";
        };
    }
}

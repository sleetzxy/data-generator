package com.datagenerator.ai.service;

import com.datagenerator.ai.dto.SessionInfo;
import com.datagenerator.ai.dto.SessionMessages;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    @Mock
    private AgentStateStore stateStore;

    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        sessionService = new SessionService(stateStore);
    }

    @Test
    void listSessions_shouldReturnEmptyList_whenNoSessions() {
        when(stateStore.listSessionIds(AgentService.DEFAULT_USER_ID))
                .thenReturn(Set.of());

        var result = sessionService.listSessions();

        assertThat(result).isEmpty();
    }

    @Test
    void listSessions_shouldReturnSessions_whenSessionsExist() {
        when(stateStore.listSessionIds(AgentService.DEFAULT_USER_ID))
                .thenReturn(Set.of("abc123", "def456"));

        AgentState state1 = AgentState.builder()
                .sessionId("abc123")
                .userId(AgentService.DEFAULT_USER_ID)
                .addMessage(Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("帮我创建用户表").build())
                        .build())
                .addMessage(Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("好的").build())
                        .build())
                .build();

        AgentState state2 = AgentState.builder()
                .sessionId("def456")
                .userId(AgentService.DEFAULT_USER_ID)
                .addMessage(Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("列出所有连接").build())
                        .build())
                .build();

        when(stateStore.get(eq(AgentService.DEFAULT_USER_ID), eq("abc123"),
                eq("agent_state"), eq(AgentState.class)))
                .thenReturn(Optional.of(state1));
        when(stateStore.get(eq(AgentService.DEFAULT_USER_ID), eq("def456"),
                eq("agent_state"), eq(AgentState.class)))
                .thenReturn(Optional.of(state2));

        var result = sessionService.listSessions();

        assertThat(result).hasSize(2);
        // 按 updatedAt 倒序排列（mock 的 InMemoryStore 使用 Instant.now()，两者时间相同，稳定排序保持插入顺序）
        assertThat(result.get(0).chatId()).isEqualTo("abc123");
        assertThat(result.get(1).chatId()).isEqualTo("def456");
        assertThat(result.get(0).title()).isEqualTo("帮我创建用户表");
        assertThat(result.get(0).messageCount()).isEqualTo(2);
    }

    @Test
    void listSessions_shouldSkipSessionsWithoutState() {
        when(stateStore.listSessionIds(AgentService.DEFAULT_USER_ID))
                .thenReturn(Set.of("abc123", "missing"));

        AgentState state = AgentState.builder()
                .sessionId("abc123")
                .userId(AgentService.DEFAULT_USER_ID)
                .addMessage(Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("hello").build())
                        .build())
                .build();

        when(stateStore.get(eq(AgentService.DEFAULT_USER_ID), eq("abc123"),
                eq("agent_state"), eq(AgentState.class)))
                .thenReturn(Optional.of(state));
        when(stateStore.get(eq(AgentService.DEFAULT_USER_ID), eq("missing"),
                eq("agent_state"), eq(AgentState.class)))
                .thenReturn(Optional.empty());

        var result = sessionService.listSessions();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).chatId()).isEqualTo("abc123");
    }

    @Test
    void getMessages_shouldReturnMessages_whenSessionExists() {
        AgentState state = AgentState.builder()
                .sessionId("abc123")
                .userId(AgentService.DEFAULT_USER_ID)
                .addMessage(Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("用户消息").build())
                        .build())
                .addMessage(Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("助手回复").build())
                        .build())
                .build();

        when(stateStore.get(eq(AgentService.DEFAULT_USER_ID), eq("abc123"),
                eq("agent_state"), eq(AgentState.class)))
                .thenReturn(Optional.of(state));

        var result = sessionService.getMessages("abc123");

        assertThat(result.chatId()).isEqualTo("abc123");
        assertThat(result.messages()).hasSize(2);
        assertThat(result.messages().get(0).role()).isEqualTo("user");
        assertThat(result.messages().get(0).blocks()).hasSize(1);
        assertThat(result.messages().get(0).blocks().get(0))
                .isInstanceOf(SessionMessages.TextBlock.class);
        assertThat(((SessionMessages.TextBlock) result.messages().get(0).blocks().get(0)).text())
                .isEqualTo("用户消息");
        assertThat(result.messages().get(1).role()).isEqualTo("assistant");
        assertThat(((SessionMessages.TextBlock) result.messages().get(1).blocks().get(0)).text())
                .isEqualTo("助手回复");
    }

    @Test
    void getMessages_shouldReturnEmptyList_whenSessionNotFound() {
        when(stateStore.get(eq(AgentService.DEFAULT_USER_ID), eq("not-found"),
                eq("agent_state"), eq(AgentState.class)))
                .thenReturn(Optional.empty());

        var result = sessionService.getMessages("not-found");

        assertThat(result.chatId()).isEqualTo("not-found");
        assertThat(result.messages()).isEmpty();
    }

    @Test
    void getMessages_shouldSkipEmptyMessages() {
        AgentState state = AgentState.builder()
                .sessionId("abc123")
                .userId(AgentService.DEFAULT_USER_ID)
                .addMessage(Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("hello").build())
                        .build())
                .addMessage(Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("").build())
                        .build())
                .build();

        when(stateStore.get(eq(AgentService.DEFAULT_USER_ID), eq("abc123"),
                eq("agent_state"), eq(AgentState.class)))
                .thenReturn(Optional.of(state));

        var result = sessionService.getMessages("abc123");

        // 空白 text block 会被跳过，assistant 消息无有效 block 也会被跳过
        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().get(0).role()).isEqualTo("user");
    }

    @Test
    void getMessages_shouldExtractToolCallsAndResults() {
        AgentState state = AgentState.builder()
                .sessionId("abc123")
                .userId(AgentService.DEFAULT_USER_ID)
                .addMessage(Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("查询连接").build())
                        .build())
                .addMessage(Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(new io.agentscope.core.message.ToolUseBlock(
                                "call_1", "listConnections", java.util.Map.of()))
                        .build())
                .addMessage(Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(new io.agentscope.core.message.ToolResultBlock(
                                "call_1", "listConnections",
                                TextBlock.builder().text("dev-pg, dev-ch").build()))
                        .build())
                .build();

        when(stateStore.get(eq(AgentService.DEFAULT_USER_ID), eq("abc123"),
                eq("agent_state"), eq(AgentState.class)))
                .thenReturn(Optional.of(state));

        var result = sessionService.getMessages("abc123");

        assertThat(result.messages()).hasSize(3);
        // 用户消息
        assertThat(result.messages().get(0).blocks().get(0))
                .isInstanceOf(SessionMessages.TextBlock.class);
        // 工具调用
        assertThat(result.messages().get(1).blocks().get(0))
                .isInstanceOf(SessionMessages.ToolCallBlock.class);
        var toolCall = (SessionMessages.ToolCallBlock) result.messages().get(1).blocks().get(0);
        assertThat(toolCall.toolCallId()).isEqualTo("call_1");
        assertThat(toolCall.toolName()).isEqualTo("listConnections");
        // 工具结果
        assertThat(result.messages().get(2).blocks().get(0))
                .isInstanceOf(SessionMessages.ToolResultBlock.class);
        var toolResult = (SessionMessages.ToolResultBlock) result.messages().get(2).blocks().get(0);
        assertThat(toolResult.text()).isEqualTo("dev-pg, dev-ch");
    }

    @Test
    void deleteSession_shouldDelete_whenSessionExists() {
        when(stateStore.exists(AgentService.DEFAULT_USER_ID, "abc123"))
                .thenReturn(true);

        var result = sessionService.deleteSession("abc123");

        assertThat(result).isTrue();
        verify(stateStore).delete(AgentService.DEFAULT_USER_ID, "abc123");
    }

    @Test
    void deleteSession_shouldReturnFalse_whenSessionNotExists() {
        when(stateStore.exists(AgentService.DEFAULT_USER_ID, "abc123"))
                .thenReturn(false);

        var result = sessionService.deleteSession("abc123");

        assertThat(result).isFalse();
    }

    @Test
    void listSessions_shouldHandleEmptyTitleGracefully() {
        when(stateStore.listSessionIds(AgentService.DEFAULT_USER_ID))
                .thenReturn(Set.of("abc123"));

        AgentState state = AgentState.builder()
                .sessionId("abc123")
                .userId(AgentService.DEFAULT_USER_ID)
                .build();

        when(stateStore.get(anyString(), anyString(), anyString(), eq(AgentState.class)))
                .thenReturn(Optional.of(state));

        var result = sessionService.listSessions();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("空会话");
        assertThat(result.get(0).messageCount()).isEqualTo(0);
    }
}

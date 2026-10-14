package com.datagenerator.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagenerator.ai.client.DgWebClient;
import com.datagenerator.ai.client.DgWebClient.ConnectionTestResult;
import com.datagenerator.ai.client.DgWebClient.RemoteColumn;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConnectionToolsTest {

    @Mock
    private DgWebClient client;

    private ConnectionTools tools;

    @BeforeEach
    void setUp() {
        tools = new ConnectionTools(Optional.of(client));
    }

    @Test
    void testConnection_success_returnsOkMessage() {
        when(client.testConnection(any())).thenReturn(new ConnectionTestResult(true, null));

        String result = tools.testConnection(
                "postgresql", "jdbc:postgresql://localhost/db",
                null, "u", "secret", null, null);

        assertThat(result).contains("成功");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> probeCaptor = ArgumentCaptor.forClass(Map.class);
        verify(client).testConnection(probeCaptor.capture());
        assertThat(probeCaptor.getValue())
                .containsEntry("type", "postgresql")
                .containsEntry("password", "secret");
    }

    @Test
    void testConnection_failure_returnsMessage() {
        when(client.testConnection(any()))
                .thenReturn(new ConnectionTestResult(false, "连接测试失败"));

        String result = tools.testConnection(
                "postgresql", "jdbc:postgresql://localhost/db",
                null, "u", "bad", null, null);

        assertThat(result).contains("连接测试失败");
    }

    @Test
    void listRemoteTables_empty_guidesRetryOrManual() {
        when(client.listRemoteTables(any())).thenReturn(List.of());

        String result = tools.listRemoteTables(
                "postgresql", "jdbc:postgresql://localhost/db",
                null, "u", "p", "public", null, null);

        assertThat(result).contains("未找到匹配的表");
    }

    @Test
    void listRemoteTables_returnsNames() {
        when(client.listRemoteTables(any())).thenReturn(List.of("users", "orders"));

        String result = tools.listRemoteTables(
                "postgresql", "jdbc:postgresql://localhost/db",
                null, "u", "p", "public", null, null);

        assertThat(result).contains("users").contains("orders");
    }

    @Test
    void getRemoteColumns_returnsFieldSummary() {
        when(client.listRemoteColumns(any(), eq("users"))).thenReturn(List.of(
                new RemoteColumn("id", "int4", false, true),
                new RemoteColumn("name", "varchar", true, false)));

        String result = tools.getRemoteColumns(
                "postgresql", "jdbc:postgresql://localhost/db",
                null, "u", "p", "users", null);

        assertThat(result).contains("id").contains("PK").contains("name");
    }

    @Test
    void testConnection_blankType_returnsError() {
        String result = tools.testConnection(
                "  ", "jdbc:postgresql://localhost/db",
                null, "u", "p", null, null);

        assertThat(result).contains("type");
    }
}

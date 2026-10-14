package com.datagenerator.web.service;

import com.datagenerator.core.engine.PluginRegistry;
import com.datagenerator.spi.inspector.ColumnMeta;
import com.datagenerator.spi.inspector.ConnectionInspector;
import com.datagenerator.spi.inspector.ConnectionProbe;
import com.datagenerator.web.dto.ConnectionProbeRequest;
import com.datagenerator.web.dto.ConnectionTestResponse;
import com.datagenerator.web.dto.RemoteColumnListResponse;
import com.datagenerator.web.dto.RemoteTableListResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConnectionExploreServiceTest {

    @Mock
    private PluginRegistry pluginRegistry;

    @Mock
    private ConnectionInspector inspector;

    private ConnectionExploreService service;

    @BeforeEach
    void setUp() {
        service = new ConnectionExploreService(pluginRegistry);
    }

    @Test
    void test_validProbe_returnsOk() throws Exception {
        when(pluginRegistry.getInspector("postgresql")).thenReturn(inspector);
        ConnectionProbeRequest request = probeRequest("postgresql");

        ConnectionTestResponse response = service.test(request);

        assertThat(response.ok()).isTrue();
        ArgumentCaptor<ConnectionProbe> probeCaptor = ArgumentCaptor.forClass(ConnectionProbe.class);
        verify(inspector).test(probeCaptor.capture());
        ConnectionProbe probe = probeCaptor.getValue();
        assertThat(probe.type()).isEqualTo("postgresql");
        assertThat(probe.url()).isEqualTo("jdbc:postgresql://localhost/db");
        assertThat(probe.username()).isEqualTo("u");
        assertThat(probe.password()).isEqualTo("p");
    }

    @Test
    void test_inspectorFails_throwsFixedChineseSummaryOnly() throws Exception {
        when(pluginRegistry.getInspector("postgresql")).thenReturn(inspector);
        doThrow(new RuntimeException("Connection refused: jdbc:postgresql://secret-host:5432/db"))
                .when(inspector)
                .test(any(ConnectionProbe.class));

        assertThatThrownBy(() -> service.test(probeRequest("postgresql")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("连接测试失败")
                .hasMessageNotContaining("secret-host")
                .hasMessageNotContaining("jdbc:");
    }

    @Test
    void test_unknownType_throwsChineseMessage() {
        when(pluginRegistry.getInspector("oracle"))
                .thenThrow(new IllegalArgumentException("Unknown inspector type: oracle"));

        assertThatThrownBy(() -> service.test(probeRequest("oracle")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持")
                .hasMessageContaining("oracle");
    }

    @Test
    void listTables_withSchemaAndPattern_delegatesToInspector() throws Exception {
        when(pluginRegistry.getInspector("postgresql")).thenReturn(inspector);
        when(inspector.listTables(any(ConnectionProbe.class), eq("public"), eq("cust%")))
                .thenReturn(List.of("customers", "customer_orders"));
        ConnectionProbeRequest request = new ConnectionProbeRequest(
                "postgresql",
                "jdbc:postgresql://localhost/db",
                null,
                "u",
                "p",
                "public",
                "cust%");

        RemoteTableListResponse response = service.listTables(request);

        assertThat(response.tables()).containsExactly("customers", "customer_orders");
    }

    @Test
    void listTables_inspectorFails_throwsFixedChineseSummaryOnly() throws Exception {
        when(pluginRegistry.getInspector("csv")).thenReturn(inspector);
        when(inspector.listTables(any(ConnectionProbe.class), isNull(), isNull()))
                .thenThrow(new java.io.IOException("path not found: /secret/data.csv"));

        assertThatThrownBy(() -> service.listTables(probeRequest("csv")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("列出表失败")
                .hasMessageNotContaining("/secret/");
    }

    @Test
    void listColumns_validTable_mapsColumnMeta() throws Exception {
        when(pluginRegistry.getInspector("postgresql")).thenReturn(inspector);
        when(inspector.listColumns(any(ConnectionProbe.class), eq("customers")))
                .thenReturn(List.of(
                        new ColumnMeta("id", "int8", false, true),
                        new ColumnMeta("name", "varchar", true, false)));

        RemoteColumnListResponse response = service.listColumns(probeRequest("postgresql"), "customers");

        assertThat(response.columns()).hasSize(2);
        assertThat(response.columns().getFirst().name()).isEqualTo("id");
        assertThat(response.columns().getFirst().typeName()).isEqualTo("int8");
        assertThat(response.columns().getFirst().nullable()).isFalse();
        assertThat(response.columns().getFirst().primaryKey()).isTrue();
        assertThat(response.columns().get(1).name()).isEqualTo("name");
    }

    @Test
    void listColumns_inspectorFails_throwsFixedChineseSummaryOnly() throws Exception {
        when(pluginRegistry.getInspector("postgresql")).thenReturn(inspector);
        when(inspector.listColumns(any(ConnectionProbe.class), eq("missing")))
                .thenThrow(new IllegalStateException(
                        "relation does not exist on jdbc:postgresql://secret-host/db"));

        assertThatThrownBy(() -> service.listColumns(probeRequest("postgresql"), "missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("列出字段失败")
                .hasMessageNotContaining("secret-host")
                .hasMessageNotContaining("jdbc:");
    }

    @Test
    void test_blankType_throwsChineseMessage() {
        ConnectionProbeRequest request = new ConnectionProbeRequest(
                "  ", null, null, null, null, null, null);

        assertThatThrownBy(() -> service.test(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");
    }

    private static ConnectionProbeRequest probeRequest(String type) {
        return new ConnectionProbeRequest(
                type,
                "jdbc:postgresql://localhost/db",
                null,
                "u",
                "p",
                null,
                null);
    }
}

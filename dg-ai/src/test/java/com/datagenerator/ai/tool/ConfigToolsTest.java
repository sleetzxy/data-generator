package com.datagenerator.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagenerator.ai.client.DgWebClient;
import com.datagenerator.ai.client.DgWebClient.ConfigDetail;
import com.datagenerator.ai.client.DgWebClient.PreviewContentResult;
import com.datagenerator.ai.client.DgWebClient.PreviewTableSummary;
import com.datagenerator.ai.client.DgWebClient.ValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConfigToolsTest {

    @Mock private DgWebClient client;
    @Mock private ObjectProvider<HarnessAgent> harnessAgentProvider;
    @Mock private HarnessAgent harnessAgent;
    @Mock private WorkspaceManager workspaceManager;

    private ConfigTools tools;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private Path tempWorkspace;

    private static final String SAMPLE_YAML = """
            writer:
              type: csv
            tables:
              - name: users
                count: 10
                fields:
                  - name: id
                    type: integer
            """;

    @BeforeEach
    void setUp() throws Exception {
        tempWorkspace = Files.createTempDirectory("dg-ct-test-");
        when(harnessAgentProvider.getObject()).thenReturn(harnessAgent);
        when(harnessAgent.getWorkspaceManager()).thenReturn(workspaceManager);
        when(workspaceManager.getWorkspace()).thenReturn(tempWorkspace);

        doAnswer(inv -> {
            String path = inv.getArgument(1);
            String content = inv.getArgument(2);
            if (content == null) {
                return null;
            }
            Path target = tempWorkspace.resolve(path);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
            return null;
        }).when(workspaceManager).writeUtf8WorkspaceRelative(any(), anyString(), any());

        when(workspaceManager.readManagedWorkspaceFileUtf8(any(), anyString()))
                .thenAnswer(inv -> {
                    String path = inv.getArgument(1);
                    Path target = tempWorkspace.resolve(path);
                    if (Files.exists(target)) {
                        return Files.readString(target);
                    }
                    return null;
                });

        tools = new ConfigTools(Optional.of(client), harnessAgentProvider, objectMapper);
    }

    @Test
    void saveConfig_create_validYaml_persists() {
        when(client.validateYaml(anyString())).thenReturn(new ValidationResult(true, List.of()));
        when(client.createConfig(eq("测试"), anyString(), isNull()))
                .thenReturn(new ConfigDetail("t1", "测试", "t1", SAMPLE_YAML));

        String result = tools.saveConfig(SAMPLE_YAML, "测试", null, null);

        assertThat(result).contains("已保存");
        verify(client).createConfig(eq("测试"), anyString(), isNull());
    }

    @Test
    void previewData_ok_returnsStructuredJsonWithTables() throws Exception {
        when(client.previewByFileName("demo-job")).thenReturn(new PreviewContentResult(
                true,
                null,
                List.of(new PreviewTableSummary(
                        "users",
                        List.of("id"),
                        List.of(Map.of("id", 1))))));

        String result = extractToolText(tools.previewData("demo-job", null));
        JsonNode root = objectMapper.readTree(result);

        assertThat(root.path("ui").asText()).isEqualTo("table");
        assertThat(root.path("title").asText()).isEqualTo("样例预览");
        assertThat(root.path("message").asText()).contains("预览成功");
        assertThat(root.path("tables").isArray()).isTrue();
        assertThat(root.path("tables")).hasSize(1);
        assertThat(root.path("tables").get(0).path("tableName").asText()).isEqualTo("users");
        verify(client).previewByFileName("demo-job");
    }

    @Test
    void previewData_blankFileName_returnsGuidance() {
        String result = extractToolText(tools.previewData("  ", null));
        assertThat(result).contains("fileName");
        verify(client, never()).previewByFileName(anyString());
    }

    @Test
    void cleanYamlMarkdown_extractsCodeBlock() {
        String input = "说明\n```yaml\nid: demo\nname: test\n```\n";
        assertThat(ConfigTools.cleanYamlMarkdown(input)).contains("id: demo");
    }

    private static String extractToolText(ToolResultBlock block) {
        StringBuilder sb = new StringBuilder();
        for (var out : block.getOutput()) {
            if (out instanceof TextBlock tb && tb.getText() != null) {
                sb.append(tb.getText());
            }
        }
        return sb.toString();
    }
}

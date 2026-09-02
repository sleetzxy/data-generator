package com.datagenerator.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.datagenerator.ai.client.DgWebClient;
import com.datagenerator.ai.client.DgWebClient.ConfigDetail;
import com.datagenerator.ai.client.DgWebClient.ValidationResult;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    private ConfigDraftManager draftManager;
    private ConfigTools tools;
    private final RuntimeContext rc = RuntimeContext.empty();
    private Path tempWorkspace;

    @BeforeEach
    void setUp() throws Exception {
        tempWorkspace = Files.createTempDirectory("dg-ct-test-");
        when(harnessAgentProvider.getObject()).thenReturn(harnessAgent);
        when(harnessAgent.getWorkspaceManager()).thenReturn(workspaceManager);
        when(workspaceManager.getWorkspace()).thenReturn(tempWorkspace);

        doAnswer(inv -> {
            String path = inv.getArgument(1);
            String content = inv.getArgument(2);
            if (content == null) return null;
            Path target = tempWorkspace.resolve(path);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
            return null;
        }).when(workspaceManager).writeUtf8WorkspaceRelative(any(), anyString(), any());

        when(workspaceManager.readManagedWorkspaceFileUtf8(any(), anyString()))
                .thenAnswer(inv -> {
                    String path = inv.getArgument(1);
                    Path target = tempWorkspace.resolve(path);
                    if (Files.exists(target)) return Files.readString(target);
                    return null;
                });

        draftManager = new ConfigDraftManager(harnessAgentProvider);
        tools = new ConfigTools(Optional.of(client), harnessAgentProvider, draftManager);
    }

    @Test
    void startConfigDraft_and_saveConfigDraft_createsConfig() {
        String result = tools.startConfigDraft("test-job",
                "writer:\n  type: csv\n", null, rc);
        assertThat(result).contains("已创建");

        tools.addTableToDraft("test-job", """
            name: users
            count: 10
            fields:
              - name: id
                type: integer
            """, null, rc);

        when(client.validateYaml(anyString())).thenReturn(new ValidationResult(true, List.of()));
        when(client.getConfig("test-job")).thenReturn(null); // 新建
        when(client.createConfig(anyString(), anyString()))
                .thenReturn(new ConfigDetail("test-job", "测试", "test-job", "..."));

        // YAML 不写 id/name，显示名经 saveConfigDraft 的 displayName 字段提交
        String saveResult = tools.saveConfigDraft("test-job", "测试", null, rc);
        assertThat(saveResult).contains("已保存");
        verify(client).createConfig(eq("测试"), anyString());
    }

    @Test
    void startEditDraft_and_saveConfigDraft_updatesConfig() {
        String existingYaml = """
            writer:
              type: csv
            tables:
              - name: items
                count: 50
                fields:
                  - name: old_col
                    type: string
            """;
        when(client.getConfig("edit-job")).thenReturn(
                new ConfigDetail("edit-job", "编辑配置", "edit-job", existingYaml));

        String result = tools.startEditDraft("edit-job", null, rc);
        assertThat(result).contains("已加载配置");

        tools.updateTableInDraft("edit-job", "items", """
            name: items
            count: 100
            fields:
              - name: new_col
                type: integer
            """, null, rc);

        when(client.validateYaml(anyString())).thenReturn(new ValidationResult(true, List.of()));
        when(client.getConfig("edit-job")).thenReturn(
                new ConfigDetail("edit-job", "编辑配置", "edit-job", "...")); // 已存在 → 更新
        when(client.updateConfig(eq("edit-job"), anyString(), anyString()))
                .thenReturn(new ConfigDetail("edit-job", "编辑配置", "edit-job", "..."));

        // 省略 displayName → 沿用主表现有显示名
        String saveResult = tools.saveConfigDraft("edit-job", null, null, rc);
        assertThat(saveResult).contains("已更新");
        verify(client).updateConfig(eq("edit-job"), eq("编辑配置"), anyString());
    }

    @Test
    void saveConfigDraft_validationFailure_returnsErrors() {
        String header = "writer:\n  type: csv\n";
        tools.startConfigDraft("bad-job", header, null, rc);

        // 添加一个简单 table 通过预检，然后测试校验失败场景
        tools.addTableToDraft("bad-job", """
            name: test_table
            count: 10
            fields:
              - name: id
                type: integer
            """, null, rc);

        when(client.validateYaml(anyString()))
                .thenReturn(new ValidationResult(false, List.of("缺少 tables 字段")));

        String result = tools.saveConfigDraft("bad-job", null, null, rc);
        assertThat(result).contains("校验失败");
        assertThat(result).contains("缺少 tables 字段");
    }

    @Test
    void saveConfigDraft_createWithoutDisplayName_returnsGuidance() {
        tools.startConfigDraft("no-name-job", "writer:\n  type: csv\n", null, rc);
        tools.addTableToDraft("no-name-job", """
            name: test_table
            count: 10
            fields:
              - name: id
                type: integer
            """, null, rc);

        when(client.validateYaml(anyString())).thenReturn(new ValidationResult(true, List.of()));
        when(client.getConfig("no-name-job")).thenReturn(null); // 新建

        String result = tools.saveConfigDraft("no-name-job", null, null, rc);

        assertThat(result).contains("displayName");
        verify(client, never()).createConfig(anyString(), anyString());
    }

    @Test
    void saveConfigDraft_updateWithNewDisplayName_overridesExisting() {
        tools.startConfigDraft("rename-job", "writer:\n  type: csv\n", null, rc);
        tools.addTableToDraft("rename-job", """
            name: test_table
            count: 10
            fields:
              - name: id
                type: integer
            """, null, rc);

        when(client.validateYaml(anyString())).thenReturn(new ValidationResult(true, List.of()));
        when(client.getConfig("rename-job")).thenReturn(
                new ConfigDetail("rename-job", "旧名", "rename-job", "...")); // 已存在 → 更新
        when(client.updateConfig(eq("rename-job"), anyString(), anyString()))
                .thenReturn(new ConfigDetail("rename-job", "新显示名", "rename-job", "..."));

        String result = tools.saveConfigDraft("rename-job", "新显示名", null, rc);

        assertThat(result).contains("已更新");
        verify(client).updateConfig(eq("rename-job"), eq("新显示名"), anyString());
    }

    // 保留原有测试
    @Test
    void cleanYamlMarkdown_extractsCodeBlock() {
        String input = "说明\n```yaml\nid: demo\nname: test\n```\n";
        assertThat(ConfigTools.cleanYamlMarkdown(input)).contains("id: demo");
    }
}

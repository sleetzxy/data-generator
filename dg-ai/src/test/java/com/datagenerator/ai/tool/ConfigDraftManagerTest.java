package com.datagenerator.ai.tool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConfigDraftManagerTest {

    @Mock
    private ObjectProvider<HarnessAgent> harnessAgentProvider;

    @Mock
    private HarnessAgent harnessAgent;

    @Mock
    private WorkspaceManager workspaceManager;

    private ConfigDraftManager manager;
    private final RuntimeContext rc = RuntimeContext.empty();
    private Path tempWorkspace;

    @BeforeEach
    void setUp() throws Exception {
        tempWorkspace = Files.createTempDirectory("dg-draft-test-");
        when(harnessAgentProvider.getObject()).thenReturn(harnessAgent);
        when(harnessAgent.getWorkspaceManager()).thenReturn(workspaceManager);
        when(workspaceManager.getWorkspace()).thenReturn(tempWorkspace);

        // 配置 writeUtf8WorkspaceRelative 真正写入临时目录
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

        // 配置 readManagedWorkspaceFileUtf8 真正读取临时目录
        when(workspaceManager.readManagedWorkspaceFileUtf8(any(), anyString()))
                .thenAnswer(inv -> {
                    String path = inv.getArgument(1);
                    Path target = tempWorkspace.resolve(path);
                    if (Files.exists(target)) {
                        return Files.readString(target);
                    }
                    return null;
                });

        manager = new ConfigDraftManager(harnessAgentProvider);
    }

    @Test
    void createDraft_singleTable_mergesCorrectly() {
        String header = "id: test-job\nname: 测试\nwriter:\n  type: csv\n";
        manager.createDraft(rc, "test", header);

        String tableYaml = """
                name: users
                count: 100
                connection: pg-main
                fields:
                  - name: id
                    type: integer
                    strategy: sequence
                  - name: username
                    type: string
                    strategy: random
                """;
        manager.appendTable(rc, "test", tableYaml);

        String merged = manager.mergeToYaml(rc, "test");
        assertThat(merged).contains("id: test-job");
        assertThat(merged).contains("name: 测试");
        assertThat(merged).contains("name: users");
        assertThat(merged).contains("count: 100");
        assertThat(merged).contains("name: id");
        assertThat(merged).contains("name: username");
        // 验证输出为 schema 嵌套格式
        assertThat(merged).contains("schema:");
        assertThat(merged).containsPattern("fields:");
    }

    @Test
    void appendTableMeta_thenAppendFields_multipleBatches_mergesCorrectly() {
        String header = "id: big-job\nname: 大配置\n";
        manager.createDraft(rc, "big", header);

        String metaYaml = """
                name: products
                count: 1000
                connection: pg-main
                """;
        String tableName = manager.appendTableMeta(rc, "big", metaYaml);
        assertThat(tableName).isEqualTo("products");

        var r1 = manager.appendFields(rc, "big", "products", """
                fields:
                  - name: id
                    type: integer
                    strategy: sequence
                  - name: title
                    type: string
                    strategy: random
                """);
        assertThat(r1.batchCount()).isEqualTo(2);

        var r2 = manager.appendFields(rc, "big", "products", """
                fields:
                  - name: price
                    type: decimal
                    strategy: random
                  - name: stock
                    type: integer
                    strategy: sequence
                """);
        assertThat(r2.batchCount()).isEqualTo(2);
        assertThat(r2.cumulativeCount()).isEqualTo(4); // 2 + 2 = 4

        String merged = manager.mergeToYaml(rc, "big");
        assertThat(merged).contains("name: id");
        assertThat(merged).contains("name: title");
        assertThat(merged).contains("name: price");
        assertThat(merged).contains("name: stock");
    }

    @Test
    void updateTable_replacesExistingContent() {
        manager.createDraft(rc, "edit", "id: edit-job\nname: 编辑测试\n");

        String original = """
                name: items
                count: 50
                fields:
                  - name: old_col
                    type: string
                """;
        manager.appendTable(rc, "edit", original);

        String updated = """
                name: items
                count: 200
                fields:
                  - name: new_col
                    type: integer
                """;
        manager.updateTable(rc, "edit", "items", updated);

        String merged = manager.mergeToYaml(rc, "edit");
        assertThat(merged).contains("new_col");
        assertThat(merged).doesNotContain("old_col");
        assertThat(merged).contains("count: 200");
    }

    @Test
    void removeTable_deletesTableFromMerge() {
        manager.createDraft(rc, "rm", "id: rm-job\nname: 删除测试\n");

        manager.appendTable(rc, "rm", """
                name: t1
                count: 10
                fields:
                  - name: c1
                    type: string
                """);
        manager.appendTable(rc, "rm", """
                name: t2
                count: 20
                fields:
                  - name: c2
                    type: integer
                """);

        manager.removeTable(rc, "rm", "t1");

        String merged = manager.mergeToYaml(rc, "rm");
        assertThat(merged).doesNotContain("name: t1");
        assertThat(merged).contains("name: t2");
    }

    @Test
    void setConstraintsAndSeeds_appearInMergedYaml() {
        manager.createDraft(rc, "cs", "id: cs-job\nname: 约束种子测试\n");

        manager.setConstraints(rc, "cs", """
                - type: unique
                  fields: [id]
                """);

        manager.setSeeds(rc, "cs", """
                - table: users
                  count: 10
                """);

        String merged = manager.mergeToYaml(rc, "cs");
        assertThat(merged).contains("type: unique");
        assertThat(merged).contains("table: users");
    }

    @Test
    void loadExistingAsDraft_schemaFormat_producesMergeableResult() {
        // 标准格式：fields 嵌套在 schema 下（已有配置实际存储格式）
        String fullYaml = """
                id: existing-job
                name: 已有配置
                writer:
                  type: csv
                  output: ./data
                tables:
                  - name: customers
                    count: 500
                    connection: pg-main
                    schema:
                      table: app_customers
                      fields:
                        - name: id
                          type: integer
                          strategy: sequence
                        - name: email
                          type: string
                          strategy: email
                constraints:
                  - type: not-null
                    column: id
                """;

        manager.loadExistingAsDraft(rc, "existing", fullYaml);

        String merged = manager.mergeToYaml(rc, "existing");
        assertThat(merged).contains("id: existing-job");
        assertThat(merged).contains("name: customers");
        assertThat(merged).contains("strategy: email");
        assertThat(merged).contains("type: not-null");
        // 验证 schema 嵌套结构
        assertThat(merged).contains("schema:");
        assertThat(merged).contains("table: app_customers");
        assertThat(merged).contains("fields:");
    }

    @Test
    void appendTable_duplicateName_throwsException() {
        manager.createDraft(rc, "dup", "id: dup\nname: 重复测试\n");
        String tableYaml = "name: t1\ncount: 10\nfields:\n  - name: c1\n    type: string\n";
        manager.appendTable(rc, "dup", tableYaml);

        assertThatThrownBy(() -> manager.appendTable(rc, "dup", tableYaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    void appendFields_tableNotExists_throwsException() {
        manager.createDraft(rc, "ne", "id: ne\nname: 不存在\n");

        assertThatThrownBy(() -> manager.appendFields(rc, "ne", "ghost", "fields: []"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void mergeToYaml_emptyDraft_throwsException() {
        assertThatThrownBy(() -> manager.mergeToYaml(rc, "empty"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("header.yaml");
    }

    @Test
    void createDraft_emptyHeader_throwsException() {
        assertThatThrownBy(() -> manager.createDraft(rc, "bad", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void draftExists_returnsCorrectly() {
        assertThat(manager.draftExists(rc, "nonexistent")).isFalse();
        manager.createDraft(rc, "exists", "id: test\nname: Test\n");
        assertThat(manager.draftExists(rc, "exists")).isTrue();
        manager.deleteDraft(rc, "exists");
        assertThat(manager.draftExists(rc, "exists")).isFalse();
    }

    @Test
    void listTableNames_returnsCorrectNames() {
        manager.createDraft(rc, "list", "id: list\nname: 列表测试\n");
        manager.appendTable(rc, "list",
                "name: aaa\ncount: 1\nfields:\n  - name: x\n    type: string\n");
        manager.appendTable(rc, "list",
                "name: bbb\ncount: 2\nfields:\n  - name: y\n    type: int\n");

        var names = manager.listTableNames(rc, "list");
        assertThat(names).containsExactly("aaa", "bbb");
    }
}

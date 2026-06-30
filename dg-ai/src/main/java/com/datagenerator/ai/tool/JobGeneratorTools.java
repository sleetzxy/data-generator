package com.datagenerator.ai.tool;

import com.datagenerator.ai.agent.runtime.JobSessionState;
import com.datagenerator.ai.application.session.AgentSession;
import com.datagenerator.ai.application.session.AgentSessionRegistry;
import com.datagenerator.ai.tool.model.DgWebModels.CreateJobRequest;
import com.datagenerator.ai.tool.model.DgWebModels.JobDetail;
import com.datagenerator.ai.tool.model.DgWebModels.JobSummary;
import com.datagenerator.ai.tool.model.DgWebModels.UpdateJobRequest;
import com.datagenerator.ai.tool.model.DgWebModels.ValidationResult;
import com.datagenerator.ai.tool.web.DataGeneratorWebClient;
import com.datagenerator.ai.util.JobYamlRootEditor;
import com.datagenerator.ai.agent.result.JobResultProcessor;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import java.util.List;

public class JobGeneratorTools {

    private final DataGeneratorWebClient webClient;
    private final AgentSessionRegistry sessionRegistry;
    private final JobResultProcessor resultProcessor;

    public JobGeneratorTools(DataGeneratorWebClient webClient, AgentSessionRegistry sessionRegistry,
                             JobResultProcessor resultProcessor) {
        this.webClient = webClient;
        this.sessionRegistry = sessionRegistry;
        this.resultProcessor = resultProcessor;
    }

    @Tool("列出可用的数据连接名称与类型，不含 url 和密码")
    public List<com.datagenerator.ai.tool.model.DgWebModels.ConnectionInfo> listConnections() {
        return webClient.listConnections();
    }

    @Tool("列出已有 Job 定义的 id、名称与文件名（fileName 用于 getJobYaml）")
    public List<JobSummary> listJobDefinitions() {
        return webClient.listJobs();
    }

    @Tool("读取已有 Job 的完整 YAML 摘要；fileName 可为 listJobDefinitions 的 fileName、id 或展示名 name；完整 YAML 缓存于会话。复制 Job 请用 copyJobYamlToDraft")
    public String getJobYaml(@ToolMemoryId String sessionId, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "错误：fileName 不能为空";
        }
        JobDetail detail = resolveJob(fileName);
        if (detail == null) {
            return "未找到 Job「"
                    + fileName
                    + "」。请先 listJobDefinitions，使用 fileName（也可传 id 或展示名 name）";
        }
        AgentSession session = requireSession(sessionId);
        JobSessionState.putReferenceYaml(session, detail.fileName(), detail.yaml());
        return resultProcessor.summarizeReferenceJob(detail.fileName(), detail.yaml());
    }

    @Tool("将已有 Job 复制为本会话草稿（服务端读取完整 YAML，不占用对话 token）；sourceFileName 可为 fileName、id 或展示名；newId/newDisplayName 可选")
    public String copyJobYamlToDraft(
            @ToolMemoryId String sessionId,
            String sourceFileName,
            String newId,
            String newDisplayName) {
        if (sourceFileName == null || sourceFileName.isBlank()) {
            return "错误：sourceFileName 不能为空";
        }
        JobDetail detail = resolveJob(sourceFileName);
        if (detail == null) {
            return "未找到 Job「"
                    + sourceFileName
                    + "」。请先 listJobDefinitions，使用 fileName（也可传 id 或展示名 name）";
        }
        AgentSession session = requireSession(sessionId);
        String yaml = JobYamlRootEditor.removeRootKeys(detail.yaml(), "id", "name", "schedule");
        if (newId != null && !newId.isBlank()) {
            yaml = JobYamlRootEditor.setRootKey(yaml, "id", newId.trim());
        }
        if (newDisplayName != null && !newDisplayName.isBlank()) {
            yaml = JobYamlRootEditor.setRootKey(yaml, "name", newDisplayName.trim());
        }
        JobSessionState.putReferenceYaml(session, detail.fileName(), detail.yaml());
        JobSessionState.setDraftYaml(session, yaml);
        JobSessionState.setDraftIncomplete(session, false);
        ValidationResult validation = webClient.validateYaml(yaml);
        JobSessionState.setDraftValidated(session, validation.valid());
        return resultProcessor.summarizeDraftStored(yaml, false);
    }

    @Tool("校验当前会话已收敛的 Job YAML 草稿；长 YAML 生成后优先使用此工具，勿在 validateJobYaml 参数中传入整段 YAML")
    public ValidationResult validateDraftJobYaml(@ToolMemoryId String sessionId) {
        String yaml = requireDraftYaml(sessionId);
        return webClient.validateYaml(yaml);
    }

    @Tool("新建 Job 定义到 Web 控制台；用户说「添加/保存/新建到 job」时调用，编辑已有 Job 请用 updateDraftJobDefinition")
    public JobDetail saveDraftJobDefinition(
            @ToolMemoryId String sessionId,
            String displayName,
            String fileName) {
        AgentSession session = requireSession(sessionId);
        String yaml = JobSessionState.draftYaml(session);
        if (yaml == null || yaml.isBlank()) {
            throw new IllegalStateException(
                    "当前会话无已校验草稿 YAML，请先生成配置并在回复末尾输出 YAML 代码块");
        }
        ValidationResult validation = webClient.validateYaml(yaml);
        if (!validation.valid()) {
            throw new IllegalStateException("草稿 YAML 校验未通过: " + String.join("; ", validation.errors()));
        }
        JobDetail created = webClient.createJob(new CreateJobRequest(fileName, displayName, yaml));
        JobSessionState.markDraftPersistedInTurn(session);
        return created;
    }

    @Tool("更新已有 Job 定义（覆盖写）；将当前会话草稿 YAML 保存到指定 fileName 的 Job；用户说「编辑/更新/修改 job」时必须调用")
    public JobDetail updateDraftJobDefinition(
            @ToolMemoryId String sessionId,
            String fileName,
            String displayName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalStateException("fileName 不能为空，请指定要更新的 Job 文件名");
        }
        AgentSession session = requireSession(sessionId);
        String yaml = JobSessionState.draftYaml(session);
        if (yaml == null || yaml.isBlank()) {
            throw new IllegalStateException(
                    "当前会话无已校验草稿 YAML，请先生成配置或在回复末尾输出 YAML 代码块。" +
                            "如需编辑已有 Job，请先调用 copyJobYamlToDraft 载入草稿");
        }
        ValidationResult validation = webClient.validateYaml(yaml);
        if (!validation.valid()) {
            throw new IllegalStateException("草稿 YAML 校验未通过: " + String.join("; ", validation.errors()));
        }
        JobDetail updated = webClient.updateJob(fileName, new UpdateJobRequest(displayName, yaml));
        JobSessionState.markDraftPersistedInTurn(session);
        return updated;
    }

    @Tool("删除自定义 Job 定义；内置 Job 不可删除")
    public String deleteJobDefinition(String fileName) {
        webClient.deleteJob(fileName);
        return "已删除 Job: " + fileName;
    }

    @Tool("查询 Job 定义的调度配置")
    public com.datagenerator.ai.tool.model.DgWebModels.JobSchedule getJobDefinitionSchedule(String fileName) {
        return webClient.getSchedule(fileName);
    }

    @Tool("列出可用的 Schema 配置文件名")
    public List<String> listSchemas() {
        return webClient.listSchemas();
    }

    @Tool("读取 Schema 详情，含字段名、类型与 generator 配置；完整结构缓存于会话，对话记忆仅保留摘要")
    public String getSchema(@ToolMemoryId String sessionId, String name) {
        com.datagenerator.ai.tool.model.DgWebModels.SchemaDetail detail = webClient.getSchema(name);
        sessionRegistry.find(sessionId)
                .ifPresent(session -> JobSessionState.putReferenceSchema(session, name, detail));
        return resultProcessor.summarizeSchema(name, detail);
    }

    @Tool("预览当前会话 Job YAML 草稿的生成结果（不写库）；limitPerTable 为每张表采样行数，tableNames 为空则预览全部表")
    public String previewDraftJobYaml(
            @ToolMemoryId String sessionId,
            int limitPerTable,
            List<String> tableNames) {
        return resultProcessor.summarizePreviewResult(
                webClient.preview(requireDraftYaml(sessionId), limitPerTable, tableNames));
    }

    @Tool("分页查询已提交的运行任务列表")
    public com.datagenerator.ai.tool.model.DgWebModels.JobListPage listSubmittedJobs(int page, int size) {
        return webClient.listSubmittedJobs(page, size);
    }

    @Tool("查询运行任务状态与进度")
    public com.datagenerator.ai.tool.model.DgWebModels.JobStatus getSubmittedJob(String jobId) {
        return webClient.getSubmittedJob(jobId);
    }

    @Tool("读取运行任务日志")
    public List<com.datagenerator.ai.tool.model.DgWebModels.JobLogEntry> getSubmittedJobLogs(String jobId) {
        return webClient.getSubmittedJobLogs(jobId);
    }

    @Tool("取消进行中的运行任务")
    public String cancelSubmittedJob(String jobId) {
        webClient.cancelSubmittedJob(jobId);
        return "已取消任务: " + jobId;
    }

    private String requireDraftYaml(String sessionId) {
        AgentSession session = sessionRegistry.find(sessionId).orElse(null);
        String yaml = session == null ? null : JobSessionState.draftYaml(session);
        if (yaml == null || yaml.isBlank()) {
            throw new IllegalStateException(
                    "当前会话无 YAML 草稿，请先在回复末尾输出 YAML 代码块，或调用 copyJobYamlToDraft");
        }
        return yaml;
    }

    private AgentSession requireSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalStateException("Tool 会话 ID 未注入，请检查 @ToolMemoryId 配置");
        }
        return sessionRegistry.find(sessionId)
                .orElseThrow(() -> new IllegalStateException("会话不存在: " + sessionId));
    }

    private JobDetail resolveJob(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return null;
        }
        String trimmed = identifier.trim();
        JobDetail direct = webClient.findJob(trimmed);
        if (direct != null) {
            return direct;
        }
        if (trimmed.endsWith(".yaml") || trimmed.endsWith(".yml")) {
            direct = webClient.findJob(trimmed.replaceFirst("\\.ya?ml$", ""));
            if (direct != null) {
                return direct;
            }
        }
        for (JobSummary summary : webClient.listJobs()) {
            if (trimmed.equals(summary.fileName())
                    || trimmed.equals(summary.id())
                    || trimmed.equals(summary.name())) {
                return webClient.findJob(summary.fileName());
            }
        }
        return null;
    }
}

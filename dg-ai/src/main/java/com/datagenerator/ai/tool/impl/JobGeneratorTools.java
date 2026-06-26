package com.datagenerator.ai.tool.impl;

import com.datagenerator.ai.agent.runtime.JobGeneratorMemoryCompressor;
import com.datagenerator.ai.application.session.AgentSession;
import com.datagenerator.ai.application.session.AgentSessionRegistry;
import com.datagenerator.ai.tool.definition.JobGeneratorToolSet;
import com.datagenerator.ai.tool.impl.model.DgWebModels.CreateJobRequest;
import com.datagenerator.ai.tool.impl.model.DgWebModels.JobDetail;
import com.datagenerator.ai.tool.impl.model.DgWebModels.JobSummary;
import com.datagenerator.ai.tool.impl.model.DgWebModels.UpdateJobRequest;
import com.datagenerator.ai.tool.impl.model.DgWebModels.ValidationResult;
import com.datagenerator.ai.tool.impl.web.DataGeneratorWebClient;
import com.datagenerator.ai.util.JobYamlRootEditor;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import java.util.List;

public class JobGeneratorTools implements JobGeneratorToolSet {

    private final DataGeneratorWebClient webClient;
    private final AgentSessionRegistry sessionRegistry;
    private final JobGeneratorMemoryCompressor compressor;

    public JobGeneratorTools(DataGeneratorWebClient webClient, AgentSessionRegistry sessionRegistry,
                             JobGeneratorMemoryCompressor compressor) {
        this.webClient = webClient;
        this.sessionRegistry = sessionRegistry;
        this.compressor = compressor;
    }

    @Tool("列出可用的数据连接名称与类型，不含 url 和密码")
    public List<com.datagenerator.ai.tool.impl.model.DgWebModels.ConnectionInfo> listConnections() {
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
        session.putReferenceYaml(detail.fileName(), detail.yaml());
        return compressor.summarizeReferenceJob(detail.fileName(), detail.yaml());
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
        session.putReferenceYaml(detail.fileName(), detail.yaml());
        session.setDraftYaml(yaml);
        session.setDraftIncomplete(false);
        ValidationResult validation = webClient.validateYaml(yaml);
        session.setDraftValidated(validation.valid());
        return compressor.summarizeDraftStored(yaml, false);
    }

    @Tool("校验 Job YAML（仅适用于短 YAML，约 6KB 以内）；长 YAML 请先通过结构化 JSON 写入 draftYaml，再调用 validateDraftJobYaml")
    public ValidationResult validateJobYaml(String yaml) {
        return webClient.validateYaml(yaml);
    }

    @Tool("校验当前会话已提取的 Job YAML 草稿；长 YAML 生成后优先使用此工具，勿在 validateJobYaml 参数中传入整段 YAML")
    public ValidationResult validateDraftJobYaml(@ToolMemoryId String sessionId) {
        String yaml = requireDraftYaml(sessionId);
        return webClient.validateYaml(yaml);
    }

    @Tool("创建自定义 Job 定义并持久化（仅短 content，约 6KB 以内）；长 YAML 请用 saveDraftJobDefinition")
    public JobDetail createJobDefinition(String displayName, String content, String name) {
        return webClient.createJob(new CreateJobRequest(name, displayName, content));
    }

    @Tool("将本会话已校验通过的 Job YAML 保存到 Web 控制台；用户说「添加/保存到 job」时必须调用")
    public JobDetail saveDraftJobDefinition(
            @ToolMemoryId String sessionId,
            String displayName,
            String fileName) {
        String yaml = sessionRegistry.getDraftYaml(sessionId)
                .orElseThrow(() -> new IllegalStateException(
                        "当前会话无已校验草稿 YAML，请先生成配置（输出结构化 JSON 的 draftYaml）"));
        ValidationResult validation = webClient.validateYaml(yaml);
        if (!validation.valid()) {
            throw new IllegalStateException("草稿 YAML 校验未通过: " + String.join("; ", validation.errors()));
        }
        JobDetail created = webClient.createJob(new CreateJobRequest(fileName, displayName, yaml));
        requireSession(sessionId).markDraftPersistedInTurn();
        return created;
    }

    @Tool("更新已有自定义 Job 定义（仅短 content，约 6KB 以内）；长 YAML 请先写入 draftYaml 再 saveDraftJobDefinition")
    public JobDetail updateJobDefinition(String fileName, String displayName, String content) {
        return webClient.updateJob(fileName, new UpdateJobRequest(displayName, content));
    }

    @Tool("删除自定义 Job 定义；内置 Job 不可删除")
    public String deleteJobDefinition(String fileName) {
        webClient.deleteJob(fileName);
        return "已删除 Job: " + fileName;
    }

    @Tool("查询 Job 定义的调度配置")
    public com.datagenerator.ai.tool.impl.model.DgWebModels.JobSchedule getJobDefinitionSchedule(String fileName) {
        return webClient.getSchedule(fileName);
    }

    @Tool("列出可用的 Schema 配置文件名")
    public List<String> listSchemas() {
        return webClient.listSchemas();
    }

    @Tool("读取 Schema 详情，含字段名、类型与 generator 配置；完整结构缓存于会话，对话记忆仅保留摘要")
    public String getSchema(@ToolMemoryId String sessionId, String name) {
        com.datagenerator.ai.tool.impl.model.DgWebModels.SchemaDetail detail = webClient.getSchema(name);
        sessionRegistry.find(sessionId).ifPresent(session -> session.putReferenceSchema(name, detail));
        return compressor.summarizeSchema(name, detail);
    }

    @Tool("预览 Job YAML 生成结果（不写库）；yaml 较长时请改用 previewDraftJobYaml")
    public String previewJobYaml(String yaml, int limitPerTable, List<String> tableNames) {
        return compressor.summarizePreviewResult(
                webClient.preview(yaml, limitPerTable, tableNames));
    }

    @Tool("预览当前会话 Job YAML 草稿的生成结果（不写库）；limitPerTable 为每张表采样行数，tableNames 为空则预览全部表")
    public String previewDraftJobYaml(
            @ToolMemoryId String sessionId,
            int limitPerTable,
            List<String> tableNames) {
        return compressor.summarizePreviewResult(
                webClient.preview(requireDraftYaml(sessionId), limitPerTable, tableNames));
    }

    @Tool("分页查询已提交的运行任务列表")
    public com.datagenerator.ai.tool.impl.model.DgWebModels.JobListPage listSubmittedJobs(int page, int size) {
        return webClient.listSubmittedJobs(page, size);
    }

    @Tool("提交短 Job YAML 执行造数任务（约 6KB 以内）；长 YAML 请用 submitDraftJob")
    public com.datagenerator.ai.tool.impl.model.DgWebModels.JobStatus submitJob(String yaml) {
        return webClient.submitJob(yaml);
    }

    @Tool("提交当前会话 Job YAML 草稿执行造数任务；长 YAML 生成后优先使用此工具")
    public com.datagenerator.ai.tool.impl.model.DgWebModels.JobStatus submitDraftJob(@ToolMemoryId String sessionId) {
        return webClient.submitJob(requireDraftYaml(sessionId));
    }

    @Tool("查询运行任务状态与进度")
    public com.datagenerator.ai.tool.impl.model.DgWebModels.JobStatus getSubmittedJob(String jobId) {
        return webClient.getSubmittedJob(jobId);
    }

    @Tool("读取运行任务日志")
    public List<com.datagenerator.ai.tool.impl.model.DgWebModels.JobLogEntry> getSubmittedJobLogs(String jobId) {
        return webClient.getSubmittedJobLogs(jobId);
    }

    @Tool("取消进行中的运行任务")
    public String cancelSubmittedJob(String jobId) {
        webClient.cancelSubmittedJob(jobId);
        return "已取消任务: " + jobId;
    }

    private String requireDraftYaml(String sessionId) {
        return sessionRegistry.getDraftYaml(sessionId)
                .orElseThrow(() -> new IllegalStateException(
                        "当前会话无 YAML 草稿，请先在结构化 JSON 中输出 draftYaml，或调用 copyJobYamlToDraft"));
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

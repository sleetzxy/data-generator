package com.datagenerator.ai.tool.generatejob;

import com.datagenerator.ai.port.ConnectionCatalogPort;
import com.datagenerator.ai.port.JobDefinitionPort;
import com.datagenerator.ai.port.JobExecutionPort;
import com.datagenerator.ai.port.JobPreviewPort;
import com.datagenerator.ai.port.SchemaCatalogPort;
import com.datagenerator.ai.session.AgentSessionRegistry;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.service.MemoryId;
import java.util.List;

public class JobGeneratorTools {

    private final ConnectionCatalogPort connections;
    private final JobDefinitionPort jobDefinitions;
    private final SchemaCatalogPort schemas;
    private final JobPreviewPort preview;
    private final JobExecutionPort jobExecution;
    private final AgentSessionRegistry sessionRegistry;

    public JobGeneratorTools(
            ConnectionCatalogPort connections,
            JobDefinitionPort jobDefinitions,
            SchemaCatalogPort schemas,
            JobPreviewPort preview,
            JobExecutionPort jobExecution,
            AgentSessionRegistry sessionRegistry) {
        this.connections = connections;
        this.jobDefinitions = jobDefinitions;
        this.schemas = schemas;
        this.preview = preview;
        this.jobExecution = jobExecution;
        this.sessionRegistry = sessionRegistry;
    }

    @Tool("列出可用的数据连接名称与类型，不含 url 和密码")
    public List<ConnectionCatalogPort.ConnectionInfo> listConnections() {
        return connections.listConnections();
    }

    @Tool("列出已有 Job 定义的 id、名称与文件名（fileName 用于 getJobYaml）")
    public List<JobDefinitionPort.JobSummary> listJobDefinitions() {
        return jobDefinitions.listJobs();
    }

    @Tool("读取已有 Job 的完整 YAML，用于参考 writer、seeds、tables 等；fileName 为 listJobDefinitions 返回的文件名")
    public String getJobYaml(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "错误：fileName 不能为空";
        }
        JobDefinitionPort.JobDetail detail = jobDefinitions.findJob(fileName.trim());
        if (detail == null) {
            return "未找到 Job: " + fileName + "，请先 listJobDefinitions 确认文件名";
        }
        return detail.yaml();
    }

    @Tool("校验 Job YAML（仅适用于短 YAML，约 6KB 以内）；长 YAML 请先输出到 dg-artifact 块，再调用 validateDraftJobYaml")
    public JobDefinitionPort.ValidationResult validateJobYaml(String yaml) {
        return jobDefinitions.validateYaml(yaml);
    }

    @Tool("校验当前会话已提取的 Job YAML 草稿；长 YAML 生成后优先使用此工具，勿在 validateJobYaml 参数中传入整段 YAML")
    public JobDefinitionPort.ValidationResult validateDraftJobYaml(@MemoryId String sessionId) {
        String yaml = requireDraftYaml(sessionId);
        return jobDefinitions.validateYaml(yaml);
    }

    @Tool("创建自定义 Job 定义并持久化；name 为文件名（可选），displayName 写入 YAML name 字段")
    public JobDefinitionPort.JobDetail createJobDefinition(String displayName, String content, String name) {
        return jobDefinitions.createJob(new JobDefinitionPort.CreateJobRequest(name, displayName, content));
    }

    @Tool("将本会话已校验通过的 Job YAML 保存到 Web 控制台；用户说「添加/保存到 job」时必须调用")
    public JobDefinitionPort.JobDetail saveDraftJobDefinition(
            @MemoryId String sessionId,
            String displayName,
            String fileName) {
        String yaml = sessionRegistry.getDraftYaml(sessionId)
                .orElseThrow(() -> new IllegalStateException(
                        "当前会话无已校验草稿 YAML，请先生成配置（输出 dg-artifact 块）"));
        JobDefinitionPort.ValidationResult validation = jobDefinitions.validateYaml(yaml);
        if (!validation.valid()) {
            throw new IllegalStateException("草稿 YAML 校验未通过: " + String.join("; ", validation.errors()));
        }
        return jobDefinitions.createJob(new JobDefinitionPort.CreateJobRequest(fileName, displayName, yaml));
    }

    @Tool("更新已有自定义 Job 定义的 YAML 内容；fileName 为 listJobDefinitions 返回的文件名")
    public JobDefinitionPort.JobDetail updateJobDefinition(String fileName, String displayName, String content) {
        return jobDefinitions.updateJob(
                fileName,
                new JobDefinitionPort.UpdateJobRequest(displayName, content));
    }

    @Tool("删除自定义 Job 定义；内置 Job 不可删除")
    public String deleteJobDefinition(String fileName) {
        jobDefinitions.deleteJob(fileName);
        return "已删除 Job: " + fileName;
    }

    @Tool("查询 Job 定义的调度配置")
    public JobDefinitionPort.JobSchedule getJobDefinitionSchedule(String fileName) {
        return jobDefinitions.getSchedule(fileName);
    }

    @Tool("列出可用的 Schema 配置文件名")
    public List<String> listSchemas() {
        return schemas.listSchemas();
    }

    @Tool("读取 Schema 详情，含字段名、类型与 generator 配置，用于编写 tables.schema")
    public SchemaCatalogPort.SchemaDetail getSchema(String name) {
        return schemas.getSchema(name);
    }

    @Tool("预览 Job YAML 生成结果（不写库）；yaml 较长时请改用 previewDraftJobYaml")
    public JobPreviewPort.PreviewResult previewJobYaml(String yaml, int limitPerTable, List<String> tableNames) {
        return preview.preview(yaml, limitPerTable, tableNames);
    }

    @Tool("预览当前会话 Job YAML 草稿的生成结果（不写库）；limitPerTable 为每张表采样行数，tableNames 为空则预览全部表")
    public JobPreviewPort.PreviewResult previewDraftJobYaml(
            @MemoryId String sessionId,
            int limitPerTable,
            List<String> tableNames) {
        return preview.preview(requireDraftYaml(sessionId), limitPerTable, tableNames);
    }

    @Tool("分页查询已提交的运行任务列表")
    public JobExecutionPort.JobListPage listSubmittedJobs(int page, int size) {
        return jobExecution.listJobs(page, size);
    }

    @Tool("提交 Job YAML 执行造数任务")
    public JobExecutionPort.JobStatus submitJob(String yaml) {
        return jobExecution.submitJob(yaml);
    }

    @Tool("查询运行任务状态与进度")
    public JobExecutionPort.JobStatus getSubmittedJob(String jobId) {
        return jobExecution.getJob(jobId);
    }

    @Tool("读取运行任务日志")
    public List<JobExecutionPort.JobLogEntry> getSubmittedJobLogs(String jobId) {
        return jobExecution.getJobLogs(jobId);
    }

    @Tool("取消进行中的运行任务")
    public String cancelSubmittedJob(String jobId) {
        jobExecution.cancelJob(jobId);
        return "已取消任务: " + jobId;
    }

    private String requireDraftYaml(String sessionId) {
        return sessionRegistry.getDraftYaml(sessionId)
                .orElseThrow(() -> new IllegalStateException(
                        "当前会话无 YAML 草稿，请先在回复中输出 <!-- dg-artifact:yaml --> 块"));
    }
}

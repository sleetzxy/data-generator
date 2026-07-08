package com.datagenerator.ai.controller;

import com.datagenerator.ai.config.AiProperties;
import com.datagenerator.ai.config.AiProperties.EmbeddingProperties;
import com.datagenerator.ai.dto.ApiResponse;
import com.datagenerator.ai.service.KnowledgeChunkService;
import com.datagenerator.ai.service.KnowledgeChunkService.ChunkInfo;
import com.datagenerator.ai.service.KnowledgeChunkService.IndexData;
import com.datagenerator.ai.service.KnowledgeChunkService.IndexEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.reader.ReaderInput;
import io.agentscope.core.rag.reader.TextReader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库管理 API — 文档上传、切分、向量索引与检索。
 */
@Tag(name = "知识库管理", description = "文档上传、切分、向量索引与检索")
@RestController
@RequestMapping("/api/v1/agent/knowledge")
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);

    private final Knowledge knowledge;
    private final KnowledgeChunkService chunkService;
    private final ObjectMapper mapper;
    private final Path storagePath;
    private final EmbeddingProperties embeddingProps;
    private final AtomicInteger chunkCount = new AtomicInteger(0);
    private volatile boolean indexed = false;

    public KnowledgeController(Knowledge knowledge, KnowledgeChunkService chunkService,
            ObjectMapper mapper, AiProperties aiProperties) {
        this.knowledge = knowledge;
        this.chunkService = chunkService;
        this.mapper = mapper;
        this.embeddingProps = aiProperties.getEmbedding();
        this.storagePath = Path.of(embeddingProps.storagePath());
    }

    // ==================== 上传切分 ====================

    @Operation(summary = "上传 Markdown 文档并自动切分",
            description = "上传 .md 文件，按 ## 二级标题自动切分为多个 chunk，存入配置的 storage-path 目录。重复上传会覆盖已有文件。")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> upload(
            @Parameter(description = "Markdown 文档（.md），按 ## 二级标题切分")
            @RequestParam("file") MultipartFile file) {

        if (file.isEmpty()) {
            return badRequest("EMPTY_FILE", "上传的文件为空");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.endsWith(".md")) {
            return badRequest("INVALID_FORMAT", "仅支持 .md 格式文件");
        }

        try {
            String markdown = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
            List<ChunkInfo> chunks = chunkService.splitMarkdown(markdown);

            if (chunks.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.success(Map.of(
                        "chunkCount", 0,
                        "message", "未检测到 ## 二级标题，无法切分"
                )));
            }

            chunkService.saveChunks(chunks, storagePath);

            List<Map<String, String>> chunkList = chunks.stream()
                    .map(c -> {
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("id", c.id());
                        m.put("title", c.title());
                        m.put("file", c.file());
                        return m;
                    })
                    .toList();

            indexed = false;

            log.info("文档上传切分完成: {} → {} 个 chunk", originalName, chunks.size());
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "chunkCount", chunks.size(),
                    "storagePath", storagePath.toString(),
                    "chunks", chunkList
            )));
        } catch (IOException e) {
            log.error("文件保存失败", e);
            return internalError("SAVE_FAILED", "文件保存失败: " + e.getMessage());
        }
    }

    // ==================== 向量索引 ====================

    @Operation(summary = "构建向量索引",
            description = "读取 storage-path 下已切分的 chunk 文件，通过 Ollama embedding 模型向量化后写入内存向量库。")
    @PostMapping("/index")
    public ResponseEntity<ApiResponse<Map<String, Object>>> index(
            @Parameter(description = "是否强制重建索引（忽略已索引状态）")
            @RequestParam(defaultValue = "false") boolean force) {

        if (indexed && !force) {
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "chunkCount", chunkCount.get(),
                    "status", "already_indexed"
            )));
        }

        try {
            log.info("开始知识库索引: storagePath={}, force={}", storagePath, force);
            IndexData indexData = chunkService.loadIndex(storagePath);

            if (indexData.chunks().isEmpty()) {
                return ResponseEntity.ok(ApiResponse.success(Map.of(
                        "chunkCount", 0,
                        "status", "no_chunks",
                        "hint", "请先通过 POST /upload 上传文档切分"
                )));
            }

            TextReader reader = new TextReader();
            List<Document> allDocs = new ArrayList<>();

            for (IndexEntry entry : indexData.chunks()) {
                String content = chunkService.loadChunkContent(storagePath, entry.file());
                if (content == null) {
                    log.warn("跳过不存在的 chunk: {}", entry.file());
                    continue;
                }
                List<Document> docs = reader.read(ReaderInput.fromString(content)).block();
                if (docs != null) {
                    allDocs.addAll(docs);
                }
                log.debug("已加载 chunk: {} ({})", entry.id(), entry.title());
            }

            if (allDocs.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.success(Map.of(
                        "chunkCount", 0,
                        "status", "no_content"
                )));
            }

            knowledge.addDocuments(allDocs).block();
            chunkCount.set(allDocs.size());
            indexed = true;

            log.info("知识库索引完成: {} 个文档片段", allDocs.size());
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "chunkCount", allDocs.size(),
                    "status", "indexed"
            )));
        } catch (IOException e) {
            log.error("知识库索引失败", e);
            return internalError("INDEX_FAILED", "索引失败: " + e.getMessage());
        }
    }

    // ==================== 状态查询 ====================

    @Operation(summary = "查询索引状态",
            description = "返回当前知识库的索引状态、chunk 数量、embedding 模型信息。")
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status() {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "indexed", indexed,
                "chunkCount", chunkCount.get(),
                "storagePath", storagePath.toString(),
                "embeddingModel", embeddingProps.modelName(),
                "dimensions", embeddingProps.dimensions()
        )));
    }

    // ==================== 内部辅助 ====================

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ResponseEntity<ApiResponse<Map<String, Object>>> badRequest(String code, String msg) {
        return (ResponseEntity) ResponseEntity.badRequest().body(ApiResponse.error(code, msg));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ResponseEntity<ApiResponse<Map<String, Object>>> internalError(String code, String msg) {
        return (ResponseEntity) ResponseEntity.internalServerError().body(ApiResponse.error(code, msg));
    }
}

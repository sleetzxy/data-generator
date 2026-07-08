package com.datagenerator.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 知识库文档切分与文件存储服务。
 * <p>负责：Markdown 按 ## 切分 → 写入文件系统 → 维护 index.json。</p>
 */
@Service
public class KnowledgeChunkService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeChunkService.class);
    private final ObjectMapper mapper;

    public KnowledgeChunkService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 按 ## 二级标题切分 Markdown 文本，每段作为一个独立 chunk */
    public List<ChunkInfo> splitMarkdown(String markdown) {
        List<ChunkInfo> chunks = new ArrayList<>();
        String[] lines = markdown.split("\n");

        int chunkIndex = 0;
        StringBuilder currentContent = new StringBuilder();
        String currentTitle = null;

        for (String line : lines) {
            // 二级标题（## xxx），非三级（### xxx）
            if (line.startsWith("## ") && !line.startsWith("### ")) {
                // 保存上一个 chunk
                if (currentTitle != null && !currentContent.isEmpty()) {
                    chunks.add(buildChunk(chunkIndex++, currentTitle, currentContent.toString()));
                }
                currentTitle = line.substring(3).trim();
                currentContent = new StringBuilder();
            }
            if (currentTitle != null) {
                currentContent.append(line).append("\n");
            }
        }
        // 最后一个 chunk
        if (currentTitle != null && !currentContent.isEmpty()) {
            chunks.add(buildChunk(chunkIndex, currentTitle, currentContent.toString()));
        }

        log.info("Markdown 切分完成: {} 个 chunk", chunks.size());
        return chunks;
    }

    private ChunkInfo buildChunk(int index, String title, String content) {
        String id = String.format("%02d", index + 1);
        String safeName = title.replaceAll("[\\\\/:*?\"<>|]", "")
                .replaceAll("\\s+", "-");
        String fileName = "chunks/" + id + "-" + safeName + ".md";
        return new ChunkInfo(id, title, fileName, content.trim());
    }

    /** 将 chunk 列表写入文件系统，同时生成 index.json */
    public void saveChunks(List<ChunkInfo> chunks, Path storagePath) throws IOException {
        Path chunksDir = storagePath.resolve("chunks");
        Files.createDirectories(chunksDir);

        for (ChunkInfo chunk : chunks) {
            Path filePath = storagePath.resolve(chunk.file());
            Files.writeString(filePath, chunk.content(), StandardCharsets.UTF_8);
            log.debug("写入 chunk: {}", chunk.file());
        }
        writeIndex(chunks, storagePath);
        log.info("Chunk 保存完成: {} 个文件 → {}", chunks.size(), chunksDir);
    }

    /** 生成 index.json */
    public void writeIndex(List<ChunkInfo> chunks, Path storagePath) throws IOException {
        List<IndexEntry> entries = chunks.stream()
                .map(c -> new IndexEntry(c.id(), c.title(), c.file()))
                .toList();
        IndexData index = new IndexData(entries);
        Path indexPath = storagePath.resolve("index.json");
        Files.writeString(indexPath, mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(index), StandardCharsets.UTF_8);
    }

    /** 从文件系统读取 index.json */
    public IndexData loadIndex(Path storagePath) throws IOException {
        Path indexPath = storagePath.resolve("index.json");
        if (!Files.exists(indexPath)) {
            return new IndexData(List.of());
        }
        String json = Files.readString(indexPath, StandardCharsets.UTF_8);
        return mapper.readValue(json, IndexData.class);
    }

    /** 从文件系统读取指定 chunk 的完整内容 */
    public String loadChunkContent(Path storagePath, String file) throws IOException {
        Path filePath = storagePath.resolve(file);
        if (!Files.exists(filePath)) {
            log.warn("Chunk 文件不存在: {}", filePath);
            return null;
        }
        return Files.readString(filePath, StandardCharsets.UTF_8);
    }

    // ==================== 内部 record ====================

    /** 切分后的单个 chunk（含完整内容，用于内存传递） */
    public record ChunkInfo(String id, String title, String file, String content) {}

    /** index.json 的一条记录（不含内容，仅元数据） */
    public record IndexEntry(String id, String title, String file) {}

    /** index.json 的完整结构 */
    public record IndexData(List<IndexEntry> chunks) {}
}

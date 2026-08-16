package com.dingring.infrastructure.rag;

import com.alibaba.cloud.ai.parser.tika.TikaDocumentParser;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.knowledgebase.File;
import com.dingring.domain.knowledgebase.FileRepository;
import com.dingring.infrastructure.rag.splitter.FixedSizeTextSplitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档摄入管道（Phase E）。
 * <p>异步执行：读取 → 切片 → 向量化 → 入库 → 更新状态。
 * <p>容错：任何环节失败更新文件状态为 FAILED，不影响主流程。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "dingring.rag.enabled", havingValue = "true", matchIfMissing = true)
public class DocumentIngestionPipeline {

    private final VectorStore vectorStore;
    private final FileRepository fileRepository;
    private final TikaDocumentParser tikaDocumentParser;
    private final FixedSizeTextSplitter textSplitter;

    /** 向量入库批次大小 */
    private static final int BATCH_SIZE = 50;

    /**
     * 显式构造器：kbVectorStore 必须用 @Qualifier 指名注入
     * （P2 起容器中存在 kb/topic 两个 PgVectorStore Bean）。
     */
    public DocumentIngestionPipeline(
            @Qualifier("kbVectorStore") VectorStore vectorStore,
            FileRepository fileRepository,
            TikaDocumentParser tikaDocumentParser,
            FixedSizeTextSplitter textSplitter) {
        this.vectorStore = vectorStore;
        this.fileRepository = fileRepository;
        this.tikaDocumentParser = tikaDocumentParser;
        this.textSplitter = textSplitter;
    }

    /**
     * 异步执行文档摄入。
     * <p>读取文件 → 切片 → 向量化入库 → 更新文件状态。
     *
     * @param file       文件元信息
     * @param scope      知识库作用域（GLOBAL / GROUP）
     * @param groupId    群 ID（scope=GROUP 时用于检索过滤）
     */
    @Async
    public void ingest(File file, String scope, Long groupId) {
        LogHelper.printLog(DocumentIngestionPipeline.class, "ingest", "RAG_INGEST",
                "摄入开始", "fileId={} fileName={} scope={}", file.getId(), file.getName(), scope);
        try {
            // 1. 读取文件
            List<Document> documents;
            try {
                documents = readDocument(file);
            } catch (IOException e) {
                updateFailed(file, "文件读取失败: " + e.getMessage());
                return;
            }
            if (documents.isEmpty()) {
                updateFailed(file, "文件内容为空或读取失败");
                return;
            }
            // 更新状态：切片中
            file.setStatus(File.STATUS_CHUNKED);
            fileRepository.update(file);

            // 2. 切片（P2：Fixed-size 512/64 字符滑窗，替换 TokenTextSplitter 800/200）
            List<Document> chunks = textSplitter.apply(documents);
            LogHelper.printLog(DocumentIngestionPipeline.class, "ingest", "RAG_INGEST",
                    "切片完成", "fileId={} 切片数={}", file.getId(), chunks.size());

            // 更新状态：向量化中
            file.setStatus(File.STATUS_EMBEDDED);
            file.setChunkCount(chunks.size());
            fileRepository.update(file);

            // 3. 构建 metadata 并入库（fileId/chunkIndex 为 P2 新增：删除文件/知识库时按 metadata 清理向量的关键）
            for (int i = 0; i < chunks.size(); i++) {
                Map<String, Object> metadata = buildMetadata(file, scope, groupId);
                metadata.put("fileId", file.getId());
                metadata.put("chunkIndex", i);
                chunks.get(i).getMetadata().putAll(metadata);
            }
            // 分批入库（避免单次 API 调用过大）
            for (int i = 0; i < chunks.size(); i += BATCH_SIZE) {
                int end = Math.min(i + BATCH_SIZE, chunks.size());
                List<Document> batch = chunks.subList(i, end);
                vectorStore.add(batch);
            }

            // 4. 更新状态：就绪
            file.setStatus(File.STATUS_READY);
            file.setUpdateTime(LocalDateTime.now());
            fileRepository.update(file);
            LogHelper.printLog(DocumentIngestionPipeline.class, "ingest", "RAG_INGEST",
                    "摄入完成", "fileId={} fileName={} 切片数={} scope={}",
                            file.getId(), file.getName(), chunks.size(), scope);
        } catch (Exception e) {
            LogHelper.printWarnLog(DocumentIngestionPipeline.class, "ingest", "RAG_INGEST",
                    "摄入失败", "fileId={} fileName={} 错误: {}",
                            file.getId(), file.getName(), e.getMessage(), e);
            updateFailed(file, e.getMessage());
        }
    }

    /** 用 SAA Tika parser 读取文件内容（P2：parse() 直接返回 Spring AI Document，生态统一） */
    private List<Document> readDocument(File file) throws IOException {
        try (InputStream is = Files.newInputStream(Paths.get(file.getPath()))) {
            return tikaDocumentParser.parse(is);
        }
    }

    /** 构建 metadata（双层过滤 + 溯源清理用） */
    private Map<String, Object> buildMetadata(File file, String scope, Long groupId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("kbId", file.getKnowledgeBaseId());
        metadata.put("fileName", file.getName());
        metadata.put("docType", file.getFileType());
        metadata.put("scope", scope);
        if (groupId != null) {
            metadata.put("groupId", groupId);
        }
        metadata.put("uploadTime", LocalDateTime.now().toString());
        return metadata;
    }

    /** 更新文件状态为失败 */
    private void updateFailed(File file, String errorMsg) {
        try {
            file.setStatus(File.STATUS_FAILED);
            file.setErrorMsg(errorMsg);
            file.setUpdateTime(LocalDateTime.now());
            fileRepository.update(file);
        } catch (Exception ex) {
            log.error("更新文件失败状态异常: fileId={}", file.getId(), ex);
        }
    }
}

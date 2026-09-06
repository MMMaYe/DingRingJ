package com.dingring.infrastructure.rag;

import com.dingring.common.constant.RagVersions;
import com.dingring.common.util.HashUtil;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.knowledgebase.CleaningSubmission;
import com.dingring.domain.knowledgebase.DocumentVersion;
import com.dingring.domain.knowledgebase.DocumentVersionRepository;
import com.dingring.domain.knowledgebase.File;
import com.dingring.domain.knowledgebase.FileRepository;
import com.dingring.domain.knowledgebase.IngestionRun;
import com.dingring.domain.knowledgebase.IngestionRunRepository;
import com.dingring.infrastructure.rag.parser.MarkdownStructureParser;
import com.dingring.infrastructure.rag.splitter.MarkdownSemanticChunker;
import com.dingring.infrastructure.rag.splitter.SemanticChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档摄入管道（P3 重构）。
 * <p>策略开关 dingring.rag.chunk.strategy：
 * <ul>
 *   <li>md-semantic（默认）：自研状态机解析 + 结构感知切分 + 完整溯源 metadata</li>
 *   <li>fixed：旧 MarkdownDocumentParser + FixedSizeTextSplitter（legacy 回退）</li>
 * </ul>
 * 摄入以 run 为幂等单位：每个阶段写入前校验 run 仍活跃，删除/取消后立即停止写向量。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "dingring.rag.enabled", havingValue = "true", matchIfMissing = true)
public class DocumentIngestionPipeline {

    private final VectorStore vectorStore;
    private final FileRepository fileRepository;
    private final IngestionRunRepository runRepository;
    private final DocumentVersionRepository versionRepository;
    private final MarkdownStructureParser structureParser;
    private final MarkdownSemanticChunker semanticChunker;
    private final LegacyIngestionSupport legacy;
    private final com.dingring.infrastructure.rag.cleaning.BuiltinDocumentCleaner builtinCleaner;
    private final VectorStoreCleaner vectorStoreCleaner;
    private final boolean legacyStrategy;
    private final boolean cleaningEnabled;

    /** 向量入库批次大小 */
    private static final int BATCH_SIZE = 50;

    public DocumentIngestionPipeline(
            @Qualifier("kbVectorStore") VectorStore vectorStore,
            FileRepository fileRepository,
            IngestionRunRepository runRepository,
            DocumentVersionRepository versionRepository,
            MarkdownStructureParser structureParser,
            MarkdownSemanticChunker semanticChunker,
            LegacyIngestionSupport legacy,
            @org.springframework.beans.factory.annotation.Value("${dingring.rag.chunk.strategy:md-semantic}") String strategy,
            @org.springframework.beans.factory.annotation.Value("${dingring.rag.cleaning.enabled:true}") boolean cleaningEnabled,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            com.dingring.infrastructure.rag.cleaning.BuiltinDocumentCleaner builtinCleaner,
            VectorStoreCleaner vectorStoreCleaner) {
        this.vectorStore = vectorStore;
        this.fileRepository = fileRepository;
        this.runRepository = runRepository;
        this.versionRepository = versionRepository;
        this.structureParser = structureParser;
        this.semanticChunker = semanticChunker;
        this.legacy = legacy;
        this.legacyStrategy = "fixed".equalsIgnoreCase(strategy);
        this.cleaningEnabled = cleaningEnabled;
        this.builtinCleaner = builtinCleaner;
        this.vectorStoreCleaner = vectorStoreCleaner;
    }

    /**
     * 异步执行文档摄入（以 run 为幂等单位）。
     *
     * @param file 文件元信息
     * @param run  本次摄入 run（null 时兼容旧调用：跳过 run 状态机，直接走 legacy 路径）
     */
    @Async
    public void ingest(File file, IngestionRun run) {
        LogHelper.printLog(DocumentIngestionPipeline.class, "ingest", "RAG_INGEST",
                "摄入开始", "fileId={} fileName={} runId={}",
                file.getId(), file.getName(), run == null ? "(legacy)" : run.getRunId());
        try {
            if (legacyStrategy) {
                ingestLegacy(file, run);
                return;
            }
            // 原始 hash 门控：文件被替换则拒绝
            String rawContent = Files.readString(Paths.get(file.getPath()), StandardCharsets.UTF_8);
            String docContentHash = HashUtil.sha256(rawContent);
            if (run != null && !docContentHash.equals(run.getDocContentHash())) {
                fail(file, run, "文件内容与 run 不一致（已替换），拒绝摄入");
                return;
            }

            // 清洗阶段：内置执行方在管道内清洗；外部执行方提交时已落 clean 文件（run 状态非 CLEANING_*）
            String ingestContent = rawContent;
            if (run != null) {
                if (IngestionRun.STATUS_CLEANING_RUNNING.equals(run.getStatus())
                        && builtinCleaner != null && cleaningEnabled) {
                    transition(file, run, IngestionRun.STATUS_CLEANING_RUNNING);
                    CleaningSubmission submission = builtinCleaner.clean(rawContent);
                    String cleanPath = file.getPath() + ".clean.md";
                    Files.writeString(Paths.get(cleanPath), submission.cleanMarkdown(), StandardCharsets.UTF_8);
                    updateVersionClean(file, run, cleanPath, submission.documentTitle(), "builtin");
                    file.setCleaningStatus(File.CLEANING_CLEANED);
                    fileRepository.update(file);
                    ingestContent = submission.cleanMarkdown();
                } else if (IngestionRun.STATUS_CLEANING_WAITING.equals(run.getStatus())) {
                    LogHelper.printLog(DocumentIngestionPipeline.class, "ingest", "RAG_INGEST",
                            "外部执行方待清洗，暂停摄入", "fileId={} runId={}", file.getId(), run.getRunId());
                    return;
                }
                // 外部执行方提交后重启：优先读 clean 版本
                DocumentVersion version = versionRepository
                        .findByFileAndVersion(file.getId(), run.getDocumentVersion()).orElse(null);
                if (version != null && version.getCleanPath() != null
                        && Files.exists(Paths.get(version.getCleanPath()))) {
                    ingestContent = Files.readString(Paths.get(version.getCleanPath()), StandardCharsets.UTF_8);
                }
            }

            List<SemanticChunk> chunks = semanticChunker.chunk(
                    structureParser.parse(ingestContent, documentTitle(file)));
            if (chunks.isEmpty()) {
                fail(file, run, "文件内容为空或解析无结果");
                return;
            }
            transition(file, run, IngestionRun.STATUS_CHUNKED);

            // 幂等清理：删除同 fileId+version 的半成品向量（重试/重启恢复可能与上次中断的
            // 摄入写回相同 chunkId），先清后写保证验收标准"重试不产生重复 chunk"
            if (run != null && vectorStoreCleaner != null) {
                vectorStoreCleaner.deleteByFileIdAndVersion(file.getId(), run.getDocumentVersion());
            }

            List<Document> documents = new ArrayList<>(chunks.size());
            for (SemanticChunk chunk : chunks) {
                documents.add(toDocument(file, chunk, docContentHash));
            }
            transition(file, run, IngestionRun.STATUS_EMBEDDING);

            for (int i = 0; i < documents.size(); i += BATCH_SIZE) {
                if (!isRunActive(run, file)) {
                    LogHelper.printWarnLog(DocumentIngestionPipeline.class, "ingest", "RAG_INGEST",
                            "run 已失效，停止向量写入", "fileId={} runId={}", file.getId(),
                            run == null ? "(legacy)" : run.getRunId());
                    return;
                }
                vectorStore.add(documents.subList(i, Math.min(i + BATCH_SIZE, documents.size())));
            }
            transition(file, run, IngestionRun.STATUS_INDEXING);

            activateVersion(file, run, chunks.size(), docContentHash);
            file.setStatus(File.STATUS_READY);
            file.setChunkCount(chunks.size());
            file.setUpdateTime(LocalDateTime.now());
            fileRepository.update(file);
            if (run != null) {
                run.setStatus(IngestionRun.STATUS_READY);
                run.setActiveSlot(null);
                file.setActiveRunId(null);
                runRepository.update(run);
                fileRepository.update(file);
            }
            LogHelper.printLog(DocumentIngestionPipeline.class, "ingest", "RAG_INGEST",
                    "摄入完成", "fileId={} fileName={} 切片数={}", file.getId(), file.getName(), chunks.size());
        } catch (Exception e) {
            LogHelper.printWarnLog(DocumentIngestionPipeline.class, "ingest", "RAG_INGEST",
                    "摄入失败", "fileId={} fileName={} 错误: {}",
                    file.getId(), file.getName(), e.getMessage(), e);
            fail(file, run, e.getMessage());
        }
    }

    /** legacy 兼容入口：无 run 的旧调用（测试/过渡期） */
    @Async
    public void ingest(File file) {
        ingest(file, null);
    }

    private void ingestLegacy(File file, IngestionRun run) throws IOException {
        List<Document> chunks = legacy.parseAndSplit(file);
        if (chunks.isEmpty()) {
            fail(file, run, "文件内容为空或读取失败");
            return;
        }
        transition(file, run, IngestionRun.STATUS_CHUNKED);
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("kbId", file.getKnowledgeBaseId());
            metadata.put("fileName", file.getName());
            metadata.put("docType", file.getFileType());
            metadata.put("fileId", file.getId());
            metadata.put("chunkIndex", i);
            metadata.put("documentVersion", file.getCurrentVersion() == null ? 1 : file.getCurrentVersion());
            metadata.put("active", true);
            chunks.get(i).getMetadata().putAll(metadata);
        }
        for (int i = 0; i < chunks.size(); i += BATCH_SIZE) {
            if (!isRunActive(run, file)) {
                LogHelper.printWarnLog(DocumentIngestionPipeline.class, "ingestLegacy", "RAG_INGEST",
                        "run 已失效，停止向量写入", "fileId={}", file.getId());
                return;
            }
            vectorStore.add(chunks.subList(i, Math.min(i + BATCH_SIZE, chunks.size())));
        }
        file.setStatus(File.STATUS_READY);
        file.setChunkCount(chunks.size());
        file.setUpdateTime(LocalDateTime.now());
        fileRepository.update(file);
        if (run != null) {
            run.setStatus(IngestionRun.STATUS_READY);
            run.setActiveSlot(null);
            runRepository.update(run);
            file.setActiveRunId(null);
            fileRepository.update(file);
        }
    }

    private void transition(File file, IngestionRun run, String status) {
        if (run != null) {
            run.setStatus(status);
            runRepository.update(run);
        }
    }

    private boolean isRunActive(IngestionRun run, File file) {
        if (run == null) {
            return !File.STATUS_DELETING.equals(file.getStatus());
        }
        return runRepository.findByRunId(run.getRunId())
                .map(current -> current.isActive() && !File.STATUS_DELETING.equals(file.getStatus()))
                .orElse(false);
    }

    private void updateVersionClean(File file, IngestionRun run, String cleanPath,
                                    String documentTitle, String executor) {
        int version = run.getDocumentVersion() == null ? 1 : run.getDocumentVersion();
        DocumentVersion existing = versionRepository.findByFileAndVersion(file.getId(), version)
                .orElseGet(() -> {
                    DocumentVersion created = new DocumentVersion();
                    created.setFileId(file.getId());
                    created.setDocumentVersion(version);
                    created.setDocumentTitle(documentTitle(file));
                    created.setDocContentHash(run.getDocContentHash());
                    created.setRawPath(file.getPath());
                    created.setActive(false);
                    return versionRepository.save(created);
                });
        existing.setCleanPath(cleanPath);
        if (documentTitle != null && !documentTitle.isBlank()) {
            existing.setDocumentTitle(documentTitle);
        }
        existing.setCleaningModel(executor);
        existing.setCleaningPromptVersion(RagVersions.CLEANING_V1);
        versionRepository.update(existing);
    }

    private void activateVersion(File file, IngestionRun run, int chunkCount, String docContentHash) {
        int version = run != null && run.getDocumentVersion() != null ? run.getDocumentVersion() : 1;
        DocumentVersion existing = versionRepository
                .findByFileAndVersion(file.getId(), version).orElse(null);
        if (existing == null) {
            DocumentVersion created = new DocumentVersion();
            created.setFileId(file.getId());
            created.setDocumentVersion(version);
            created.setDocumentTitle(documentTitle(file));
            created.setDocContentHash(docContentHash);
            created.setRawPath(file.getPath());
            created.setChunkingVersion(RagVersions.CHUNKING_V1);
            created.setChunkCount(chunkCount);
            versionRepository.save(created);
        } else {
            existing.setChunkCount(chunkCount);
            existing.setChunkingVersion(RagVersions.CHUNKING_V1);
            versionRepository.update(existing);
        }
        versionRepository.activate(file.getId(), version);
        file.setCurrentVersion(version);
    }

    private Document toDocument(File file, SemanticChunk chunk, String docContentHash) {
        String documentTitle = documentTitle(file);
        String heading = String.join(" > ", chunk.headingPath());
        String embeddingText = "文档：" + documentTitle + "\n章节：" + heading + "\n\n" + chunk.content();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kbId", file.getKnowledgeBaseId());
        metadata.put("fileId", file.getId());
        metadata.put("fileName", file.getName());
        metadata.put("documentTitle", documentTitle);
        metadata.put("documentVersion", file.getCurrentVersion() == null ? 1 : file.getCurrentVersion());
        metadata.put("chunkIndex", chunk.index());
        // chunkId：{fileId}-v{version}-{index}，检索目录展示与 Redis 全文缓存的 key 基础；
        // 确定性格式让 RRF 邻居扩展（fileId+version 定位兄弟 chunk）无需额外查询
        metadata.put("chunkId", file.getId() + "-v" + file.getCurrentVersion() + "-" + chunk.index());
        metadata.put("headingPath", chunk.headingPath());
        metadata.put("blockTypes", chunk.blockTypes().stream().map(Enum::name).toList());
        metadata.put("sourceStartLine", chunk.sourceStartLine());
        metadata.put("sourceEndLine", chunk.sourceEndLine());
        metadata.put("sourceStartOffset", chunk.sourceStartOffset());
        metadata.put("sourceEndOffset", chunk.sourceEndOffset());
        metadata.put("docContentHash", docContentHash);
        metadata.put("chunkContentHash", HashUtil.sha256(chunk.content()));
        metadata.put("chunkingVersion", RagVersions.CHUNKING_V1);
        metadata.put("active", true);
        return new Document(embeddingText, metadata);
    }

    private String documentTitle(File file) {
        String name = file.getName();
        if (name == null || name.isBlank()) {
            return "未命名文档";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private void fail(File file, IngestionRun run, String message) {
        try {
            file.setStatus(File.STATUS_FAILED);
            file.setErrorMsg(message);
            file.setUpdateTime(LocalDateTime.now());
            fileRepository.update(file);
            if (run != null) {
                run.setStatus(IngestionRun.STATUS_FAILED);
                run.setErrorMessage(message);
                run.setActiveSlot(null);
                runRepository.update(run);
                file.setActiveRunId(null);
                fileRepository.update(file);
            }
        } catch (Exception ex) {
            log.error("更新文件失败状态异常: fileId={}", file.getId(), ex);
        }
    }
}

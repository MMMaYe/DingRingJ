package com.dingring.app.service;

import com.dingring.app.dto.request.CreateKbRequest;
import com.dingring.app.dto.response.FileDTO;
import com.dingring.app.dto.response.KbDetail;
import com.dingring.app.dto.response.KbSummary;
import com.dingring.common.exception.BizException;
import com.dingring.common.exception.ErrorCode;
import com.dingring.common.exception.ParamException;
import com.dingring.common.util.HashUtil;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.knowledgebase.DocumentVersion;
import com.dingring.domain.knowledgebase.DocumentVersionRepository;
import com.dingring.domain.knowledgebase.File;
import com.dingring.domain.knowledgebase.FileRepository;
import com.dingring.domain.knowledgebase.IngestionRun;
import com.dingring.domain.knowledgebase.IngestionRunRepository;
import com.dingring.domain.knowledgebase.KnowledgeBase;
import com.dingring.domain.knowledgebase.KnowledgeBaseRepository;
import com.dingring.domain.group.GroupRepository;
import com.dingring.domain.service.RagService;
import com.dingring.infrastructure.rag.DocumentIngestionPipeline;
import com.dingring.infrastructure.rag.FileStorageService;
import com.dingring.infrastructure.rag.VectorStoreCleaner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

/**
 * 知识库管理应用服务（Phase E）。
 * <p>编排知识库 CRUD + 文件上传/删除 + 异步摄入 + 检索测试。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseAppService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final FileRepository fileRepository;
    private final DocumentIngestionPipeline ingestionPipeline;
    private final FileStorageService fileStorageService;
    private final RagService ragService;
    private final VectorStoreCleaner vectorStoreCleaner;
    private final GroupRepository groupRepository;
    private final IngestionRunRepository ingestionRunRepository;
    private final DocumentVersionRepository documentVersionRepository;

    @org.springframework.beans.factory.annotation.Value("${dingring.rag.cleaning.enabled:true}")
    private boolean cleaningEnabledByDefault;
    @org.springframework.beans.factory.annotation.Value("${dingring.rag.cleaning.executor:builtin}")
    private String cleaningExecutor;

    /** 创建知识库（空库默认 ACTIVE，文件状态由摄入管道流转） */
    public KbDetail create(CreateKbRequest request) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(request.getName());
        kb.setDescription(request.getDescription());
        kb.setStatus(KnowledgeBase.STATUS_ACTIVE);
        knowledgeBaseRepository.save(kb);

        LogHelper.printLog(KnowledgeBaseAppService.class, "create", "KB_CREATE",
                "知识库已创建", "id={} name={}", kb.getId(), kb.getName());
        return detail(kb.getId());
    }

    public List<KbSummary> list() {
        return knowledgeBaseRepository.findAll().stream()
                .map(this::toSummary)
                .toList();
    }

    public KbDetail detail(Long id) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "知识库不存在: " + id));
        List<FileDTO> files = fileRepository.findByKnowledgeBaseId(id).stream()
                .map(this::toFileDTO)
                .toList();
        return KbDetail.builder()
                .id(kb.getId())
                .name(kb.getName())
                .description(kb.getDescription())
                .status(kb.getStatus())
                .files(files)
                .createTime(kb.getCreateTime())
                .updateTime(kb.getUpdateTime())
                .build();
    }

    /**
     * 删除知识库：连带清理文件元数据与物理文件。
     * <p>先标记 DELETING 并使活跃 run 失效（防止异步摄入在清理后写回向量），再删向量与元数据。
     */
    public void delete(Long id) {
        knowledgeBaseRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "知识库不存在: " + id));
        for (File file : fileRepository.findByKnowledgeBaseId(id)) {
            invalidateActiveRun(file);
            fileStorageService.delete(file.getPath());
            fileRepository.deleteById(file.getId());
        }
        // P2：按 kbId 一次清理该知识库全部向量（fileId 溯源 metadata 已在摄入时写入）
        vectorStoreCleaner.deleteByKbId(id);
        knowledgeBaseRepository.deleteById(id);
        LogHelper.printLog(KnowledgeBaseAppService.class, "delete", "KB_DELETE",
                "知识库已删除", "id={}", id);
    }

    /**
     * 上传文件：md 双检 -> 落盘 -> 记录 kb_file -> 异步触发摄入。
     * <p>仅接受 .md/.markdown（contentType 非空时须 text/*），否则抛 {@link ParamException}。
     * <p>摄入失败不影响上传结果，状态由摄入管道回写为 FAILED。
     */
    public FileDTO upload(Long kbId, MultipartFile file, boolean clean) {
        // P2：当前仅支持 md 文档（解析与切片链路按 markdown 调优）
        validateMarkdown(file);
        KnowledgeBase kb = knowledgeBaseRepository.findById(kbId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "知识库不存在: " + kbId));

        String path = fileStorageService.store(file);
        String docContentHash = hashStoredFile(path);
        int nextVersion = 1;
        String executor = clean ? cleaningExecutor : IngestionRun.EXECUTOR_BUILTIN;

        File entity = new File();
        entity.setKnowledgeBaseId(kbId);
        entity.setName(file.getOriginalFilename());
        entity.setPath(path);
        entity.setFileType(detectFileType(file.getOriginalFilename()));
        entity.setFileSize(file.getSize());
        entity.setStatus(File.STATUS_UPLOADED);
        entity.setDocContentHash(docContentHash);
        entity.setCurrentVersion(nextVersion);
        entity.setCleaningStatus(clean ? null : File.CLEANING_SKIPPED);
        fileRepository.save(entity);

        // 创建摄入 run（活跃槽 = fileId）：同一文件同一时刻仅允许一个活跃 run
        // 勾选清洗 + 外部执行方：置 CLEANING_WAITING，等 MCP 提交后由提交服务唤醒管道；
        // 勾选清洗 + 内置执行方：管道内先 CLEANING_RUNNING 再继续；未勾选：直接摄入
        IngestionRun run = new IngestionRun();
        run.setRunId(UUID.randomUUID().toString());
        run.setFileId(entity.getId());
        run.setDocContentHash(docContentHash);
        run.setDocumentVersion(nextVersion);
        run.setExecutor(executor);
        run.setStatus(clean && IngestionRun.EXECUTOR_EXTERNAL_MCP.equals(executor)
                ? IngestionRun.STATUS_CLEANING_WAITING : IngestionRun.STATUS_UPLOAD_CREATED);
        run.setAttempt(0);
        run.setActiveSlot(entity.getId());
        ingestionRunRepository.save(run);

        // 版本记录（raw 不可变；active 切换在摄入全部成功后由管道执行）
        DocumentVersion version = new DocumentVersion();
        version.setFileId(entity.getId());
        version.setDocumentVersion(nextVersion);
        version.setDocumentTitle(stripExtension(entity.getName()));
        version.setDocContentHash(docContentHash);
        version.setRawPath(path);
        version.setActive(false);
        documentVersionRepository.save(version);

        entity.setActiveRunId(run.getRunId());
        fileRepository.update(entity);

        // 异步摄入：向量 metadata 按 kbId 溯源（检索过滤/删除清理均按 kbId）
        if (!IngestionRun.STATUS_CLEANING_WAITING.equals(run.getStatus())) {
            ingestionPipeline.ingest(entity, run);
        }

        LogHelper.printLog(KnowledgeBaseAppService.class, "upload", "KB_FILE_UPLOAD",
                "文件已上传", "kbId={} fileId={} name={} size={} runId={} version={} clean={} executor={}",
                kbId, entity.getId(), entity.getName(), entity.getFileSize(), run.getRunId(),
                nextVersion, clean, executor);
        return toFileDTO(entity);
    }

    public FileDTO upload(Long kbId, MultipartFile file) {
        return upload(kbId, file, cleaningEnabledByDefault);
    }

    /** 取消清洗任务：取消本次 run（无超时自动回退，等用户干预或取消） */
    public void cancelCleaning(Long fileId) {
        File file = fileRepository.findById(fileId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "文件不存在: " + fileId));
        IngestionRun run = ingestionRunRepository.findActiveByFileId(fileId)
                .orElseThrow(() -> new ParamException("该文件无活跃清洗任务"));
        if (!IngestionRun.STATUS_CLEANING_WAITING.equals(run.getStatus())
                && !IngestionRun.STATUS_CLEANING_RUNNING.equals(run.getStatus())
                && !IngestionRun.STATUS_FAILED.equals(run.getStatus())) {
            throw new ParamException("任务非清洗中状态: " + run.getStatus());
        }
        run.setStatus(IngestionRun.STATUS_CANCELLED);
        run.setActiveSlot(null);
        run.setErrorMessage("用户取消");
        ingestionRunRepository.update(run);
        file.setActiveRunId(null);
        file.setCleaningStatus(File.CLEANING_FAILED);
        file.setStatus(File.STATUS_FAILED);
        file.setErrorMsg("清洗已取消");
        fileRepository.update(file);
        LogHelper.printLog(KnowledgeBaseAppService.class, "cancelCleaning", "KB_CLEANING_CANCEL",
                "清洗任务已取消", "fileId={} runId={}", fileId, run.getRunId());
    }

    /** 手动重试：FAILED 终态任务拉起新 run（幂等，不产生重复向量） */
    public FileDTO retryIngestion(Long fileId) {
        File file = fileRepository.findById(fileId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "文件不存在: " + fileId));
        if (ingestionRunRepository.findActiveByFileId(fileId).isPresent()) {
            throw new ParamException("该文件已有活跃任务，不能重试");
        }
        String docContentHash = hashStoredFile(file.getPath());

        IngestionRun run = new IngestionRun();
        run.setRunId(UUID.randomUUID().toString());
        run.setFileId(fileId);
        run.setDocContentHash(docContentHash);
        run.setDocumentVersion(file.getCurrentVersion() == null ? 1 : file.getCurrentVersion());
        run.setExecutor(IngestionRun.EXECUTOR_BUILTIN);
        run.setStatus(IngestionRun.STATUS_UPLOAD_CREATED);
        run.setAttempt(0);
        run.setActiveSlot(fileId);
        ingestionRunRepository.save(run);

        file.setStatus(File.STATUS_UPLOADED);
        file.setErrorMsg(null);
        file.setActiveRunId(run.getRunId());
        fileRepository.update(file);

        ingestionPipeline.ingest(file, run);
        LogHelper.printLog(KnowledgeBaseAppService.class, "retryIngestion", "KB_RETRY",
                "手动重试已拉起新 run", "fileId={} runId={}", fileId, run.getRunId());
        return toFileDTO(file);
    }

    private String hashStoredFile(String path) {
        try {
            return HashUtil.sha256(Paths.get(path));
        } catch (IOException e) {
            throw new IllegalStateException("计算文件 hash 失败: " + path, e);
        }
    }

    private static String stripExtension(String name) {
        if (name == null || name.isBlank()) {
            return "未命名文档";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public List<FileDTO> listFiles(Long kbId) {
        knowledgeBaseRepository.findById(kbId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "知识库不存在: " + kbId));
        return fileRepository.findByKnowledgeBaseId(kbId).stream()
                .map(this::toFileDTO)
                .toList();
    }

    /** 删除文件：先失效 run 并标记 DELETING，再删物理文件、向量与元数据（幂等） */
    public void deleteFile(Long kbId, Long fileId) {
        File file = fileRepository.findById(fileId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "文件不存在: " + fileId));
        if (!kbId.equals(file.getKnowledgeBaseId())) {
            throw new ParamException("文件不属于该知识库");
        }
        invalidateActiveRun(file);
        file.setStatus(File.STATUS_DELETING);
        fileRepository.update(file);
        fileStorageService.delete(file.getPath());
        // P2：联动清理该文件全部切片向量（失败仅告警，不阻断删除）
        vectorStoreCleaner.deleteByFileId(fileId);
        fileRepository.deleteById(fileId);
        LogHelper.printLog(KnowledgeBaseAppService.class, "deleteFile", "KB_FILE_DELETE",
                "文件已删除", "kbId={} fileId={}", kbId, fileId);
    }

    /** 使文件当前活跃 run 失效（置 CANCELLED 并释放活跃槽），防止删除后异步写回 */
    private void invalidateActiveRun(File file) {
        if (file.getActiveRunId() == null) {
            return;
        }
        ingestionRunRepository.findByRunId(file.getActiveRunId()).ifPresent(run -> {
            if (run.isActive()) {
                run.setStatus(IngestionRun.STATUS_CANCELLED);
                run.setActiveSlot(null);
                run.setErrorMessage("文件删除，run 已取消");
                ingestionRunRepository.update(run);
            }
        });
        file.setActiveRunId(null);
    }

    /**
     * 检索测试（dev only）：以指定群视角调用 RAG 检索，便于人工验证召回效果。
     *
     * @param query   检索文本
     * @param groupId 群 ID（按该群绑定的知识库过滤；null 或群不存在时无结果）
     */
    public String search(String query, Long groupId) {
        List<Long> kbIds = groupId == null ? List.of()
                : groupRepository.findById(groupId).map(g -> g.boundKbIds()).orElse(List.of());
        return ragService.retrieve(query, kbIds);
    }

    private KbSummary toSummary(KnowledgeBase kb) {
        return KbSummary.builder()
                .id(kb.getId())
                .name(kb.getName())
                .description(kb.getDescription())
                .status(kb.getStatus())
                .createTime(kb.getCreateTime())
                .build();
    }

    private FileDTO toFileDTO(File file) {
        return FileDTO.builder()
                .id(file.getId())
                .knowledgeBaseId(file.getKnowledgeBaseId())
                .name(file.getName())
                .fileType(file.getFileType())
                .fileSize(file.getFileSize())
                .status(file.getStatus())
                .cleaningStatus(file.getCleaningStatus())
                // 活跃 run 状态供前端区分"待清洗（外部执行方）"与"清洗中"徽章
                .runStatus(file.getActiveRunId() == null ? null
                        : ingestionRunRepository.findByRunId(file.getActiveRunId())
                        .map(IngestionRun::getStatus).orElse(null))
                .chunkCount(file.getChunkCount())
                .errorMsg(file.getErrorMsg())
                .createTime(file.getCreateTime())
                .updateTime(file.getUpdateTime())
                .build();
    }

    /** 按扩展名识别文件类型，未知类型统一归 TXT（Tika 仍可尝试解析） */
    private String detectFileType(String filename) {
        if (filename == null) {
            return File.TYPE_TXT;
        }
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return File.TYPE_PDF;
        }
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return File.TYPE_MARKDOWN;
        }
        return File.TYPE_TXT;
    }

    /**
     * md 双检：扩展名必须 .md/.markdown；contentType 非空时须为 text/*
     * （浏览器对 .md 可能上报 text/markdown 或 text/plain，均放行；
     * 明确的二进制类型如 application/pdf 直接拒绝，防止改名伪装）。
     */
    private void validateMarkdown(MultipartFile file) {
        String name = file.getOriginalFilename();
        boolean extOk = name != null && (name.toLowerCase().endsWith(".md")
                || name.toLowerCase().endsWith(".markdown"));
        if (!extOk) {
            throw new ParamException("当前仅支持 .md 文件: " + name);
        }
        String contentType = file.getContentType();
        if (contentType != null && !contentType.isBlank() && !contentType.toLowerCase().startsWith("text/")) {
            throw new ParamException("文件类型不支持（仅支持 Markdown 文本）: " + contentType);
        }
    }
}

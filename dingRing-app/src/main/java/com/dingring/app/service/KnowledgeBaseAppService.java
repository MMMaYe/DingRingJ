package com.dingring.app.service;

import com.dingring.app.dto.request.CreateKbRequest;
import com.dingring.app.dto.response.FileDTO;
import com.dingring.app.dto.response.KbDetail;
import com.dingring.app.dto.response.KbSummary;
import com.dingring.common.exception.BizException;
import com.dingring.common.exception.ErrorCode;
import com.dingring.common.exception.ParamException;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.knowledgebase.File;
import com.dingring.domain.knowledgebase.FileRepository;
import com.dingring.domain.knowledgebase.KnowledgeBase;
import com.dingring.domain.knowledgebase.KnowledgeBaseRepository;
import com.dingring.domain.service.RagService;
import com.dingring.infrastructure.rag.DocumentIngestionPipeline;
import com.dingring.infrastructure.rag.FileStorageService;
import com.dingring.infrastructure.rag.VectorStoreCleaner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

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

    /** 创建知识库（空库默认 ACTIVE，文件状态由摄入管道流转） */
    public KbDetail create(CreateKbRequest request) {
        String scope = request.getScope();
        if (scope == null || scope.isBlank()) {
            scope = KnowledgeBase.SCOPE_GLOBAL;
        }
        // 群专属知识库必须关联群 ID，否则检索时无法定位归属
        if (KnowledgeBase.SCOPE_GROUP.equals(scope) && request.getGroupId() == null) {
            throw new ParamException("群专属知识库必须指定 groupId");
        }

        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(request.getName());
        kb.setScope(scope);
        kb.setGroupId(KnowledgeBase.SCOPE_GROUP.equals(scope) ? request.getGroupId() : null);
        kb.setStatus(KnowledgeBase.STATUS_ACTIVE);
        knowledgeBaseRepository.save(kb);

        LogHelper.printLog(KnowledgeBaseAppService.class, "create", "KB_CREATE",
                "知识库已创建", "id={} name={} scope={}", kb.getId(), kb.getName(), kb.getScope());
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
                .scope(kb.getScope())
                .groupId(kb.getGroupId())
                .status(kb.getStatus())
                .files(files)
                .createTime(kb.getCreateTime())
                .updateTime(kb.getUpdateTime())
                .build();
    }

    /**
     * 删除知识库：连带清理文件元数据与物理文件。
     * <p>向量条目按 kbId 联动清理（失败仅告警）。
     */
    public void delete(Long id) {
        knowledgeBaseRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "知识库不存在: " + id));
        for (File file : fileRepository.findByKnowledgeBaseId(id)) {
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
     * 上传文件：落盘 → 记录 kb_file → 异步触发摄入。
     * <p>摄入失败不影响上传结果，状态由摄入管道回写为 FAILED。
     */
    public FileDTO upload(Long kbId, MultipartFile file) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(kbId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "知识库不存在: " + kbId));

        String path = fileStorageService.store(file);

        File entity = new File();
        entity.setKnowledgeBaseId(kbId);
        entity.setName(file.getOriginalFilename());
        entity.setPath(path);
        entity.setFileType(detectFileType(file.getOriginalFilename()));
        entity.setFileSize(file.getSize());
        entity.setStatus(File.STATUS_UPLOADED);
        fileRepository.save(entity);

        // 异步摄入：scope/groupId 用于向量库双层过滤
        ingestionPipeline.ingest(entity, kb.getScope(), kb.getGroupId());

        LogHelper.printLog(KnowledgeBaseAppService.class, "upload", "KB_FILE_UPLOAD",
                "文件已上传", "kbId={} fileId={} name={} size={}",
                kbId, entity.getId(), entity.getName(), entity.getFileSize());
        return toFileDTO(entity);
    }

    public List<FileDTO> listFiles(Long kbId) {
        knowledgeBaseRepository.findById(kbId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "知识库不存在: " + kbId));
        return fileRepository.findByKnowledgeBaseId(kbId).stream()
                .map(this::toFileDTO)
                .toList();
    }

    /** 删除文件：先删物理文件再删元数据，物理文件缺失不阻断（幂等） */
    public void deleteFile(Long kbId, Long fileId) {
        File file = fileRepository.findById(fileId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "文件不存在: " + fileId));
        if (!kbId.equals(file.getKnowledgeBaseId())) {
            throw new ParamException("文件不属于该知识库");
        }
        fileStorageService.delete(file.getPath());
        // P2：联动清理该文件全部切片向量（失败仅告警，不阻断删除）
        vectorStoreCleaner.deleteByFileId(fileId);
        fileRepository.deleteById(fileId);
        LogHelper.printLog(KnowledgeBaseAppService.class, "deleteFile", "KB_FILE_DELETE",
                "文件已删除", "kbId={} fileId={}", kbId, fileId);
    }

    /**
     * 检索测试（dev only）：直接调用 RAG 检索，便于人工验证召回效果。
     *
     * @param query   检索文本
     * @param groupId 群 ID（null 时仅检索全局知识）
     */
    public String search(String query, Long groupId) {
        return ragService.retrieve(query, groupId);
    }

    private KbSummary toSummary(KnowledgeBase kb) {
        return KbSummary.builder()
                .id(kb.getId())
                .name(kb.getName())
                .scope(kb.getScope())
                .groupId(kb.getGroupId())
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
}

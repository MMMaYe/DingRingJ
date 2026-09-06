package com.dingring.app.service;

import com.dingring.common.constant.RagVersions;
import com.dingring.common.exception.BizException;
import com.dingring.common.exception.ErrorCode;
import com.dingring.common.exception.ParamException;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.knowledgebase.CleaningSubmission;
import com.dingring.domain.knowledgebase.DocumentVersion;
import com.dingring.domain.knowledgebase.DocumentVersionRepository;
import com.dingring.domain.knowledgebase.File;
import com.dingring.domain.knowledgebase.FileRepository;
import com.dingring.domain.knowledgebase.IngestionRun;
import com.dingring.domain.knowledgebase.IngestionRunRepository;
import com.dingring.infrastructure.rag.DocumentIngestionPipeline;
import com.dingring.infrastructure.rag.cleaning.CleaningSubmissionValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;

/**
 * 清洗结果提交服务（3.4.2）：内置与外部执行方共用的提交落地。
 * <p>校验顺序：run 活跃且 CLEANING_WAITING → hash 一致 → 协议校验 →
 * clean 版本落盘 → 更新 run/文件 → 唤醒摄入管道。
 * <p>幂等：run 已 READY 时重复提交直接返回首次结果；非 WAITING 状态拒绝。
 */
@Service
@RequiredArgsConstructor
public class CleaningSubmissionService {

    private final FileRepository fileRepository;
    private final IngestionRunRepository ingestionRunRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final CleaningSubmissionValidator validator;
    private final DocumentIngestionPipeline ingestionPipeline;

    /** 外部 MCP / 重试入口共用 */
    public String submit(Long fileId, String submissionJson) {
        File file = fileRepository.findById(fileId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "文件不存在: " + fileId));
        IngestionRun run = ingestionRunRepository.findActiveByFileId(fileId)
                .orElseThrow(() -> new ParamException("文件无活跃清洗任务（已完成、已失败或已取消），当前状态拒绝提交"));

        // 幂等：已完成状态重复提交直接返回
        if (IngestionRun.STATUS_READY.equals(run.getStatus())) {
            return "已处理完成，本次提交幂等忽略";
        }
        if (!IngestionRun.STATUS_CLEANING_WAITING.equals(run.getStatus())
                && !IngestionRun.STATUS_CLEANING_RUNNING.equals(run.getStatus())) {
            throw new ParamException("任务非待清洗状态: " + run.getStatus());
        }

        // 提交过期防护：重算原始文件 hash
        String currentHash = hashFile(file.getPath());
        if (!currentHash.equals(run.getDocContentHash())) {
            throw new ParamException("原始文件已被替换，本次提交已过期");
        }

        CleaningSubmission submission = validator.parseAndValidate(submissionJson);
        if (submission == null) {
            throw new ParamException("清洗结果不符合协议（cleanMarkdown 为空或 JSON 非法）");
        }

        // documentTitle 缺省取文件名
        String title = submission.documentTitle() == null || submission.documentTitle().isBlank()
                ? stripExtension(file.getName()) : submission.documentTitle().trim();

        // clean 版本落盘（raw 不可变，clean 独立存储）
        String cleanPath = file.getPath() + ".clean.md";
        writeCleanFile(cleanPath, submission.cleanMarkdown());

        DocumentVersion version = documentVersionRepository
                .findByFileAndVersion(fileId, run.getDocumentVersion())
                .orElseThrow(() -> new ParamException("版本记录缺失: v" + run.getDocumentVersion()));
        version.setDocumentTitle(title);
        version.setCleanPath(cleanPath);
        version.setCleaningModel("external-mcp");
        version.setCleaningPromptVersion(RagVersions.CLEANING_V1);
        documentVersionRepository.update(version);

        file.setCleaningStatus(File.CLEANING_CLEANED);
        file.setDocContentHash(currentHash);
        fileRepository.update(file);

        run.setStatus(IngestionRun.STATUS_PARSED);
        run.setSubmittedAt(LocalDateTime.now());
        ingestionRunRepository.update(run);

        LogHelper.printLog(CleaningSubmissionService.class, "submit", "KB_CLEANING_SUBMIT",
                "清洗结果已提交", "fileId={} runId={} title={} chars={}",
                fileId, run.getRunId(), title, submission.cleanMarkdown().length());

        // 唤醒摄入管道（读 clean 版本入库）
        ingestionPipeline.ingest(file, run);
        return "提交成功，已进入摄入管道";
    }

    private String hashFile(String path) {
        try {
            return com.dingring.common.util.HashUtil.sha256(Path.of(path));
        } catch (IOException e) {
            throw new IllegalStateException("读取文件失败: " + path, e);
        }
    }

    private void writeCleanFile(String path, String content) {
        try {
            Files.writeString(Paths.get(path), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("清洗结果落盘失败: " + path, e);
        }
    }

    private static String stripExtension(String name) {
        if (name == null || name.isBlank()) {
            return "未命名文档";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}

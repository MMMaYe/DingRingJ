package com.dingring.adapter.rest;

import com.dingring.app.dto.request.CreateKbRequest;
import com.dingring.app.dto.response.FileDTO;
import com.dingring.app.dto.response.KbDetail;
import com.dingring.app.dto.response.KbSummary;
import com.dingring.app.service.KnowledgeBaseAppService;
import com.dingring.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库管理 REST API（Phase E）。
 */
@RestController
@RequestMapping("/api/kb")
@RequiredArgsConstructor
public class KbController {

    private final KnowledgeBaseAppService knowledgeBaseAppService;

    /** 创建知识库 */
    @PostMapping
    public ApiResponse<KbDetail> create(@Valid @RequestBody CreateKbRequest request) {
        return ApiResponse.ok(knowledgeBaseAppService.create(request));
    }

    /** 列出所有知识库 */
    @GetMapping
    public ApiResponse<List<KbSummary>> list() {
        return ApiResponse.ok(knowledgeBaseAppService.list());
    }

    /** 知识库详情（含文件列表） */
    @GetMapping("/{id}")
    public ApiResponse<KbDetail> detail(@PathVariable Long id) {
        return ApiResponse.ok(knowledgeBaseAppService.detail(id));
    }

    /** 删除知识库 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        knowledgeBaseAppService.delete(id);
        return ApiResponse.ok();
    }

    /** 上传文件（multipart）；clean 默认开启，可显式取消 */
    @PostMapping("/{id}/files")
    public ApiResponse<FileDTO> upload(@PathVariable Long id,
                                       @RequestParam("file") MultipartFile file,
                                       @RequestParam(value = "clean", required = false, defaultValue = "true") boolean clean) {
        return ApiResponse.ok(knowledgeBaseAppService.upload(id, file, clean));
    }

    /** 列出知识库文件 */
    @GetMapping("/{id}/files")
    public ApiResponse<List<FileDTO>> listFiles(@PathVariable Long id) {
        return ApiResponse.ok(knowledgeBaseAppService.listFiles(id));
    }

    /** 删除文件 */
    @DeleteMapping("/{id}/files/{fileId}")
    public ApiResponse<Void> deleteFile(@PathVariable Long id, @PathVariable Long fileId) {
        knowledgeBaseAppService.deleteFile(id, fileId);
        return ApiResponse.ok();
    }

    /** 取消清洗任务（取消本次上传/run，不提供"跳过清洗后入库"） */
    @PostMapping("/{id}/files/{fileId}/cleaning/cancel")
    public ApiResponse<Void> cancelCleaning(@PathVariable Long id, @PathVariable Long fileId) {
        knowledgeBaseAppService.cancelCleaning(fileId);
        return ApiResponse.ok();
    }

    /** 手动重试失败任务：拉起新 run */
    @PostMapping("/{id}/files/{fileId}/retry")
    public ApiResponse<FileDTO> retry(@PathVariable Long id, @PathVariable Long fileId) {
        return ApiResponse.ok(knowledgeBaseAppService.retryIngestion(fileId));
    }
}

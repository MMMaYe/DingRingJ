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

    /** 上传文件（multipart） */
    @PostMapping("/{id}/files")
    public ApiResponse<FileDTO> upload(@PathVariable Long id,
                                       @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(knowledgeBaseAppService.upload(id, file));
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

    /** 检索测试（dev only）：验证知识库召回效果 */
    @PostMapping("/search")
    public ApiResponse<String> search(@RequestParam("query") String query,
                                      @RequestParam(value = "groupId", required = false) Long groupId) {
        return ApiResponse.ok(knowledgeBaseAppService.search(query, groupId));
    }
}

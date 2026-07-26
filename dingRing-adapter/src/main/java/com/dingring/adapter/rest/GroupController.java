package com.dingring.adapter.rest;

import com.dingring.app.dto.request.CreateGroupRequest;
import com.dingring.app.dto.response.GroupDetail;
import com.dingring.app.dto.response.GroupSummary;
import com.dingring.app.service.GroupAppService;
import com.dingring.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 群管理 REST API。
 */
@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupAppService groupAppService;

    /** 创建群（含成员配置） */
    @PostMapping
    public ApiResponse<GroupDetail> create(@Valid @RequestBody CreateGroupRequest request) {
        return ApiResponse.ok(groupAppService.create(request));
    }

    /** 查询用户的群列表 */
    @GetMapping
    public ApiResponse<List<GroupSummary>> list() {
        return ApiResponse.ok(groupAppService.list());
    }

    /** 群详情（含成员） */
    @GetMapping("/{id}")
    public ApiResponse<GroupDetail> detail(@PathVariable Long id) {
        return ApiResponse.ok(groupAppService.detail(id));
    }

    /** 删除群（历史保留） */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        groupAppService.delete(id);
        return ApiResponse.ok();
    }
}

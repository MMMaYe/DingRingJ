package com.dingring.adapter.rest;

import com.dingring.app.dto.request.SaveSkillRequest;
import com.dingring.app.dto.response.SkillDTO;
import com.dingring.app.service.SkillAppService;
import com.dingring.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SKILL 管理 REST API（Phase F）。
 * <p>技能 = 工具组 + 附加系统提示词，可通过 CRUD 动态装配 Agent 能力（热更新走 SkillHotReloader）。
 */
@RestController
@RequestMapping("/api/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillAppService skillAppService;

    /** 创建技能 */
    @PostMapping
    public ApiResponse<SkillDTO> create(@Valid @RequestBody SaveSkillRequest request) {
        return ApiResponse.ok(skillAppService.create(request));
    }

    /** 修改技能 */
    @PutMapping("/{id}")
    public ApiResponse<SkillDTO> update(@PathVariable Long id, @Valid @RequestBody SaveSkillRequest request) {
        return ApiResponse.ok(skillAppService.update(id, request));
    }

    /** 删除技能 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        skillAppService.delete(id);
        return ApiResponse.ok();
    }

    /** 技能列表（含全局与绑定技能） */
    @GetMapping
    public ApiResponse<List<SkillDTO>> list(@RequestParam(value = "agentId", required = false) Long agentId) {
        if (agentId != null) {
            return ApiResponse.ok(skillAppService.listByAgent(agentId));
        }
        return ApiResponse.ok(skillAppService.listAll());
    }

    /** 技能详情 */
    @GetMapping("/{id}")
    public ApiResponse<SkillDTO> getById(@PathVariable Long id) {
        return ApiResponse.ok(skillAppService.getById(id));
    }
}

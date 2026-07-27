package com.dingring.adapter.rest;

import com.dingring.app.dto.request.SaveAgentRequest;
import com.dingring.app.dto.response.AgentDTO;
import com.dingring.app.service.AgentAppService;
import com.dingring.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent 管理 REST API。
 */
@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentAppService agentAppService;

    /** 创建 Agent */
    @PostMapping
    public ApiResponse<AgentDTO> create(@Validated(SaveAgentRequest.Create.class) @RequestBody SaveAgentRequest request) {
        return ApiResponse.ok(agentAppService.create(request));
    }

    /** 修改 Agent 配置 */
    @PutMapping("/{id}")
    public ApiResponse<AgentDTO> update(@PathVariable Long id, @Validated(SaveAgentRequest.Update.class) @RequestBody SaveAgentRequest request) {
        return ApiResponse.ok(agentAppService.update(id, request));
    }

    /** Agent 列表 */
    @GetMapping
    public ApiResponse<List<AgentDTO>> list() {
        return ApiResponse.ok(agentAppService.list());
    }
}

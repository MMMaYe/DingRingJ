package com.dingring.app.service;

import com.dingring.app.dto.request.CreateAgentRequest;
import com.dingring.app.dto.request.UpdateAgentRequest;
import com.dingring.app.dto.response.AgentDTO;
import com.dingring.common.exception.BizException;
import com.dingring.common.exception.ErrorCode;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Agent 管理应用服务。
 */
@Service
@RequiredArgsConstructor
public class AgentAppService {

    private final AgentRepository agentRepository;

    public AgentDTO create(CreateAgentRequest request) {
        Agent agent = new Agent();
        agent.setName(request.getName());
        agent.setProfilePicture(request.getProfilePicture());
        agent.setDescription(request.getDescription());
        agent.setBaseUrl(request.getBaseUrl());
        agent.setApiKey(request.getApiKey());
        agent.setModelName(request.getModelName());
        // callType 未指定时默认 API
        agent.setCallType(request.getCallType() == null || request.getCallType().isBlank()
                ? "API" : request.getCallType());
        agent.setSystemPrompt(request.getSystemPrompt());
        agent.setFeature(request.getFeature());
        agentRepository.save(agent);
        return toDto(agent);
    }

    public AgentDTO update(Long id, UpdateAgentRequest request) {
        Agent agent = agentRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "Agent 不存在: " + id));
        agent.setName(request.getName());
        agent.setProfilePicture(request.getProfilePicture());
        agent.setDescription(request.getDescription());
        agent.setBaseUrl(request.getBaseUrl());
        // apiKey 为空表示不修改原 Key
        if (request.getApiKey() != null && !request.getApiKey().isBlank()) {
            agent.setApiKey(request.getApiKey());
        }
        agent.setModelName(request.getModelName());
        agent.setCallType(request.getCallType());
        agent.setSystemPrompt(request.getSystemPrompt());
        agent.setFeature(request.getFeature());
        agentRepository.update(agent);
        return toDto(agent);
    }

    public List<AgentDTO> list() {
        return agentRepository.findAll().stream().map(this::toDto).toList();
    }

    /** 不回传 apiKey */
    private AgentDTO toDto(Agent agent) {
        return AgentDTO.builder()
                .id(agent.getId())
                .name(agent.getName())
                .profilePicture(agent.getProfilePicture())
                .description(agent.getDescription())
                .baseUrl(agent.getBaseUrl())
                .modelName(agent.getModelName())
                .callType(agent.getCallType())
                .systemPrompt(agent.getSystemPrompt())
                .feature(agent.getFeature())
                .createTime(agent.getCreateTime())
                .updateTime(agent.getUpdateTime())
                .build();
    }
}

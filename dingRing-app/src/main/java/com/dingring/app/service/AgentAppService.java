package com.dingring.app.service;

import com.dingring.app.dto.request.SaveAgentRequest;
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

    public AgentDTO create(SaveAgentRequest request) {
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

    public AgentDTO update(Long id, SaveAgentRequest request) {
        Agent agent = agentRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "Agent 不存在: " + id));
        agent.setName(request.getName());
        agent.setProfilePicture(request.getProfilePicture());
        agent.setDescription(request.getDescription());
        agent.setBaseUrl(request.getBaseUrl());
        // apiKey 现在创建/修改都必填，直接覆盖
        agent.setApiKey(request.getApiKey());
        agent.setModelName(request.getModelName());
        agent.setCallType(request.getCallType());
        agent.setSystemPrompt(request.getSystemPrompt());
        agent.setFeature(request.getFeature());
        agentRepository.update(agent);
        return toDto(agent);
    }

    /**
     * Agent 列表（群加群选择器 / Agent 管理页均用此接口）。
     * <p>过滤掉路由判定器（feature.routeJudge=true）：
     * 判定器不参与群讨论，不应出现在加群成员选择器中；
     * 如需维护判定器配置，通过 {@link #findById(Long)} 详情接口按 id 获取。
     */
    public List<AgentDTO> list() {
        return agentRepository.findAll().stream()
                .filter(a -> !a.isRouteJudge())
                .map(this::toDto)
                .toList();
    }

    /**
     * 按 id 查询 Agent 详情。
     * 用于编辑场景拉取最新配置，避免使用列表快照数据覆盖他人修改。
     * 返回的 DTO 包含 apiKey（编辑回填需要），与 list 接口（不含 apiKey）区分。
     */
    public AgentDTO findById(Long id) {
        return agentRepository.findById(id)
                .map(this::toDetailDto)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "Agent 不存在: " + id));
    }

    /** 列表场景：不回传 apiKey（安全考虑，避免列表接口泄露密钥） */
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

    /** 详情场景：回传 apiKey（编辑回填需要） */
    private AgentDTO toDetailDto(Agent agent) {
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
                .apiKey(agent.getApiKey())
                .build();
    }
}

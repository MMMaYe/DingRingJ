package com.dingring.app.service;

import com.dingring.app.dto.request.SaveSkillRequest;
import com.dingring.app.dto.response.SkillDTO;
import com.dingring.common.exception.BizException;
import com.dingring.common.exception.ErrorCode;
import com.dingring.common.exception.ParamException;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.skill.Skill;
import com.dingring.domain.skill.SkillRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * SKILL 管理应用服务（Phase F）。
 * <p>编排技能 CRUD + 查询，负责：
 * <ul>
 *   <li>入参校验（scope/agentId 联动规则）</li>
 *   <li>名称唯一性检查（skill 表 uk_name 兜底）</li>
 *   <li>领域实体 ↔ DTO 转换</li>
 * </ul>
 * <p>技能挂载与热加载见 infrastructure 层（SkillToolkitFactory / SkillLoaderService / SkillHotReloader）。
 */
@Service
@RequiredArgsConstructor
public class SkillAppService {

    private final SkillRepository skillRepository;

    /** 创建技能（scope 缺省 GLOBAL，status 缺省 ACTIVE） */
    public SkillDTO create(SaveSkillRequest request) {
        String scope = normalizeScope(request.getScope());
        String status = request.getStatus() != null && !request.getStatus().isBlank()
                ? request.getStatus() : Skill.STATUS_ACTIVE;
        validate(scope, request.getAgentId());

        // 名称唯一（数据库 uk_name 兜底，提前检查给出友好报错）
        if (skillRepository.findByName(request.getName()).isPresent()) {
            throw new ParamException("技能名称已存在: " + request.getName());
        }

        Skill skill = new Skill();
        skill.setName(request.getName());
        skill.setDescription(request.getDescription());
        skill.setToolNames(request.getToolNames());
        skill.setSystemPrompt(request.getSystemPrompt());
        skill.setScope(scope);
        skill.setAgentId(Skill.SCOPE_AGENT.equals(scope) ? request.getAgentId() : null);
        skill.setStatus(status);
        skillRepository.save(skill);

        LogHelper.printLog(SkillAppService.class, "create", "SKILL_CREATE",
                "技能已创建", "id={} name={} scope={} status={}",
                skill.getId(), skill.getName(), skill.getScope(), skill.getStatus());
        return toDto(skill);
    }

    /** 修改技能（scope/agentId 联动校验同创建） */
    public SkillDTO update(Long id, SaveSkillRequest request) {
        Skill skill = skillRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "技能不存在: " + id));
        String scope = normalizeScope(request.getScope() != null ? request.getScope() : skill.getScope());
        validate(scope, request.getAgentId());

        // 改名需唯一（排除自身）
        if (!skill.getName().equals(request.getName())
                && skillRepository.findByName(request.getName()).isPresent()) {
            throw new ParamException("技能名称已存在: " + request.getName());
        }

        skill.setName(request.getName());
        skill.setDescription(request.getDescription());
        skill.setToolNames(request.getToolNames());
        skill.setSystemPrompt(request.getSystemPrompt());
        skill.setScope(scope);
        skill.setAgentId(Skill.SCOPE_AGENT.equals(scope) ? request.getAgentId() : null);
        if (request.getStatus() != null && !request.getStatus().isBlank()) {
            skill.setStatus(request.getStatus());
        }
        skillRepository.update(skill);

        LogHelper.printLog(SkillAppService.class, "update", "SKILL_UPDATE",
                "技能已更新", "id={} name={}", id, skill.getName());
        return toDto(skill);
    }

    /** 删除技能 */
    public void delete(Long id) {
        boolean deleted = skillRepository.deleteById(id);
        if (!deleted) {
            throw new BizException(ErrorCode.NOT_FOUND, "技能不存在: " + id);
        }
        LogHelper.printLog(SkillAppService.class, "delete", "SKILL_DELETE",
                "技能已删除", "id={}", id);
    }

    public SkillDTO getById(Long id) {
        Skill skill = skillRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "技能不存在: " + id));
        return toDto(skill);
    }

    public List<SkillDTO> listAll() {
        return skillRepository.findAll().stream().map(this::toDto).toList();
    }

    /** 按 Agent 查看其绑定的技能（scope=AGENT 列表，不含全局技能） */
    public List<SkillDTO> listByAgent(Long agentId) {
        return skillRepository.findByAgentId(agentId).stream().map(this::toDto).toList();
    }

    /** scope 归一化：空值转 GLOBAL，非法值直接报错 */
    private String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return Skill.SCOPE_GLOBAL;
        }
        if (!Skill.SCOPE_GLOBAL.equals(scope) && !Skill.SCOPE_AGENT.equals(scope)) {
            throw new ParamException("非法的技能作用域: " + scope + "（仅支持 GLOBAL/AGENT）");
        }
        return scope;
    }

    /** 联动校验：scope=AGENT 必须指定 agentId */
    private void validate(String scope, Long agentId) {
        if (Skill.SCOPE_AGENT.equals(scope) && agentId == null) {
            throw new ParamException("scope=AGENT 时必须指定 agentId");
        }
    }

    private SkillDTO toDto(Skill skill) {
        return SkillDTO.builder()
                .id(skill.getId())
                .name(skill.getName())
                .description(skill.getDescription())
                .toolNames(skill.getToolNames())
                .toolNameList(skill.toolNameList())
                .systemPrompt(skill.getSystemPrompt())
                .scope(skill.getScope())
                .agentId(skill.getAgentId())
                .status(skill.getStatus())
                .createTime(skill.getCreateTime())
                .updateTime(skill.getUpdateTime())
                .build();
    }
}

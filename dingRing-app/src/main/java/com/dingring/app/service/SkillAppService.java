package com.dingring.app.service;

import com.dingring.app.dto.request.SaveSkillRequest;
import com.dingring.app.dto.response.SkillDTO;
import com.dingring.common.exception.BizException;
import com.dingring.common.exception.ErrorCode;
import com.dingring.common.exception.ParamException;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.skill.Skill;
import com.dingring.domain.skill.SkillRepository;
import com.dingring.domain.skill.SkillScene;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * SKILL 管理应用服务（Phase F）。
 * <p>编排技能 CRUD + 查询，负责：
 * <ul>
 *   <li>入参校验（scope 与 agentId/sceneKey 联动规则：GLOBAL/AGENT 面向 Agent 装配；
 *       Phase G 新增 SCENE 绑定沉淀生成场景——conclude/card/topic-profile，
 *       沉淀链路是无工具生成，故 toolNames/agentId 必须为空）</li>
 *   <li>名称唯一性检查（skill 表 uk_name 兜底）</li>
 *   <li>领域实体 ↔ DTO 转换</li>
 * </ul>
 * <p>技能装载见 infrastructure 层：Agent 装配走 SkillToolkitFactory / SkillLoaderService，
 * 沉淀场景（conclude/card/topic-profile）规范注入走 SkillLoaderService.loadSceneSkills——
 * 实时查库、CRUD 即时生效，无种子/热加载机制（技能表冷启动为空，REST CRUD 是唯一入口）。
 */
@Service
@RequiredArgsConstructor
public class SkillAppService {

    /** R2 软限制：SCENE 技能正文建议上限（超长会挤占沉淀生成预算：卡片 2048/画像仅 500 tokens） */
    private static final int SCENE_PROMPT_SOFT_LIMIT = 800;

    private final SkillRepository skillRepository;

    /** 创建技能（scope 缺省 GLOBAL，status 缺省 ACTIVE） */
    public SkillDTO create(SaveSkillRequest request) {
        String scope = normalizeScope(request.getScope());
        String status = request.getStatus() != null && !request.getStatus().isBlank()
                ? request.getStatus() : Skill.STATUS_ACTIVE;
        validate(scope, request);

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
        skill.setSceneKey(Skill.SCOPE_SCENE.equals(scope) ? request.getSceneKey() : null);
        skill.setStatus(status);
        skillRepository.save(skill);

        warnIfScenePromptTooLong(skill, "create");

        LogHelper.printLog(SkillAppService.class, "create", "SKILL_CREATE",
                "技能已创建", "id={} name={} scope={} status={}",
                skill.getId(), skill.getName(), skill.getScope(), skill.getStatus());
        return toDto(skill);
    }

    /** 修改技能（scope/agentId/sceneKey 联动校验同创建） */
    public SkillDTO update(Long id, SaveSkillRequest request) {
        Skill skill = skillRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "技能不存在: " + id));
        String scope = normalizeScope(request.getScope() != null ? request.getScope() : skill.getScope());
        validate(scope, request);

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
        skill.setSceneKey(Skill.SCOPE_SCENE.equals(scope) ? request.getSceneKey() : null);
        if (request.getStatus() != null && !request.getStatus().isBlank()) {
            skill.setStatus(request.getStatus());
        }
        skillRepository.update(skill);

        warnIfScenePromptTooLong(skill, "update");

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
        if (!Skill.SCOPE_GLOBAL.equals(scope) && !Skill.SCOPE_AGENT.equals(scope)
                && !Skill.SCOPE_SCENE.equals(scope)) {
            throw new ParamException("非法的技能作用域: " + scope + "（仅支持 GLOBAL/AGENT/SCENE）");
        }
        return scope;
    }

    /** 联动校验：scope=AGENT 必须指定 agentId；scope=SCENE 必须指定合法 sceneKey 且无工具/Agent 装配语义 */
    private void validate(String scope, SaveSkillRequest request) {
        if (Skill.SCOPE_AGENT.equals(scope) && request.getAgentId() == null) {
            throw new ParamException("scope=AGENT 时必须指定 agentId");
        }
        if (!Skill.SCOPE_SCENE.equals(scope)) {
            return;
        }
        String sceneKey = request.getSceneKey();
        if (sceneKey == null || sceneKey.isBlank()) {
            throw new ParamException("scope=SCENE 时必须指定 sceneKey（取值 conclude/card/topic-profile）");
        }
        // 场景键合法性收敛到 SkillScene.fromKey 单点校验，避免白名单散落多处漂移
        if (SkillScene.fromKey(sceneKey).isEmpty()) {
            throw new ParamException("非法的沉淀场景键: " + sceneKey + "（仅支持 conclude/card/topic-profile）");
        }
        if (request.getAgentId() != null) {
            throw new ParamException("scope=SCENE 时 agentId 必须为空（沉淀技能不参与 Agent 装配）");
        }
        if (request.getToolNames() != null && !request.getToolNames().isBlank()) {
            throw new ParamException("scope=SCENE 时 toolNames 必须为空（沉淀链路是无工具生成）");
        }
    }

    /**
     * 按沉淀场景查看技能（管理面：需看到该场景全部状态，含 INACTIVE，便于启停管理）。
     * <p>基于 findAll 内存过滤而非新增仓储端口方法：管理面低频查询不值得扩端口，
     * 运行时 loader 已有专用的 findActiveBySceneKey（仅 ACTIVE）。
     * <p>非法场景键直接报参数错误而非返回空列表：与 create/update 的 fromKey 校验口径一致，
     * 运营拼错键立即可见；静默空列表会把拼写错误伪装成「该场景无技能」。
     */
    public List<SkillDTO> listBySceneKey(String sceneKey) {
        SkillScene scene = SkillScene.fromKey(sceneKey)
                .orElseThrow(() -> new ParamException(
                        "非法的沉淀场景键: " + sceneKey + "（仅支持 conclude/card/topic-profile）"));
        return skillRepository.findAll().stream()
                .filter(s -> Skill.SCOPE_SCENE.equals(s.getScope()))
                .filter(s -> scene.key().equals(s.getSceneKey()))
                .map(this::toDto)
                .toList();
    }

    /**
     * R2 软限制：SCENE 技能正文超 800 字时 WARN 提示运营自查。
     * <p>超长正文会挤占沉淀生成预算（卡片 maxTokens=2048、画像仅 500），
     * 但规范长度本身无硬性标准，故只告警不阻断保存。
     */
    private void warnIfScenePromptTooLong(Skill skill, String methodName) {
        if (!Skill.SCOPE_SCENE.equals(skill.getScope()) || skill.getSystemPrompt() == null) {
            return;
        }
        int length = skill.getSystemPrompt().length();
        if (length > SCENE_PROMPT_SOFT_LIMIT) {
            LogHelper.printWarnLog(SkillAppService.class, methodName, "SKILL_SCENE_PROMPT_TOO_LONG",
                    "SCENE 技能正文超过 800 字软限制",
                    "id={} name={} sceneKey={} length={}，超长正文会挤占沉淀生成预算，请自查精简",
                    skill.getId(), skill.getName(), skill.getSceneKey(), length);
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
                .sceneKey(skill.getSceneKey())
                .status(skill.getStatus())
                .createTime(skill.getCreateTime())
                .updateTime(skill.getUpdateTime())
                .build();
    }
}

package com.dingring.infrastructure.skill;

import com.dingring.common.util.LogHelper;
import com.dingring.domain.skill.Skill;
import com.dingring.domain.skill.SkillRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * SKILL 配置热加载器（Phase F）。
 * <p>参考 {@link com.dingring.infrastructure.prompt.PromptTemplateLoader} 的「Nacos 优先 + 本地 classpath 降级」模式：
 * <ul>
 *   <li>启动时从 classpath:/skill-config.json 加载种子技能，幂等写入 skill 表（按 name 存在则跳过）</li>
 *   <li>无 Nacos Config client 依赖（当前项目未引入），reload() 作为 Nacos 监听回调的扩展点，
 *       后续接入 Nacos 后由配置监听器调用，实现热更新而无需改装配逻辑</li>
 *   <li>运行期技能状态以 skill 表为准：CRUD 走 SkillAppService，热加载只负责种子与增量</li>
 * </ul>
 * <p>种子配置结构：{@code [{"name","description","toolNames","systemPrompt","scope","agentId","status"}]}，
 * 与 skill 表列一一对应，便于后续脚本化迁移。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dingring.skill.enabled", havingValue = "true", matchIfMissing = true)
public class SkillHotReloader {

    /** 本地种子配置路径（无 Nacos 时从 classpath 加载） */
    private static final String LOCAL_CONFIG_PATH = "skill-config.json";

    private final SkillRepository skillRepository;

    /** JSON 解析器 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 启动时预加载种子技能。
     * <p>幂等：已存在同名技能则跳过（保留运营配置的修改），仅补录缺失项。
     * 加载失败仅 WARN 日志，不阻塞启动（技能可通过 CRUD API 手工维护）。
     */
    @PostConstruct
    public void loadSeedConfig() {
        try (InputStream is = new ClassPathResource(LOCAL_CONFIG_PATH).getInputStream()) {
            List<Map<String, Object>> entries = objectMapper.readValue(is, new TypeReference<>() {});
            int seeded = 0;
            for (Map<String, Object> entry : entries) {
                String name = (String) entry.get("name");
                if (name == null || name.isBlank()) {
                    continue;
                }
                if (skillRepository.findByName(name).isPresent()) {
                    continue;
                }
                skillRepository.save(toSkill(entry));
                seeded++;
            }
            LogHelper.printLog(SkillHotReloader.class, "loadSeedConfig", "SKILL_SEED",
                    "种子技能加载完成", "seedTotal={} newlySeeded={}", entries.size(), seeded);
        } catch (Exception e) {
            log.warn("本地技能种子加载失败（classpath:{}），技能将仅依赖 CRUD API 维护: {}",
                    LOCAL_CONFIG_PATH, e.getMessage());
        }
    }

    /**
     * 热更新入口（Nacos 配置监听回调扩展点）。
     * <p>当前无 Nacos 依赖，仅记录日志；后续接入后由监听器以 JSON 内容调用，
     * 复用同一套「按 name 幂等 upsert」逻辑即可实现配置热更新。
     *
     * @param dataId  配置 dataId（预留）
     * @param content 技能配置 JSON 内容
     */
    public void reload(String dataId, String content) {
        LogHelper.printLog(SkillHotReloader.class, "reload", "SKILL_RELOAD",
                "技能配置热更新触发", "dataId={} contentLen={}", dataId,
                content == null ? 0 : content.length());
        loadSeedConfig();
    }

    /** 种子 Map → Skill 实体（字段与 skill 表列一致，空值给默认值） */
    private Skill toSkill(Map<String, Object> entry) {
        Skill skill = new Skill();
        skill.setName(str(entry.get("name")));
        skill.setDescription(str(entry.get("description")));
        skill.setToolNames(str(entry.get("toolNames")));
        skill.setSystemPrompt(str(entry.get("systemPrompt")));
        skill.setScope(strOrDefault(entry.get("scope"), Skill.SCOPE_GLOBAL));
        skill.setAgentId(entry.get("agentId") == null ? null : ((Number) entry.get("agentId")).longValue());
        skill.setStatus(strOrDefault(entry.get("status"), Skill.STATUS_ACTIVE));
        return skill;
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }

    private String strOrDefault(Object o, String def) {
        String s = str(o);
        return (s == null || s.isBlank()) ? def : s;
    }
}

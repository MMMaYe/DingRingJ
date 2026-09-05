package com.dingring.app.event;

import com.dingring.app.service.GroupAppService;
import com.dingring.app.service.MessageAssembler;
import com.dingring.common.util.JsonHelper;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.event.TopicClosed;
import com.dingring.domain.group.Group;
import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.group.GroupRepository;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.group.MessageTag;
import com.dingring.domain.group.SenderType;
import com.dingring.domain.service.LlmService;
import com.dingring.domain.skill.SkillLoaderService;
import com.dingring.domain.skill.SkillScene;
import com.dingring.domain.user.UserTopicProfile;
import com.dingring.domain.user.UserTopicProfileRepository;
import com.dingring.infrastructure.prompt.PromptTemplateLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 话题级用户表现画像：监听 {@link TopicClosed}，LLM 分析用户在该话题下的表现
 * （理解程度/薄弱点/亮点/建议提升方向），存入 {@link UserTopicProfileRepository}。
 * <p>与全局画像 {@link ProfileEventHandler} 互补：本处理器关注具体话题下的能力表现，
 * 每次讨论追加一条记录，话题重启时按标题回溯形成进步轨迹。
 * <p>独立虚拟线程执行，失败仅日志，不阻塞主流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TopicProfileEventHandler {

    /** 分析输入的对话条数上限（控制 token 成本） */
    private static final int DIALOGUE_LIMIT = 30;

    /** 理解程度中文 → 枚举映射（topic-profile 模板输出强/中/弱） */
    private static final Map<String, String> LEVEL_MAP = Map.of(
            "强", "ADVANCED",
            "中", "INTERMEDIATE",
            "弱", "BEGINNER");

    private final GroupRepository groupRepository;
    private final AgentRepository agentRepository;
    private final MessageRepository messageRepository;
    private final MessageAssembler messageAssembler;
    private final UserTopicProfileRepository profileRepository;
    private final LlmService llmService;
    private final PromptTemplateLoader promptLoader;
    /** 沉淀场景技能加载：topic-profile 技能是运营增量规范，追加在出厂模板之后（依赖倒置，infrastructure 实现） */
    private final SkillLoaderService skillLoader;

    @EventListener
    public void onTopicClosed(TopicClosed event) {
        Thread.ofVirtual().name("topic-profile-" + event.getTopicId())
                .start(() -> analyze(event));
    }

    private void analyze(TopicClosed event) {
        try {
            Group group = groupRepository.findById(event.getGroupId()).orElse(null);
            if (group == null) {
                return;
            }
            Agent extractor = resolveExtractor(event.getConcluderAgentId(), group);
            if (extractor == null) {
                LogHelper.printWarnLog(TopicProfileEventHandler.class, "TopicProfileEventHandler.analyze",
                        "TOPIC_PROFILE", "无可用Agent跳过", "topicId={}", event.getTopicId());
                return;
            }

            // 1. 取 Topic 对话（过滤 NOISE，复用消息标签）
            List<GroupMessage> messages = messageRepository.findRecentByTopicId(event.getTopicId(), DIALOGUE_LIMIT);
            boolean hasUserMessage = messages.stream().anyMatch(m -> m.getSenderType() == SenderType.USER);
            if (!hasUserMessage) {
                LogHelper.printLog(TopicProfileEventHandler.class, "TopicProfileEventHandler.analyze",
                        "TOPIC_PROFILE", "无用户发言跳过", "topicId={}", event.getTopicId());
                return;
            }

            // 用户花名：从任一用户消息解析（单用户系统固定 DEFAULT_USER_ID）
            String userName = messages.stream()
                    .filter(m -> m.getSenderType() == SenderType.USER)
                    .findFirst()
                    .map(messageAssembler::resolveSenderName)
                    .orElse("用户");

            String dialogue = messages.stream()
                    .filter(m -> m.getTag() != MessageTag.NOISE)
                    .map(m -> messageAssembler.resolveSenderName(m) + ": " + m.getContent())
                    .collect(Collectors.joining("\n"));

            // 2. LLM 分析用户表现（jsonMode 强制 JSON 输出）
            String prompt = promptLoader.render("topic-profile", Map.of(
                    "userName", userName,
                    "topicTitle", event.getTitle(),
                    "dialogue", dialogue));
            if (prompt == null || prompt.isBlank()) {
                return;
            }
            // 模板承载出厂基线规范，topic-profile 场景技能是运营增量约束——渲染完成后追加在其后，再交给 LLM
            prompt = skillLoader.applySceneSkills(SkillScene.TOPIC_PROFILE, prompt);
            String raw = llmService.chat(extractor, prompt, List.of(),
                    new LlmService.CallOptions(0.0, 500, null, true, false));

            // 3. 解析 JSON → UserTopicProfile
            Map<String, Object> parsed = JsonHelper.toMap(stripCodeFence(raw));
            if (parsed.isEmpty()) {
                LogHelper.printWarnLog(TopicProfileEventHandler.class, "TopicProfileEventHandler.analyze",
                        "TOPIC_PROFILE", "画像JSON解析失败跳过", "topicId={} raw={}", event.getTopicId(), raw);
                return;
            }
            UserTopicProfile profile = toProfile(parsed, event);
            profileRepository.save(profile);
            LogHelper.printLog(TopicProfileEventHandler.class, "TopicProfileEventHandler.analyze",
                    "TOPIC_PROFILE", "话题画像已保存",
                    "topicId={} title={} level={} 薄弱点={}",
                    event.getTopicId(), event.getTitle(),
                    profile.getUnderstandingLevel(), profile.getWeakPoints());
        } catch (Exception e) {
            LogHelper.printWarnLog(TopicProfileEventHandler.class, "TopicProfileEventHandler.analyze",
                    "TOPIC_PROFILE", "话题画像分析异常跳过", "topicId={}", event.getTopicId(), e);
        }
    }

    private UserTopicProfile toProfile(Map<String, Object> parsed, TopicClosed event) {
        UserTopicProfile profile = new UserTopicProfile();
        profile.setUserId(GroupAppService.DEFAULT_USER_ID);
        profile.setTopicId(event.getTopicId());
        profile.setGroupId(event.getGroupId());
        profile.setTopicTitle(event.getTitle());
        profile.setUnderstandingLevel(mapLevel(str(parsed.get("understanding_level"))));
        profile.setWeakPoints(str(parsed.get("weak_points")));
        profile.setStrongPoints(str(parsed.get("strong_points")));
        profile.setSuggestedFocus(str(parsed.get("suggested_focus")));
        return profile;
    }

    /** 理解程度映射：模板输出中文强/中/弱 → 枚举；已是英文枚举则透传；解析不到兜底 BEGINNER */
    private String mapLevel(String level) {
        if (level == null || level.isBlank()) {
            return "BEGINNER";
        }
        String mapped = LEVEL_MAP.get(level);
        if (mapped != null) {
            return mapped;
        }
        String upper = level.toUpperCase();
        return switch (upper) {
            case "ADVANCED", "INTERMEDIATE", "BEGINNER" -> upper;
            default -> "BEGINNER";
        };
    }

    private String str(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    /** 分析 Agent：总结者优先，否则群首个成员（与 ProfileEventHandler 一致） */
    private Agent resolveExtractor(Long concluderAgentId, Group group) {
        if (concluderAgentId != null) {
            Agent concluder = agentRepository.findById(concluderAgentId).orElse(null);
            if (concluder != null) {
                return concluder;
            }
        }
        List<Agent> members = agentRepository.findByIds(group.memberAgentIds());
        return members.isEmpty() ? null : members.get(0);
    }

    /** 剥离 LLM 输出可能的 ```json 代码块包裹 */
    private String stripCodeFence(String raw) {
        if (raw == null) {
            return "";
        }
        String json = raw.trim();
        if (json.startsWith("```")) {
            json = json.replaceAll("^```(json)?\\s*", "").replaceAll("```\\s*$", "").trim();
        }
        return json;
    }
}

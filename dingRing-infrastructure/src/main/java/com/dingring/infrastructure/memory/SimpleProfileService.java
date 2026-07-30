package com.dingring.infrastructure.memory;

import com.dingring.common.constant.PromptConstants;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.service.LlmService;
import com.dingring.domain.service.ProfileService;
import com.dingring.domain.user.UserProfile;
import com.dingring.domain.user.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户画像服务 v1：LLM 读近期对话，与既有全局画像增量合并后覆盖写回（纯文本，不做向量化）。
 * <p>用户维度串行：同一用户的提炼任务用锁排队，防多群同时触发互相覆盖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimpleProfileService implements ProfileService {

    private final UserProfileRepository userProfileRepository;
    private final LlmService llmService;

    /** 用户维度串行锁 */
    private final Map<Long, Object> userLocks = new ConcurrentHashMap<>();

    @Override
    public String getProfile(Long userId) {
        return userProfileRepository.findByUserId(userId)
                .map(UserProfile::getProfileText)
                .filter(text -> !text.isBlank())
                .orElse("");
    }

    @Override
    public void extractAndMerge(Long userId, Agent extractor, String groupName, String recentDialogue) {
        if (recentDialogue == null || recentDialogue.isBlank()) {
            return;
        }
        Object lock = userLocks.computeIfAbsent(userId, k -> new Object());
        synchronized (lock) {
            try {
                String existing = getProfile(userId);
                String input = "已有画像：\n" + (existing.isBlank() ? "（暂无）" : existing)
                        + "\n\n最近对话（来自群「" + groupName + "」）：\n" + recentDialogue;
                String merged = llmService.chat(extractor, PromptConstants.USER_PROFILE_EXTRACT,
                        List.of(LlmService.ChatTurn.user(input)));
                if (merged == null || merged.isBlank()) {
                    log.warn("画像提炼返回空，跳过写回, userId={}", userId);
                    return;
                }
                UserProfile profile = userProfileRepository.findByUserId(userId)
                        .orElseGet(() -> {
                            UserProfile p = new UserProfile();
                            p.setUserId(userId);
                            return p;
                        });
                profile.setProfileText(merged.trim());
                userProfileRepository.upsert(profile);
                log.info("画像提炼写回成功, userId={}, 画像长度={}", userId, merged.trim().length());
            } catch (Exception e) {
                // 提炼失败仅日志留痕，不影响主流程
                log.warn("画像提炼失败，跳过本次, userId={}", userId, e);
            }
        }
    }
}

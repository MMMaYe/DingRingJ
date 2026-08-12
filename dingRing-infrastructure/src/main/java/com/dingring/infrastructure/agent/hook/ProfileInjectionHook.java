package com.dingring.infrastructure.agent.hook;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.service.ProfileService;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 用户画像注入 Hook（Phase D）。
 * <p>在 LLM 调用前注入跨群用户画像（长期观察的表达习惯/情绪基调/思考方式），
 * 替代 Phase C 由 ContextBuilder 静态拼接。
 * <p>注入方式：beforeModel 返回 {@code Map.of("messages", new SystemMessage(profile))}，
 * SAA 图引擎按 AppendStrategy 追加到 messages 列表。
 */
@Component
public class ProfileInjectionHook extends ModelHook {

    /** 当前单用户系统的默认用户 ID（与 GroupAppService.DEFAULT_USER_ID 一致） */
    private static final Long DEFAULT_USER_ID = 1L;

    private final ProfileService profileService;

    public ProfileInjectionHook(ProfileService profileService) {
        this.profileService = profileService;
    }

    @Override
    public String getName() {
        return "profile-injection";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
        // 从 state 读取 userId（可选，默认 1L）
        Long userId = state.<Long>value("userId").orElse(DEFAULT_USER_ID);

        String profile = profileService.getProfile(userId);
        if (profile == null || profile.isBlank()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        LogHelper.printLog(ProfileInjectionHook.class, "ProfileInjectionHook.beforeModel",
                "HOOK_PROFILE", "注入用户画像", "userId={} profile={}", userId, profile);

        return CompletableFuture.completedFuture(
                Map.of("messages", new SystemMessage(
                        "关于用户的画像记忆（长期观察所得，供你更懂他/她，不要直接复述）：\n" + profile))
        );
    }
}

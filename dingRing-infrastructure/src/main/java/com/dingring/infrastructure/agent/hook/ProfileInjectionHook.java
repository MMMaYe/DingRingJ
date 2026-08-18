package com.dingring.infrastructure.agent.hook;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.service.ProfileService;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 用户画像注入 Hook（Phase D）。
 * <p>在 Agent 运行前注入跨群用户画像（长期观察的表达习惯/情绪基调/思考方式），
 * 替代 Phase C 由 ContextBuilder 静态拼接。
 * <p>注入方式：beforeAgent 返回 {@code Map.of("messages", new SystemMessage(profile))}，
 * SAA 图引擎按 AppendStrategy 追加到 messages 列表，一次注入贯穿整个 ReAct 运行。
 * <p>为什么是 AgentHook 而非 ModelHook：画像内容一次运行内不变，beforeAgent 只执行一次；
 * ModelHook 会在 ReAct 工具循环内每次模型调用重复查库、重复注入。
 */
@Component
public class ProfileInjectionHook extends AgentHook {

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
    public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
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

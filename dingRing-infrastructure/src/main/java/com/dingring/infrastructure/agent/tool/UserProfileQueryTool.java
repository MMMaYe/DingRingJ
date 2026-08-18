package com.dingring.infrastructure.agent.tool;

import com.dingring.common.util.LogHelper;
import com.dingring.domain.service.ProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 用户画像查询工具（Phase D）。
 * <p>Agent 可主动调用此工具查询用户的表达习惯/情绪基调/思考方式等长期观察特征，
 * 替代 Phase C 由 ContextBuilder 静态拼接的方式。
 * <p>迁移自 ContextBuilder#buildSystemPrompt（已删，见 GroupContextMemoryServiceImpl） 的画像注入部分。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserProfileQueryTool {

    private final ProfileService profileService;

    /**
     * 查询用户画像。
     * <p>画像包含用户的长期观察特征（表达习惯/情绪基调/思考方式），帮助你更懂用户。
     * 不要直接复述画像内容，而是据此调整你的沟通方式。
     *
     * @param userId 用户 ID
     * @return 画像文本（无画像时返回提示语）
     */
    @Tool(description = "查询用户画像（用户的表达习惯/情绪基调/思考方式等长期观察特征），帮你更懂用户")
    public String queryUserProfile(
            @ToolParam(description = "用户 ID，群聊场景传 1 即可（当前单用户系统）") Long userId) {
        String profile = profileService.getProfile(userId);
        if (profile == null || profile.isBlank()) {
            return "暂无该用户的画像信息";
        }
        LogHelper.printLog(UserProfileQueryTool.class, "UserProfileQueryTool.queryUserProfile",
                "TOOL_USER_PROFILE", "用户画像查询", "userId={} 长度={}", userId, profile.length());
        return profile;
    }
}

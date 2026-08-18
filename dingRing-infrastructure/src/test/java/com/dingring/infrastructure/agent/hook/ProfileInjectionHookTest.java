package com.dingring.infrastructure.agent.hook;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.dingring.domain.service.ProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ProfileInjectionHook} 用户画像注入单测。
 * <p>承接自 Phase C ContextBuilder 的用户画像拼接逻辑（Phase D 迁移到 Hook）。
 * <p>策略：用真实 {@link OverAllState}（Map 构造），仅 mock {@link ProfileService}。
 */
@DisplayName("ProfileInjectionHook 用户画像注入")
class ProfileInjectionHookTest {

    private ProfileService profileService;
    private ProfileInjectionHook hook;

    @BeforeEach
    void setUp() {
        profileService = mock(ProfileService.class);
        hook = new ProfileInjectionHook(profileService);
    }

    @Test
    @DisplayName("有用户画像时注入 SystemMessage（含画像内容 + 前缀标识）")
    void shouldInjectProfileWhenPresent() {
        when(profileService.getProfile(1L)).thenReturn("用户偏好简洁、直接的表达方式");
        OverAllState state = new OverAllState(Map.of("userId", 1L));

        Map<String, Object> result = hook.beforeAgent(state, null).join();

        assertThat(result).containsKey("messages");
        SystemMessage msg = (SystemMessage) result.get("messages");
        assertThat(msg.getText())
                .contains("用户偏好简洁、直接的表达方式")   // 画像正文
                .contains("画像记忆");                     // 前缀标识
    }

    @Test
    @DisplayName("画像为空时返回空 Map（不注入）")
    void shouldReturnEmptyWhenProfileBlank() {
        when(profileService.getProfile(1L)).thenReturn("");
        OverAllState state = new OverAllState(Map.of("userId", 1L));

        Map<String, Object> result = hook.beforeAgent(state, null).join();

        assertThat(result).doesNotContainKey("messages");
    }

    @Test
    @DisplayName("state 无 userId 时用默认 1L 取画像")
    void shouldUseDefaultUserIdWhenAbsent() {
        when(profileService.getProfile(1L)).thenReturn("默认用户画像");
        OverAllState state = new OverAllState(Map.of());

        Map<String, Object> result = hook.beforeAgent(state, null).join();

        assertThat(result).containsKey("messages");
        SystemMessage msg = (SystemMessage) result.get("messages");
        assertThat(msg.getText()).contains("默认用户画像");
    }
}

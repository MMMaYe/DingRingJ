package com.dingring.infrastructure.memory;

import com.dingring.domain.agent.Agent;
import com.dingring.domain.service.LlmService;
import com.dingring.domain.user.UserProfile;
import com.dingring.domain.user.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SimpleProfileService} 用户画像服务单元测试。
 * <p>核心：既有画像 + 近期对话 → LLM 增量合并 → upsert 覆盖写回；任何失败静默跳过。
 */
@DisplayName("SimpleProfileService 用户画像")
class SimpleProfileServiceTest {

    private UserProfileRepository userProfileRepository;
    private LlmService llmService;
    private SimpleProfileService service;
    private Agent extractor;

    @BeforeEach
    void setUp() {
        userProfileRepository = mock(UserProfileRepository.class);
        llmService = mock(LlmService.class);
        service = new SimpleProfileService(userProfileRepository, llmService);
        extractor = new Agent();
        extractor.setId(10L);
        extractor.setName("老王");
    }

    private UserProfile profile(Long userId, String text) {
        UserProfile p = new UserProfile();
        p.setId(1L);
        p.setUserId(userId);
        p.setProfileText(text);
        return p;
    }

    @Nested
    @DisplayName("getProfile 读画像")
    class GetProfile {

        @Test
        @DisplayName("有画像时返回画像文本")
        void existingProfileShouldReturnText() {
            when(userProfileRepository.findByUserId(1L))
                    .thenReturn(Optional.of(profile(1L, "- 偏好数据驱动决策")));

            assertThat(service.getProfile(1L)).isEqualTo("- 偏好数据驱动决策");
        }

        @Test
        @DisplayName("无画像时返回空串")
        void missingProfileShouldReturnEmpty() {
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());

            assertThat(service.getProfile(1L)).isEmpty();
        }

        @Test
        @DisplayName("画像为空白文本时返回空串")
        void blankProfileShouldReturnEmpty() {
            when(userProfileRepository.findByUserId(1L))
                    .thenReturn(Optional.of(profile(1L, "   ")));

            assertThat(service.getProfile(1L)).isEmpty();
        }
    }

    @Nested
    @DisplayName("extractAndMerge 提炼合并")
    class ExtractAndMerge {

        @Test
        @DisplayName("首次提炼：新建画像并 upsert")
        void firstExtractionShouldCreateProfile() {
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());
            when(llmService.chat(any(Agent.class), anyString(), anyList()))
                    .thenReturn("- 表达直接\n- 决策果断");

            service.extractAndMerge(1L, extractor, "测试群", "用户: 就这么定了");

            ArgumentCaptor<UserProfile> captor = ArgumentCaptor.forClass(UserProfile.class);
            verify(userProfileRepository).upsert(captor.capture());
            assertThat(captor.getValue().getUserId()).isEqualTo(1L);
            assertThat(captor.getValue().getProfileText()).isEqualTo("- 表达直接\n- 决策果断");
        }

        @Test
        @DisplayName("增量合并：既有画像作为 LLM 输入的一部分")
        void mergeShouldFeedExistingProfileToLlm() {
            when(userProfileRepository.findByUserId(1L))
                    .thenReturn(Optional.of(profile(1L, "- 偏好简洁表达")));
            when(llmService.chat(any(Agent.class), anyString(), anyList()))
                    .thenReturn("- 偏好简洁表达\n- 情绪稳定");

            service.extractAndMerge(1L, extractor, "测试群", "用户: 嗯，可以");

            // LLM 输入包含既有画像（通过 ChatTurn 内容验证）
            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.List<LlmService.ChatTurn>> turnsCaptor =
                    ArgumentCaptor.forClass((Class) java.util.List.class);
            verify(llmService).chat(any(Agent.class), anyString(), turnsCaptor.capture());
            assertThat(turnsCaptor.getValue().get(0).content()).contains("- 偏好简洁表达");
            ArgumentCaptor<UserProfile> captor = ArgumentCaptor.forClass(UserProfile.class);
            verify(userProfileRepository).upsert(captor.capture());
            assertThat(captor.getValue().getProfileText()).contains("情绪稳定");
        }

        @Test
        @DisplayName("对话为空时直接跳过")
        void blankDialogueShouldSkip() {
            service.extractAndMerge(1L, extractor, "测试群", "  ");

            verify(llmService, never()).chat(any(), anyString(), anyList());
            verify(userProfileRepository, never()).upsert(any());
        }

        @Test
        @DisplayName("LLM 返回空时不写回")
        void blankLlmOutputShouldNotUpsert() {
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());
            when(llmService.chat(any(Agent.class), anyString(), anyList())).thenReturn("  ");

            service.extractAndMerge(1L, extractor, "测试群", "用户: hi");

            verify(userProfileRepository, never()).upsert(any());
        }

        @Test
        @DisplayName("LLM 异常时静默跳过不抛出")
        void llmFailureShouldBeSwallowed() {
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());
            when(llmService.chat(any(Agent.class), anyString(), anyList()))
                    .thenThrow(new RuntimeException("LLM 超时"));

            service.extractAndMerge(1L, extractor, "测试群", "用户: hi");

            verify(userProfileRepository, never()).upsert(any());
        }
    }
}

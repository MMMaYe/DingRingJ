package com.dingring.app.service;

import com.dingring.app.dto.response.QuestionnaireDTO;
import com.dingring.common.exception.ParamException;
import com.dingring.domain.user.UserProfile;
import com.dingring.domain.user.UserProfileRepository;
import com.dingring.domain.user.UserQuestionnaire;
import com.dingring.domain.user.UserQuestionnaireRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link QuestionnaireAppService} 单元测试。
 */
@DisplayName("QuestionnaireAppService 问卷应用服务")
class QuestionnaireAppServiceTest {

    private UserQuestionnaireRepository questionnaireRepository;
    private UserProfileRepository userProfileRepository;
    private QuestionnaireAppService service;

    @BeforeEach
    void setUp() {
        questionnaireRepository = mock(UserQuestionnaireRepository.class);
        userProfileRepository = mock(UserProfileRepository.class);
        service = new QuestionnaireAppService(questionnaireRepository, userProfileRepository);
    }

    private Map<String, Object> validAnswers() {
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("career", "MID");
        answers.put("directions", List.of("BACKEND"));
        answers.put("motivations", List.of("INTERVIEW"));
        answers.put("focusAreas", List.of("JVM", "CONCURRENCY"));
        answers.put("areaLevels", Map.of("JVM", "INTERMEDIATE", "CONCURRENCY", "ELEMENTARY"));
        answers.put("discussionDepth", "DEEP_THEORY");
        answers.put("discussionPace", "DIVERGE_THEN_CONVERGE");
        answers.put("interactionStyle", "SOCRATIC");
        answers.put("presentationForms", List.of("SOURCE_ANALYSIS"));
        answers.put("terminologyLanguage", "MIXED");
        return answers;
    }

    @Nested
    @DisplayName("get 问卷查询")
    class Get {

        @Test
        @DisplayName("未填过返回空 answers 与 13 题 schema")
        void shouldReturnEmptyAnswersWhenNeverFilled() {
            when(questionnaireRepository.findValidByUserId(1L)).thenReturn(Optional.empty());

            QuestionnaireDTO dto = service.get();

            assertThat(dto.getAnswers()).isEmpty();
            assertThat(dto.getQuestions()).hasSize(13);
        }

        @Test
        @DisplayName("已填过回显当前有效答案")
        void shouldReturnSavedAnswers() {
            UserQuestionnaire questionnaire = new UserQuestionnaire();
            questionnaire.setAnswers(Map.of("career", "MID"));
            when(questionnaireRepository.findValidByUserId(1L)).thenReturn(Optional.of(questionnaire));

            QuestionnaireDTO dto = service.get();

            assertThat(dto.getAnswers()).containsEntry("career", "MID");
        }
    }

    @Nested
    @DisplayName("submit 提交问卷")
    class Submit {

        @Test
        @DisplayName("合法答案同事务双写事实源与画像版本")
        void shouldWriteBothStores() {
            Map<String, Object> answers = validAnswers();

            service.submit(answers);

            ArgumentCaptor<UserQuestionnaire> qc = ArgumentCaptor.forClass(UserQuestionnaire.class);
            verify(questionnaireRepository).saveNewVersion(qc.capture());
            assertThat(qc.getValue().getUserId()).isEqualTo(1L);
            assertThat(qc.getValue().getAnswers()).isEqualTo(answers);

            ArgumentCaptor<UserProfile> pc = ArgumentCaptor.forClass(UserProfile.class);
            verify(userProfileRepository).saveNewVersion(pc.capture());
            assertThat(pc.getValue().getUserId()).isEqualTo(1L);
            assertThat(pc.getValue().getProfileText()).contains("中级工程师（3-5 年）");
            assertThat(pc.getValue().getProfileText()).contains("JVM（中级）、并发编程（初级）");
        }

        @Test
        @DisplayName("非法答案抛 ParamException 且不落库")
        void shouldRejectInvalidAnswers() {
            Map<String, Object> answers = validAnswers();
            answers.put("career", "CTO");

            assertThatThrownBy(() -> service.submit(answers))
                    .isInstanceOf(ParamException.class);

            verifyNoInteractions(questionnaireRepository, userProfileRepository);
        }

        @Test
        @DisplayName("answers 为 null 抛 ParamException 且不落库")
        void shouldRejectNullAnswers() {
            assertThatThrownBy(() -> service.submit(null))
                    .isInstanceOf(ParamException.class);

            verifyNoInteractions(questionnaireRepository, userProfileRepository);
        }
    }
}

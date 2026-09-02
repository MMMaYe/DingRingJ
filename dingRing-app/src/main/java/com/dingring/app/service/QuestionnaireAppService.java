package com.dingring.app.service;

import com.dingring.app.dto.response.QuestionnaireDTO;
import com.dingring.common.exception.ParamException;
import com.dingring.domain.user.QuestionnaireDefinition;
import com.dingring.domain.user.QuestionnaireProfileAssembler;
import com.dingring.domain.user.UserProfile;
import com.dingring.domain.user.UserProfileRepository;
import com.dingring.domain.user.UserQuestionnaire;
import com.dingring.domain.user.UserQuestionnaireRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 用户问卷应用服务：画像主数据的读写编排。
 * <p>提交即双写：答案事实源（user_questionnaire）+ 画像消费视图（user_profile），
 * 同一事务保证不出现"有答案无画像"的中间态；重填视为用户主动重置画像基线。
 */
@Service
@RequiredArgsConstructor
public class QuestionnaireAppService {

    private final UserQuestionnaireRepository questionnaireRepository;
    private final UserProfileRepository userProfileRepository;

    /** 问卷 schema + 当前有效答案（未填过返回空 answers） */
    public QuestionnaireDTO get() {
        Map<String, Object> answers = questionnaireRepository
                .findValidByUserId(GroupAppService.DEFAULT_USER_ID)
                .map(UserQuestionnaire::getAnswers)
                .orElseGet(Map::of);
        return QuestionnaireDTO.builder()
                .questions(toQuestionDTOs())
                .answers(answers)
                .build();
    }

    /**
     * 提交问卷：校验 → 事务内双写。
     *
     * @throws ParamException 答案缺失必填项、取值非法或联动不一致
     */
    @Transactional
    public void submit(Map<String, Object> answers) {
        if (answers == null) {
            throw new ParamException("问卷答案不能为空");
        }
        QuestionnaireDefinition.validate(answers);

        UserQuestionnaire questionnaire = new UserQuestionnaire();
        questionnaire.setUserId(GroupAppService.DEFAULT_USER_ID);
        questionnaire.setAnswers(answers);
        questionnaireRepository.saveNewVersion(questionnaire);

        UserProfile profile = new UserProfile();
        profile.setUserId(GroupAppService.DEFAULT_USER_ID);
        profile.setProfileText(QuestionnaireProfileAssembler.assemble(answers));
        userProfileRepository.saveNewVersion(profile);
    }

    private List<QuestionnaireDTO.QuestionDTO> toQuestionDTOs() {
        return QuestionnaireDefinition.questions().stream()
                .map(q -> QuestionnaireDTO.QuestionDTO.builder()
                        .key(q.key())
                        .label(q.label())
                        .dimension(q.dimension())
                        .type(q.type().name())
                        .required(q.required())
                        .options(q.options().stream()
                                .map(o -> QuestionnaireDTO.OptionDTO.builder()
                                        .value(o.value())
                                        .label(o.label())
                                        .build())
                                .toList())
                        .build())
                .toList();
    }
}

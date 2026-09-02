package com.dingring.infrastructure.persistence.repository;

import com.dingring.domain.user.UserQuestionnaire;
import com.dingring.domain.user.UserQuestionnaireRepository;
import com.dingring.infrastructure.persistence.mapper.UserQuestionnaireMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 用户问卷答案仓储实现。
 */
@Repository
@RequiredArgsConstructor
public class UserQuestionnaireRepositoryImpl implements UserQuestionnaireRepository {

    private final UserQuestionnaireMapper userQuestionnaireMapper;

    @Override
    public Optional<UserQuestionnaire> findValidByUserId(Long userId) {
        return Optional.ofNullable(userQuestionnaireMapper.findValidByUserId(userId));
    }

    @Override
    @Transactional
    public void saveNewVersion(UserQuestionnaire questionnaire) {
        userQuestionnaireMapper.expireByUserId(questionnaire.getUserId());
        Integer maxVersion = userQuestionnaireMapper.selectMaxVersion(questionnaire.getUserId());
        questionnaire.setVersion(maxVersion == null ? 1 : maxVersion + 1);
        LocalDateTime now = LocalDateTime.now();
        questionnaire.setStatus(UserQuestionnaire.STATUS_ACTIVE);
        questionnaire.setCreateTime(now);
        questionnaire.setUpdateTime(now);
        userQuestionnaireMapper.insert(questionnaire);
    }
}

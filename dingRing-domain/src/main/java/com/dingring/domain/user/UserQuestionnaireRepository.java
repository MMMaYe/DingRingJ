package com.dingring.domain.user;

import java.util.Optional;

/**
 * 用户问卷答案仓储接口。
 */
public interface UserQuestionnaireRepository {

    /** 查询当前有效的问卷答案（status = 1） */
    Optional<UserQuestionnaire> findValidByUserId(Long userId);

    /** 保存为新版本：将旧 status 置为 0，version 取历史最大值 +1，再插入 status=1 的新记录 */
    void saveNewVersion(UserQuestionnaire questionnaire);
}

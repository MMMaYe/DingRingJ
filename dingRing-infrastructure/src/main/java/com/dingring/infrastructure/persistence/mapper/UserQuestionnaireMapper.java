package com.dingring.infrastructure.persistence.mapper;

import com.dingring.domain.user.UserQuestionnaire;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户问卷答案表 Mapper。
 * <p>SQL 与结果映射见 resources/mapper/UserQuestionnaireMapper.xml
 */
@Mapper
public interface UserQuestionnaireMapper {

    /** 查询当前有效的问卷答案（status = 1） */
    UserQuestionnaire findValidByUserId(Long userId);

    /** 查询指定用户历史最大版本号（无记录返回 null） */
    Integer selectMaxVersion(Long userId);

    /** 将指定用户当前有效的问卷答案置为失效（status = 0） */
    int expireByUserId(Long userId);

    /** 插入新记录 */
    int insert(UserQuestionnaire questionnaire);
}

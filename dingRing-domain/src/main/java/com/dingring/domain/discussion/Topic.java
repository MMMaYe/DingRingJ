package com.dingring.domain.discussion;

import com.dingring.common.exception.BizException;
import com.dingring.common.exception.ErrorCode;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 主题聚合根（表 topic），讨论域核心，状态机保证讨论生命周期正确流转。
 * <p>不变式：一个群同时只有一个 IN_PROGRESS 的 Topic（version 乐观锁防并发创建）。
 */
@Data
public class Topic {

    private Long id;
    /** 所属群 */
    private Long chatGroupId;
    /** 主题标题（同群内不重复） */
    private String title;
    /** 状态：IN_PROGRESS / CONCLUDING / CLOSED / ARCHIVED */
    private TopicStatus status;
    /** 讨论结论（结束时生成，STAR 框架 Markdown） */
    private String conclusion;
    /** 结束时间 */
    private LocalDateTime closedAt;
    /** 结束操作人 ID */
    private Long closedBy;
    /** 乐观锁 */
    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public boolean isInProgress() {
        return status == TopicStatus.IN_PROGRESS;
    }

    /** 触发结束：IN_PROGRESS -> CONCLUDING */
    public void startConcluding(Long operatorId) {
        transitTo(TopicStatus.CONCLUDING);
        this.closedBy = operatorId;
    }

    /** 结论生成成功：CONCLUDING -> CLOSED */
    public void close(String conclusion) {
        transitTo(TopicStatus.CLOSED);
        this.conclusion = conclusion;
        this.closedAt = LocalDateTime.now();
    }

    /** 结论生成失败回退：CONCLUDING -> IN_PROGRESS */
    public void rollbackToInProgress() {
        transitTo(TopicStatus.IN_PROGRESS);
        this.closedBy = null;
    }

    /** 手动归档：CLOSED -> ARCHIVED */
    public void archive() {
        transitTo(TopicStatus.ARCHIVED);
    }

    private void transitTo(TopicStatus target) {
        if (status == null || !status.canTransitTo(target)) {
            throw new BizException(ErrorCode.TOPIC_NOT_IN_PROGRESS,
                    "Topic 状态不允许从 " + status + " 流转到 " + target);
        }
        this.status = target;
    }
}

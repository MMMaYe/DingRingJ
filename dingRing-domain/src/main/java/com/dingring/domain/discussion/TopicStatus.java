package com.dingring.domain.discussion;

/**
 * Topic 状态枚举（状态机，见技术方案 6.1）。
 *
 * <pre>
 * IN_PROGRESS --@专家/最大轮次--> CONCLUDING --结论成功--> CLOSED --手动归档--> ARCHIVED
 *      ^                             |
 *      +--------结论生成失败(回退)-----+
 * </pre>
 */
public enum TopicStatus {

    /** 讨论进行中，接收消息 */
    IN_PROGRESS,
    /** 已触发结束，AI 生成结论中 */
    CONCLUDING,
    /** 结论已生成，讨论关闭 */
    CLOSED,
    /** 归档，只读 */
    ARCHIVED;

    /** 状态机流转校验 */
    public boolean canTransitTo(TopicStatus target) {
        return switch (this) {
            case IN_PROGRESS -> target == CONCLUDING;
            case CONCLUDING -> target == CLOSED || target == IN_PROGRESS;
            case CLOSED -> target == ARCHIVED;
            case ARCHIVED -> false;
        };
    }
}

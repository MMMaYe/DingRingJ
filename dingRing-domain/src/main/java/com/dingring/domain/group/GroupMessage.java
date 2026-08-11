package com.dingring.domain.group;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 群消息实体（表 message）。
 * <p>topic_id 由后端入库时自动填充：查群的活跃 Topic，有则归属，无则为 null（闲聊）。
 * <p>消息标签（tag）与观点摘要（viewpoint）不设独立列，统一存于扩展字段 feature 的 JSON 中
 * （键名 tag / viewpoint），由 {@link #getTag()} / {@link #getViewpoint()} 便捷读写。
 */
@Data
public class GroupMessage {

    /** feature JSON 中的标签键 */
    public static final String FEATURE_TAG = "tag";
    /** feature JSON 中的观点摘要键 */
    public static final String FEATURE_VIEWPOINT = "viewpoint";

    private Long id;
    /** 所属群 */
    private Long chatGroupId;
    /** 所属主题（可空，null=闲聊） */
    private Long topicId;
    /** 发送者 ID */
    private Long senderId;
    /** USER / AGENT / SYSTEM */
    private SenderType senderType;
    /** TEXT / IMAGE / FILE / SYSTEM_NOTICE */
    private MessageType messageType;
    /** 消息内容（支持 Markdown） */
    private String content;
    /** 回复的消息 ID（可空） */
    private Long replyToMessageId;
    /** 扩展字段（tag/viewpoint 等标签存于此） */
    private Map<String, Object> feature;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 消息标签（KEY/NOISE/VIEWPOINT，null=尚未打标签）；读自 feature["tag"] */
    public MessageTag getTag() {
        Object v = feature == null ? null : feature.get(FEATURE_TAG);
        if (v == null) {
            return null;
        }
        if (v instanceof MessageTag tag) {
            return tag;
        }
        try {
            return MessageTag.valueOf(v.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void setTag(MessageTag tag) {
        featureMap().put(FEATURE_TAG, tag == null ? null : tag.name());
    }

    /** 观点摘要（VIEWPOINT 消息的 LLM 摘要，可空）；读自 feature["viewpoint"] */
    public String getViewpoint() {
        Object v = feature == null ? null : feature.get(FEATURE_VIEWPOINT);
        return v == null ? null : v.toString();
    }

    public void setViewpoint(String viewpoint) {
        featureMap().put(FEATURE_VIEWPOINT, viewpoint);
    }

    /** 惰性初始化 feature，保证便捷方法写入安全 */
    private Map<String, Object> featureMap() {
        if (feature == null) {
            feature = new HashMap<>();
        }
        return feature;
    }
}

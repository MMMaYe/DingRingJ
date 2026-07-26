package com.dingring.adapter.rest;

import com.dingring.app.dto.request.CreateTopicRequest;
import com.dingring.app.dto.response.ConclusionDTO;
import com.dingring.app.dto.response.KnowledgeCardDTO;
import com.dingring.app.dto.response.MessageDTO;
import com.dingring.app.dto.response.TopicSummary;
import com.dingring.app.service.CardAppService;
import com.dingring.app.service.TopicAppService;
import com.dingring.common.response.ApiResponse;
import com.dingring.common.response.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 主题讨论 REST API。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TopicController {

    private final TopicAppService topicAppService;
    private final CardAppService cardAppService;

    /** 创建主题（开始一场讨论） */
    @PostMapping("/groups/{groupId}/topics")
    public ApiResponse<TopicSummary> createTopic(@PathVariable Long groupId,
                                                 @Valid @RequestBody CreateTopicRequest request) {
        return ApiResponse.ok(topicAppService.createTopic(groupId, request.getTitle()));
    }

    /** 群的主题列表 */
    @GetMapping("/groups/{groupId}/topics")
    public ApiResponse<List<TopicSummary>> listTopics(@PathVariable Long groupId) {
        return ApiResponse.ok(topicAppService.listByGroup(groupId));
    }

    /** 群消息分页（含闲聊，群聊主窗口用） */
    @GetMapping("/groups/{groupId}/messages")
    public ApiResponse<PageResult<MessageDTO>> groupMessages(@PathVariable Long groupId,
                                                             @RequestParam(defaultValue = "1") int page,
                                                             @RequestParam(defaultValue = "50") int pageSize) {
        return ApiResponse.ok(topicAppService.groupMessages(groupId, page, pageSize));
    }

    /** 结束讨论（触发专家生成结论） */
    @PostMapping("/topics/{topicId}/conclude")
    public ApiResponse<Void> conclude(@PathVariable Long topicId) {
        topicAppService.conclude(topicId);
        return ApiResponse.ok();
    }

    /** 主题消息分页 */
    @GetMapping("/topics/{topicId}/messages")
    public ApiResponse<PageResult<MessageDTO>> messages(@PathVariable Long topicId,
                                                        @RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "50") int pageSize) {
        return ApiResponse.ok(topicAppService.messages(topicId, page, pageSize));
    }

    /** 主题结论 */
    @GetMapping("/topics/{topicId}/conclusion")
    public ApiResponse<ConclusionDTO> conclusion(@PathVariable Long topicId) {
        return ApiResponse.ok(topicAppService.conclusion(topicId));
    }

    /** 主题生成的知识卡片 */
    @GetMapping("/topics/{topicId}/cards")
    public ApiResponse<List<KnowledgeCardDTO>> topicCards(@PathVariable Long topicId) {
        return ApiResponse.ok(cardAppService.listByTopic(topicId));
    }
}

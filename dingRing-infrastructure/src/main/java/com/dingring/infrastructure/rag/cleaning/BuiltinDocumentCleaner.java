package com.dingring.infrastructure.rag.cleaning;

import com.dingring.common.util.LogHelper;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.knowledgebase.CleaningSubmission;
import com.dingring.domain.service.LlmService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 内置执行方（3.4）：摄入管道内直调清洗 LLM。
 * <p>模型连接复用 dingring.rag.cleaning.agent-id 指定的 Agent（baseUrl/apiKey），
 * dingring.rag.cleaning.model 非空时覆盖模型名。JSON 模式输出协议 JSON。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "dingring.rag.cleaning.executor", havingValue = "builtin", matchIfMissing = true)
public class BuiltinDocumentCleaner {

    private final LlmService llmService;
    private final AgentRepository agentRepository;
    private final CleaningSubmissionValidator validator;
    private final Long cleaningAgentId;
    private final String cleaningModel;

    public BuiltinDocumentCleaner(LlmService llmService,
                                   AgentRepository agentRepository,
                                   CleaningSubmissionValidator validator,
                                   @Value("${dingring.rag.cleaning.agent-id:6}") Long cleaningAgentId,
                                   @Value("${dingring.rag.cleaning.model:}") String cleaningModel) {
        this.llmService = llmService;
        this.agentRepository = agentRepository;
        this.validator = validator;
        this.cleaningAgentId = cleaningAgentId;
        this.cleaningModel = cleaningModel;
    }

    /**
     * 全文清洗：返回协议提交，失败抛异常（由管道置 FAILED 并计入 attempt，不降级）。
     */
    public CleaningSubmission clean(String rawMarkdown) {
        Optional<Agent> agentOpt = agentRepository.findById(cleaningAgentId);
        if (agentOpt.isEmpty()) {
            throw new IllegalStateException("清洗 Agent 不存在: " + cleaningAgentId);
        }
        Agent agent = agentOpt.get();
        if (cleaningModel != null && !cleaningModel.isBlank()) {
            agent.setModelName(cleaningModel);
        }
        LlmService.CallOptions options = new LlmService.CallOptions(0.0, null, 300L, true, false);
        String response = llmService.chat(agent, CleaningPromptTemplate.CONSTRAINTS,
                List.of(LlmService.ChatTurn.user(CleaningPromptTemplate.userMessage(rawMarkdown))), options);

        CleaningSubmission submission = validator.parseAndValidate(response);
        if (submission == null) {
            throw new IllegalStateException("清洗输出不符合协议（cleanMarkdown 为空或 JSON 非法）");
        }
        LogHelper.printLog(BuiltinDocumentCleaner.class, "clean", "KB_CLEANING",
                "内置清洗完成", "title={} chars={}",
                        submission.documentTitle() == null ? "(none)" : submission.documentTitle(),
                        submission.cleanMarkdown().length());
        return submission;
    }
}

package com.dingring.app.workflow;

import com.dingring.domain.workflow.DiscussionRules;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 群聊流程配置：把 application.yml 的散落配置组装为 {@link DiscussionRules} 强类型 record。
 * <p>DiscussionEngine 和 SaaWorkflow 通过 DiscussionRules 读取业务规则，不再各自 @Value 注入。
 */
@Configuration
public class WorkflowConfig {

    @Bean
    public DiscussionRules discussionRules(
            @Value("${dingring.orchestrator.diverge-pace-ms:2000}") long divergePaceMs,
            @Value("${dingring.orchestrator.max-auto-rounds:5}") int maxAutoRounds,
            @Value("${dingring.orchestrator.max-diverge-rounds:3}") int maxDivergeRounds,
            @Value("${dingring.orchestrator.max-rounds:100}") int maxRounds,
            @Value("${dingring.orchestrator.profile-extract-threshold:15}") int profileExtractThreshold,
            @Value("${dingring.orchestrator.backfill-limit:15}") int backfillLimit,
            @Value("${dingring.orchestrator.context-window:200}") int contextWindow,
            @Value("${dingring.orchestrator.conclude-confirm-timeout-ms:300000}") long concludeConfirmTimeoutMs
    ) {
        return new DiscussionRules(
                divergePaceMs,
                maxAutoRounds,
                maxDivergeRounds,
                maxRounds,
                profileExtractThreshold,
                backfillLimit,
                contextWindow,
                concludeConfirmTimeoutMs
        );
    }
}

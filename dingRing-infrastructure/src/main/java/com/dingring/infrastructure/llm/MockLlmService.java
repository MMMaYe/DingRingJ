package com.dingring.infrastructure.llm;

import com.dingring.domain.agent.Agent;
import com.dingring.domain.service.LlmService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mock LLM 实现（dingring.llm.mock=true 启用），无需真实 API Key 即可演示核心闭环。
 * <p>根据 systemPrompt 中的任务标记返回模拟发言 / STAR 结论 / 卡片 JSON。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "dingring.llm.mock", havingValue = "true")
public class MockLlmService implements LlmService {

    @Override
    public String chat(Agent agent, String systemPrompt, List<ChatTurn> messages) {
        simulateLatency();
        String lastUser = messages.isEmpty() ? "" : messages.get(messages.size() - 1).content();
        if (systemPrompt != null && systemPrompt.contains("STAR")) {
            return mockConclusion(lastUser);
        }
        if (systemPrompt != null && systemPrompt.contains("知识卡片")) {
            return mockCards();
        }
        return mockReply(agent, lastUser);
    }

    private String mockReply(Agent agent, String lastUser) {
        String[] templates = {
                "我是 %s，针对刚才的讨论我补充一点：%s 这个问题可以从架构分层的角度拆解，先明确边界再谈实现。",
                "（%s 观点）我认为核心矛盾在于取舍——%s。建议先做最小可行方案，跑通后再迭代优化。",
                "作为 %s，我持保留意见。%s 的前提假设值得推敲，建议先补充数据验证再下结论。",
                "%s 来总结一下前面的分歧点：%s。我倾向于渐进式方案，风险更可控。"
        };
        String t = templates[ThreadLocalRandom.current().nextInt(templates.length)];
        String topicHint = lastUser.length() > 40 ? lastUser.substring(0, 40) + "…" : lastUser;
        return String.format(t, agent.getName(), topicHint);
    }

    private String mockConclusion(String context) {
        return """
                ## 讨论结论（STAR）

                ### S - 背景（Situation）
                群内围绕该主题展开了多轮讨论，各 Agent 从不同专业视角发表了观点。

                ### T - 任务（Task）
                需要在多个候选方案中收敛出一个可执行、风险可控的最终结论。

                ### A - 行动（Action）
                1. 梳理了各方观点的核心分歧点与共识区；
                2. 对比了渐进式方案与激进式方案的成本收益；
                3. 结合讨论中提出的数据验证建议进行了可行性评估。

                ### R - 结果（Result）
                最终达成共识：采用渐进式落地路径，先完成最小可行版本验证核心假设，再按里程碑迭代扩展。
                """;
    }

    private String mockCards() {
        return """
                [
                  {"question": "本次讨论采用了什么决策框架来收敛结论？", "answer": "STAR 框架（Situation/Task/Action/Result），先对齐背景与任务，再评估行动方案得出结果。", "category": "方法论"},
                  {"question": "渐进式方案相比激进式方案的核心优势是什么？", "answer": "风险可控：先用最小可行版本验证核心假设，失败成本低，可按里程碑迭代扩展。", "category": "架构决策"},
                  {"question": "多 Agent 讨论收敛的关键步骤是什么？", "answer": "先梳理分歧点与共识区，再做成本收益对比，最后结合数据验证评估可行性。", "category": "协作模式"}
                ]
                """;
    }

    private void simulateLatency() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(300, 900));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

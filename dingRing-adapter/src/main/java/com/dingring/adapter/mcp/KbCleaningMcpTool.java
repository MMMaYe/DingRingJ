package com.dingring.adapter.mcp;

import com.dingring.app.service.CleaningSubmissionService;
import com.dingring.common.exception.BizException;
import com.dingring.common.util.LogHelper;
import com.dingring.infrastructure.rag.cleaning.CleaningPromptTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 知识库清洗提交 MCP 工具（3.4）。
 * <p>外部执行方（TRAE / Claude Code）清洗完成后经 MCP 提交结果的唯一入口。
 * MCP 侧刻意只提供这一个工具——只做"收结果"，任务发现与原文读取由用户决定
 * （前端 CLEANING_WAITING 徽章展示 fileId，原文由用户直接交给外部 Agent），
 * 省掉 list/get 类工具和任务领取语义。
 * <p>工具描述即清洗约束模板：外部 Agent 按描述完成清洗，
 * 与内置执行方（BuiltinDocumentCleaner 的 system prompt）共用同一口径。
 * <p>拒绝语义：校验失败（非待清洗状态 / 文件已替换 / 协议不符）返回拒绝原因文本，
 * 任务保持 CLEANING_WAITING 可修正后重新提交（3.4.2）——工具不抛异常，
 * 让 Agent 能读到原因并自纠。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbCleaningMcpTool {

    private final CleaningSubmissionService cleaningSubmissionService;

    @Tool(name = "kb_cleaning_submit", description = CleaningPromptTemplate.MCP_TOOL_DESCRIPTION)
    public String kbCleaningSubmit(
            @ToolParam(description = "待清洗文件 ID（前端 CLEANING_WAITING 徽章上展示的 fileId）",
                    required = true) Long fileId,
            @ToolParam(description = "清洗结果 JSON：{\"documentTitle\": \"文档标题\", \"cleanMarkdown\": \"清洗后的全文\"}",
                    required = true) String submissionJson) {
        long start = System.currentTimeMillis();
        String outcome = "通过";
        try {
            return cleaningSubmissionService.submit(fileId, submissionJson);
        } catch (BizException e) {
            // 拒绝原因回传给外部 Agent：可修正（协议问题）或放弃（状态/过期问题）
            // ParamException extends BizException，统一在此捕获
            outcome = "拒绝";
            return "提交被拒绝：" + e.getMessage();
        } catch (Exception e) {
            outcome = "异常";
            LogHelper.printWarnLog(KbCleaningMcpTool.class, "kbCleaningSubmit", "KB_MCP_SUBMIT",
                    "清洗提交异常", "fileId={} 错误: {}", fileId, e.getMessage());
            return "提交处理异常：" + e.getMessage() + "，可稍后重试";
        } finally {
            // 10.1 摄入与清洗结构化事件：executor/fileId/校验结果/提交延迟（仅外部执行方）
            LogHelper.printLog(KbCleaningMcpTool.class, "kbCleaningSubmit", "KB_MCP_SUBMIT",
                    "MCP 清洗提交", "executor=external-mcp fileId={} 结果={} 延迟={}ms",
                    fileId, outcome, System.currentTimeMillis() - start);
        }
    }
}

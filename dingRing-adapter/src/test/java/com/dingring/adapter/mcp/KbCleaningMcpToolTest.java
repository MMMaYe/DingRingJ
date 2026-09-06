package com.dingring.adapter.mcp;

import com.dingring.app.service.CleaningSubmissionService;
import com.dingring.common.exception.ParamException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link KbCleaningMcpTool} P3 用例：拒绝原因回传给外部 Agent（不抛异常，Agent 可自纠）。
 */
@DisplayName("MCP 清洗提交工具：拒绝语义")
class KbCleaningMcpToolTest {

    private CleaningSubmissionService submissionService;
    private KbCleaningMcpTool tool;

    @BeforeEach
    void setUp() {
        submissionService = mock(CleaningSubmissionService.class);
        tool = new KbCleaningMcpTool(submissionService);
    }

    @Test
    @DisplayName("提交成功：透传服务结果")
    void shouldReturnSubmitResult() {
        when(submissionService.submit(12L, "{}")).thenReturn("提交成功，已进入摄入管道");

        String result = tool.kbCleaningSubmit(12L, "{}");

        assertThat(result).contains("提交成功");
    }

    @Test
    @DisplayName("校验拒绝：返回拒绝原因文本，任务可修正后重提")
    void shouldReturnRejectionReason() {
        when(submissionService.submit(anyLong(), anyString()))
                .thenThrow(new ParamException("任务非待清洗状态: FAILED"));

        String result = tool.kbCleaningSubmit(12L, "{}");

        assertThat(result).contains("提交被拒绝").contains("任务非待清洗状态");
    }

    @Test
    @DisplayName("意外异常：返回可重试提示，不向传输层抛异常")
    void shouldReturnRetryHintOnUnexpectedError() {
        when(submissionService.submit(anyLong(), anyString()))
                .thenThrow(new RuntimeException("db down"));

        String result = tool.kbCleaningSubmit(12L, "{}");

        assertThat(result).contains("提交处理异常").contains("重试");
    }
}

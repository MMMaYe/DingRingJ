package com.dingring.adapter.rest;

import com.dingring.common.response.ApiResponse;
import com.dingring.infrastructure.observability.LogEventRecord;
import com.dingring.infrastructure.observability.LogFileCollector;
import com.dingring.infrastructure.observability.LogQueryService;
import com.dingring.infrastructure.observability.LogQueryService.LlmCall;
import com.dingring.infrastructure.observability.LogQueryService.QueryResult;
import com.dingring.infrastructure.observability.LogQueryService.Stats;
import com.dingring.infrastructure.observability.LogQueryService.TraceDetail;
import com.dingring.infrastructure.observability.LogQueryService.TraceSummary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 日志观测 REST API。
 *
 * <p>仅当配置 {@code dingring.debug.observability.enabled=true} 时该控制器才注册（参考 TestController 模式）。
 * 生产配置必须置为 false。
 *
 * <h3>接口清单</h3>
 * <ul>
 *   <li>{@code GET /api/logs/stats} —— 总览统计（日志总数、ERROR/WARN 计数、LLM 调用统计、Token 总量）</li>
 *   <li>{@code GET /api/logs/events} —— 事件列表（游标增量+多条件过滤，时间降序）</li>
 *   <li>{@code GET /api/logs/events/{seq}} —— 单条事件全文（按 cursor 懒读）</li>
 *   <li>{@code GET /api/logs/traces} —— Trace 摘要列表（按 traceId 聚合，时间降序）</li>
 *   <li>{@code GET /api/logs/traces/{traceId}} —— Trace 详情（事件流+关联入口+LLM 调用）</li>
 *   <li>{@code GET /api/logs/llm-calls} —— LLM 调用列表（耗时+token 关联）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/logs")
@ConditionalOnProperty(name = "dingring.debug.observability.enabled", havingValue = "true")
public class LogController {

    private final LogQueryService queryService;
    private final LogFileCollector collector;

    public LogController(LogQueryService queryService, LogFileCollector collector) {
        this.queryService = queryService;
        this.collector = collector;
    }

    @GetMapping("/stats")
    public ApiResponse<Stats> stats() {
        return ApiResponse.ok(queryService.getStats());
    }

    @GetMapping("/events")
    public ApiResponse<QueryResult> events(
            @RequestParam(value = "afterSeq", required = false) Long afterSeq,
            @RequestParam(value = "limit", defaultValue = "2000") int limit,
            @RequestParam(value = "level", required = false) String level,
            @RequestParam(value = "eventCode", required = false) String eventCode,
            @RequestParam(value = "traceId", required = false) String traceId,
            @RequestParam(value = "keyword", required = false) String keyword
    ) {
        return ApiResponse.ok(queryService.queryEvents(afterSeq, limit, level, eventCode, traceId, keyword));
    }

    @GetMapping("/events/{seq}")
    public ApiResponse<LogEventRecord> eventBySeq(
            @PathVariable long seq,
            @RequestParam(value = "includeMessage", defaultValue = "false") boolean includeMessage
    ) {
        LogEventRecord record = collector.findBySeq(seq);
        return ApiResponse.ok(record);
    }

    @GetMapping("/traces")
    public ApiResponse<List<TraceSummary>> traces(
            @RequestParam(value = "groupId", required = false) String groupId
    ) {
        return ApiResponse.ok(queryService.listTraces(groupId));
    }

    @GetMapping("/traces/{traceId}")
    public ApiResponse<TraceDetail> traceDetail(@PathVariable String traceId) {
        return ApiResponse.ok(queryService.getTraceDetail(traceId));
    }

    @GetMapping("/llm-calls")
    public ApiResponse<List<LlmCall>> llmCalls() {
        return ApiResponse.ok(queryService.listLlmCalls());
    }
}

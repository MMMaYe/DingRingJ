package com.dingring.infrastructure.observability;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 日志查询聚合服务。
 *
 * <p>每个查询方法先调用 {@link LogFileCollector#consumeNew()} 保证数据新鲜，
 * 再基于内存缓存做线性扫描 O(n)（n≤10000，毫秒级），无需额外索引。
 *
 * <h3>增量协议（seq 游标）</h3>
 * 文件字节偏移由采集器服务端单点推进，前端只持有 seq：
 * <ul>
 *   <li>{@code afterSeq<=0}：返回匹配过滤条件的最新 limit 条（seq 降序，即时间降序）</li>
 *   <li>{@code afterSeq>0}：返回 seq 大于该值的匹配事件（seq 升序，前端自行归并排序）</li>
 *   <li>响应恒携带 {@code latestSeq}（采集器已分配的最大 seq），前端下次原样回传即可不重不丢</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "dingring.debug.observability.enabled", havingValue = "true")
public class LogQueryService {

    private final LogFileCollector collector;

    public LogQueryService(LogFileCollector collector) {
        this.collector = collector;
    }

    /**
     * 事件列表查询：seq 增量 + 多条件过滤。
     */
    public QueryResult queryEvents(Long afterSeq, int limit, String level, String eventCode, String traceId, String keyword) {
        if (limit <= 0) limit = 2000;
        collector.consumeNew();

        List<LogEventRecord> all = collector.snapshot();
        List<LogEventRecord> filtered = all.stream()
                .filter(e -> afterSeq == null || afterSeq <= 0 || e.seq() > afterSeq)
                .filter(e -> level == null || level.isEmpty() || level.equalsIgnoreCase(e.level()))
                .filter(e -> eventCode == null || eventCode.isEmpty() || eventCode.equals(e.eventCode()))
                .filter(e -> traceId == null || traceId.isEmpty() || traceId.equals(e.traceId()))
                .filter(e -> keyword == null || keyword.isEmpty()
                        || (e.message() != null && e.message().contains(keyword))
                        || (e.eventName() != null && e.eventName().contains(keyword))
                        || (e.summary() != null && e.summary().contains(keyword)))
                .collect(Collectors.toList());

        List<LogEventRecord> result;
        if (afterSeq == null || afterSeq <= 0) {
            // 首次/过滤变化：最新 limit 条，seq 降序（最新在前）
            result = filtered.stream()
                    .sorted(Comparator.comparingLong(LogEventRecord::seq).reversed())
                    .limit(limit)
                    .collect(Collectors.toList());
        } else {
            // 增量：seq 升序返回（天然时间正序），上限放宽避免一次拉爆
            result = filtered.stream()
                    .sorted(Comparator.comparingLong(LogEventRecord::seq))
                    .limit((long) limit * 4)
                    .collect(Collectors.toList());
        }
        return new QueryResult(result, collector.getLatestSeq(), collector.getFileSize());
    }

    /**
     * 统计概览。
     */
    public Stats getStats() {
        collector.consumeNew();
        List<LogEventRecord> all = collector.snapshot();
        long total = all.size();
        long errorCount = all.stream().filter(e -> "ERROR".equalsIgnoreCase(e.level())).count();
        long warnCount = all.stream().filter(e -> "WARN".equalsIgnoreCase(e.level())).count();

        // LLM 调用：CHAT_RESPONSE 事件计数
        List<LogEventRecord> llmResponses = all.stream()
                .filter(e -> "CHAT_RESPONSE".equals(e.eventCode()))
                .collect(Collectors.toList());
        long llmCallCount = llmResponses.size();
        long totalLatency = 0;
        long latencySamples = 0;
        long totalTokens = 0;
        for (LogEventRecord r : llmResponses) {
            if (r.fields() != null) {
                Object latency = r.fields().get("latencyMs");
                if (latency instanceof Number n) {
                    totalLatency += n.longValue();
                    latencySamples++;
                }
            }
        }
        // token 总量：从 BEFORE_CALL_LOG 的 usage 汇总
        for (LogEventRecord r : all) {
            if (!"BEFORE_CALL_LOG".equals(r.eventCode()) || r.fields() == null) continue;
            Object totalT = r.fields().get("totalTokens");
            if (totalT instanceof Number n) {
                totalTokens += n.longValue();
            }
        }
        long avgLatency = latencySamples > 0 ? totalLatency / latencySamples : 0;
        return new Stats(total, errorCount, warnCount, llmCallCount, avgLatency, totalTokens);
    }

    /**
     * Trace 摘要列表：按 traceId 聚合，时间降序。
     */
    public List<TraceSummary> listTraces(String groupIdFilter) {
        collector.consumeNew();
        List<LogEventRecord> all = collector.snapshot();
        Map<String, TraceSummary> traces = new LinkedHashMap<>();
        for (LogEventRecord e : all) {
            if (e.traceId() == null || e.traceId().isEmpty()) continue;
            // 入口段 G{g}-T? 由 ChatOrchestrator/WsMessageDispatcher 写入，不属于引擎处理流，单独关联不列示
            if (e.traceId().endsWith("-T?")) continue;
            if (groupIdFilter != null && !groupIdFilter.isEmpty()
                    && !groupIdFilter.equals(e.groupId())) continue;
            TraceSummary summary = traces.computeIfAbsent(e.traceId(), k -> {
                TraceSummary s = new TraceSummary();
                s.traceId = k;
                s.groupId = e.groupId();
                s.eventCount = 0;
                s.errorCount = 0;
                s.startTimestamp = e.timestamp();
                s.endTimestamp = e.timestamp();
                s.title = e.summary();
                return s;
            });
            summary.eventCount++;
            if ("ERROR".equalsIgnoreCase(e.level())) summary.errorCount++;
            if ("CHAT_RESPONSE".equals(e.eventCode())) summary.llmCount++;
            if (e.timestamp() < summary.startTimestamp) summary.startTimestamp = e.timestamp();
            if (e.timestamp() > summary.endTimestamp) summary.endTimestamp = e.timestamp();
            if (e.costMs() != null && (summary.maxCost == null || e.costMs() > summary.maxCost)) {
                summary.maxCost = e.costMs();
            }
        }
        return traces.values().stream()
                .sorted(Comparator.comparingLong(TraceSummary::getEndTimestamp).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Trace 详情：事件流（seq 升序=文件真实顺序）+ LLM 调用 + 关联入口事件（同 groupId 时间窗口）。
     */
    public TraceDetail getTraceDetail(String traceId) {
        collector.consumeNew();
        List<LogEventRecord> all = collector.snapshot();
        // 主事件流
        List<LogEventRecord> flow = all.stream()
                .filter(e -> traceId.equals(e.traceId()))
                .sorted(Comparator.comparingLong(LogEventRecord::seq))
                .collect(Collectors.toList());

        // 关联入口事件（G{g}-T? 段，同 groupId，时间窗口±30s）
        List<LogEventRecord> relatedEntries = List.of();
        if (!flow.isEmpty()) {
            long start = flow.get(0).timestamp();
            long window = 30_000L; // 30s
            String groupId = flow.get(0).groupId();
            relatedEntries = all.stream()
                    .filter(e -> e.traceId() != null && e.traceId().endsWith("-T?"))
                    .filter(e -> groupId != null && groupId.equals(e.groupId()))
                    .filter(e -> Math.abs(e.timestamp() - start) < window)
                    .sorted(Comparator.comparingLong(LogEventRecord::seq))
                    .collect(Collectors.toList());
        }

        // LLM 调用
        List<LlmCall> llmCalls = extractLlmCalls(flow);
        return new TraceDetail(flow, relatedEntries, llmCalls);
    }

    /**
     * LLM 调用列表（按 traceId + 时间邻近匹配 BEFORE_CALL_LOG 的 token 用量）。
     */
    public List<LlmCall> listLlmCalls() {
        collector.consumeNew();
        return extractLlmCalls(collector.snapshot());
    }

    private List<LlmCall> extractLlmCalls(List<LogEventRecord> events) {
        // CHAT_RESPONSE 提供 agent/model/latency，BEFORE_CALL_LOG 提供 token 用量
        List<LlmCall> calls = new ArrayList<>();
        for (LogEventRecord r : events) {
            if (!"CHAT_RESPONSE".equals(r.eventCode())) continue;
            LlmCall call = new LlmCall();
            call.seq = r.seq();
            call.traceId = r.traceId();
            call.timestamp = r.timestamp();
            call.latencyMs = 0;
            if (r.fields() != null) {
                Object agent = r.fields().get("agent");
                Object model = r.fields().get("model");
                Object latency = r.fields().get("latencyMs");
                if (agent instanceof String s) call.agent = s;
                if (model instanceof String m) call.model = m;
                if (latency instanceof Number n) call.latencyMs = n.longValue();
            }
            calls.add(call);
        }
        // 关联 token：从同 traceId 邻近的 BEFORE_CALL_LOG 取 usage
        for (LlmCall call : calls) {
            LogEventRecord nearest = findNearestBeforeCall(events, call.traceId, call.timestamp);
            if (nearest != null && nearest.fields() != null) {
                Object prompt = nearest.fields().get("promptTokens");
                Object completion = nearest.fields().get("completionTokens");
                Object total = nearest.fields().get("totalTokens");
                if (prompt instanceof Number n) call.promptTokens = n.longValue();
                if (completion instanceof Number n) call.completionTokens = n.longValue();
                if (total instanceof Number n) call.totalTokens = n.longValue();
            }
        }
        calls.sort(Comparator.comparingLong(LlmCall::getTimestamp).reversed());
        return calls;
    }

    private LogEventRecord findNearestBeforeCall(List<LogEventRecord> events, String traceId, long timestamp) {
        LogEventRecord nearest = null;
        long nearestDelta = Long.MAX_VALUE;
        for (LogEventRecord e : events) {
            if (!"BEFORE_CALL_LOG".equals(e.eventCode())) continue;
            if (!traceId.equals(e.traceId())) continue;
            long delta = Math.abs(e.timestamp() - timestamp);
            if (delta < nearestDelta && delta < 10_000) { // 10s 窗口
                nearestDelta = delta;
                nearest = e;
            }
        }
        return nearest;
    }

    // ==================== DTO ====================

    public record QueryResult(List<LogEventRecord> events, long latestSeq, long fileSize) {}
    public record Stats(long total, long errorCount, long warnCount,
                        long llmCallCount, long avgLatencyMs, long totalTokens) {}

    public static class TraceSummary {
        public String traceId;
        public String groupId;
        public String title;
        public int eventCount;
        public int errorCount;
        public int llmCount;
        public long startTimestamp;
        public long endTimestamp;
        public Long maxCost;
        // getter for stream sorting
        public long getEndTimestamp() { return endTimestamp; }
    }

    public record TraceDetail(List<LogEventRecord> events,
                               List<LogEventRecord> relatedEntries,
                               List<LlmCall> llmCalls) {}

    public static class LlmCall {
        public long seq;
        public String traceId;
        public String agent;
        public String model;
        public long latencyMs;
        public Long promptTokens;
        public Long completionTokens;
        public Long totalTokens;
        public long timestamp;
        public long getTimestamp() { return timestamp; }
    }
}

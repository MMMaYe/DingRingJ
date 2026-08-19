package com.dingring.infrastructure.observability;

import java.util.Map;

/**
 * 日志事件结构化记录。
 *
 * <p>由 {@link LogFileCollector} 从 logs/dingring.log 增量 tail 出原始文本，
 * 经 {@link LogEventParser} 解析后填充本对象。
 *
 * <p>设计要点：
 * <ul>
 *   <li>{@code seq}：采集器内全局递增序号，前端按时间降序展示后用于稳定排序</li>
 *   <li>{@code cursor}：事件起始行在日志文件中的字节偏移，作为前端增量游标透传，
 *       也能用于按需从文件懒读取事件全文</li>
 *   <li>{@code costMs}：EventAspect 输出的方法执行耗时（来自日志中的 ☆ cost=Nms 锚点），可空</li>
 *   <li>{@code message}：事件全文（多行合并后），按需懒加载；列表查询时可缺省以减小响应体</li>
 *   <li>{@code fields}：特定事件深解析后的结构化字段（如 LLM token 用量、节点 elapsedMs 等），可空</li>
 * </ul>
 *
 * <p>本 record 是不可变值对象，多线程读取安全。
 */
public record LogEventRecord(
        long seq,
        long cursor,
        long timestamp,
        String level,
        String traceId,
        String groupId,
        String thread,
        String logger,
        String source,
        String eventCode,
        String eventName,
        Long costMs,
        String summary,
        String message,
        Map<String, Object> fields
) {
    /**
     * 构造摘要：取消息首行截断到 120 字符。
     * 列表场景只展示摘要，全文按需拉取。
     */
    public static String summarize(String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        int newlineIdx = message.indexOf('\n');
        String firstLine = newlineIdx > 0 ? message.substring(0, newlineIdx) : message;
        return firstLine.length() > 120 ? firstLine.substring(0, 120) + "..." : firstLine;
    }
}

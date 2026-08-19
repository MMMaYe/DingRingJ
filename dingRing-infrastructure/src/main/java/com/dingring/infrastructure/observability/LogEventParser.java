package com.dingring.infrastructure.observability;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志事件结构化解析器。
 *
 * <p>输入：LogFileCollector 已合并多行后的原始事件文本（含行首时间戳/级别/traceId/线程/logger 前缀 + 事件正文）。
 * 输出：填充好的 {@link LogEventRecord}。
 *
 * <h3>解析层次</h3>
 * <ol>
 *   <li>行首固定前缀：{@code HH:mm:ss.SSS [LEVEL] [traceId] [thread] logger - msg}
 *       —— 由正则 {@link #LOG_HEADER} 一次性匹配</li>
 *   <li>EventAspect 三段前缀：{@code [Class.method][EVENT_CODE][eventName] ☆ cost=Nms ☆ request=... ☆ result=...}
 *       —— 优先以 ☆ 锚点切分（无 ☆ 时回退 indexOf 定位，兼容改造前历史日志）</li>
 *   <li>特定事件深解析（fastjson，仅低频事件码）：
 *       CHAT_RESPONSE 提取 agent/model/耗时/长度、FLOW_EVENT 提取 node/elapsedMs/status、
 *       BEFORE_CALL_LOG 提取 usage token 数</li>
 * </ol>
 *
 * <p>任何解析异常都静默降级为普通文本展示，绝不影响业务线程打日志。
 */
public class LogEventParser {

    private static final Logger log = LoggerFactory.getLogger(LogEventParser.class);

    /** 行首固定前缀正则：HH:mm:ss.SSS [LEVEL] [traceId] [thread] c.d.x.Class - msg
     * 注意 logback pattern 为 {@code [%-5level]}，级别右补空格（如 "[INFO ]"），故级别后允许 \s* */
    private static final Pattern LOG_HEADER = Pattern.compile(
            "^(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+\\[(\\w+)\\s*\\]\\s+\\[([^\\]]*)\\]\\s+\\[([^\\]]*)\\]\\s+([\\w.$]+)\\s+-\\s+(.*)$",
            Pattern.DOTALL
    );

    /** EventAspect 三段前缀正则：[Class.method][EVENT_CODE][eventName] */
    private static final Pattern EVENT_PREFIX = Pattern.compile(
            "^\\[([^\\]]+)\\]\\[([A-Z_][A-Z0-9_]*)\\]\\[([^\\]]*)\\]\\s*(.*)$",
            Pattern.DOTALL
    );

    /** LogHelper 两段前缀正则：[Class.method][EVENT_CODE] eventName msg
     * （eventName 与 msg 无可靠分隔符，eventName 留空，正文整体进 payload） */
    private static final Pattern EVENT_PREFIX_2 = Pattern.compile(
            "^\\[([^\\]]+)\\]\\[([A-Z_][A-Z0-9_]*)\\]\\s*(.*)$",
            Pattern.DOTALL
    );

    /** apiKey 字段值脱敏正则：匹配 "apiKey":"sk-xxxx" 形式 */
    private static final Pattern API_KEY_PATTERN = Pattern.compile(
            "(\"(apiKey|api_key|apikey)\"\\s*:\\s*\")([^\"]+)(\")",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 解析一条原始事件文本。
     *
     * @param rawText   原始文本（多行已合并）
     * @param seq       序号
     * @param cursor    文件字节偏移
     * @return 结构化记录（解析失败也会返回带 rawText 的记录，绝不抛异常）
     */
    public LogEventRecord parse(String rawText, long seq, long cursor) {
        if (rawText == null || rawText.isEmpty()) {
            return new LogEventRecord(seq, cursor, 0L, "INFO", "", "", "", "", "", "", "", null, "", rawText, null);
        }
        try {
            return doParse(rawText, seq, cursor);
        } catch (Exception e) {
            // 静默降级：返回带原文的最小记录，绝不抛异常影响调用方
            log.debug("LogEventParser 降级: seq={} cursor={} err={}", seq, cursor, e.getMessage());
            return new LogEventRecord(seq, cursor, System.currentTimeMillis(), "INFO", "", "", "",
                    "", "", "", "", null, LogEventRecord.summarize(rawText), rawText, null);
        }
    }

    private LogEventRecord doParse(String rawText, long seq, long cursor) {
        // 1. 行首前缀
        Matcher header = LOG_HEADER.matcher(rawText);
        String time = "";
        String level = "INFO";
        String traceId = "";
        String thread = "";
        String logger = "";
        String body = rawText;
        long timestamp = System.currentTimeMillis();

        if (header.matches()) {
            time = header.group(1);
            level = header.group(2);
            traceId = header.group(3);
            thread = header.group(4);
            logger = header.group(5);
            body = header.group(6);
            timestamp = parseTimeToMillis(time);
        }
        // logback pattern 中 traceId 缺省占位为 "-"，归一化为空串便于过滤判断
        if ("-".equals(traceId)) {
            traceId = "";
        }

        // 拆出 groupId（traceId 形如 G10-T1787108186823 或 G10-T?）
        String groupId = "";
        if (traceId != null && traceId.startsWith("G")) {
            int dashIdx = traceId.indexOf("-T");
            if (dashIdx > 0) {
                groupId = traceId.substring(1, dashIdx);
            }
        }

        // 2. EventAspect 三段前缀
        String source = "";
        String eventCode = "";
        String eventName = "";
        String payload = body;
        Long costMs = null;
        Map<String, Object> fields = null;

        Matcher eventMatcher = EVENT_PREFIX.matcher(body);
        if (eventMatcher.matches()) {
            source = eventMatcher.group(1);
            eventCode = eventMatcher.group(2);
            eventName = eventMatcher.group(3);
            payload = eventMatcher.group(4);
        } else {
            // LogHelper 两段前缀：[Class.method][EVENT_CODE] eventName msg
            Matcher m2 = EVENT_PREFIX_2.matcher(body);
            if (m2.matches()) {
                source = m2.group(1);
                eventCode = m2.group(2);
                payload = m2.group(3);
            }
        }

        // 3. ☆ 锚点切分（cost/request/result）
        // 直接按 ☆ 字符切分：payload 以 "☆ cost=..." 开头（前缀消费后左侧无空格），
        // 且 EventAspect 格式串里 ☆ 两侧空格不保证对称，用带空格的锚点会漏掉首段
        if (payload.contains("☆")) {
            String[] parts = payload.split("☆");
            for (String part : parts) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.startsWith("cost=") && trimmed.endsWith("ms")) {
                    try {
                        costMs = Long.parseLong(trimmed.substring("cost=".length(), trimmed.length() - 2).trim());
                    } catch (NumberFormatException ignore) {
                    }
                } else if (trimmed.startsWith("request=")) {
                    fields = ensureFields(fields);
                    fields.put("request", desensitize(trimmed.substring("request=".length())));
                } else if (trimmed.startsWith("result=")) {
                    fields = ensureFields(fields);
                    fields.put("result", desensitize(trimmed.substring("result=".length())));
                } else if (!trimmed.isEmpty()) {
                    fields = ensureFields(fields);
                    // 多个无名分区累积而非覆盖（如 CHAT_RESPONSE 的 "耗时=Nms"/"长度=N" 段）
                    Object prev = fields.get("extra");
                    fields.put("extra", desensitize(prev == null ? trimmed : prev + " | " + trimmed));
                }
            }
        } else if (payload.startsWith("request=") || payload.contains(" request=")) {
            // 兼容改造前历史日志：indexOf 定位
            int reqIdx = payload.indexOf("request=");
            fields = ensureFields(fields);
            fields.put("request", desensitize(payload.substring(reqIdx + "request=".length())));
        }

        // 4. 特定事件深解析
        if (eventCode != null && !eventCode.isEmpty()) {
            fields = deepParse(eventCode, fields, body, payload);
        }

        // 摘要去掉行首固定前缀（时间/级别/线程等在列表中已分列展示），直接取正文首行
        String summary = LogEventRecord.summarize(body);
        return new LogEventRecord(seq, cursor, timestamp, level, traceId, groupId, thread, logger,
                source, eventCode, eventName, costMs, summary, rawText, fields);
    }

    /**
     * 特定事件深解析：仅对低频事件码做 fastjson 解析，提取关键观测字段。
     */
    private Map<String, Object> deepParse(String eventCode, Map<String, Object> fields, String body, String payload) {
        fields = ensureFields(fields);
        try {
            switch (eventCode) {
                case "CHAT_RESPONSE" -> {
                    // agent=xxx model=xxx 耗时=Nms 长度=N 完整内容:...
                    String agent = extractKv(body, "agent=", " ");
                    String model = extractKv(body, "model=", " ");
                    Long latency = extractNumericKv(body, "耗时=", "ms");
                    if (agent != null) fields.put("agent", agent);
                    if (model != null) fields.put("model", model);
                    if (latency != null) fields.put("latencyMs", latency);
                }
                case "FLOW_EVENT" -> {
                    // FLOW_EVENT 的 result JSON 含 node/nodeName/status/elapsedMs/state
                    String json = resultJsonOf(fields, payload, body);
                    if (json != null) {
                        JSONObject obj = JSON.parseObject(json);
                        if (obj != null) {
                            putIfPresent(fields, obj, "node");
                            putIfPresent(fields, obj, "nodeName");
                            putIfPresent(fields, obj, "status");
                            putIfPresent(fields, obj, "elapsedMs");
                        }
                    }
                }
                case "BEFORE_CALL_LOG" -> {
                    // result JSON 结构：{"chatResponse":{"metadata":{"usage":{...}}}}
                    // （兼容顶层直接含 usage 的格式）
                    String json = resultJsonOf(fields, payload, body);
                    if (json != null) {
                        JSONObject usage = findUsage(JSON.parseObject(json));
                        if (usage != null) {
                            putIfPresent(fields, usage, "promptTokens");
                            putIfPresent(fields, usage, "completionTokens");
                            putIfPresent(fields, usage, "totalTokens");
                        }
                    }
                }
                default -> {
                    // 其他事件不深解析
                }
            }
        } catch (Exception ignore) {
            // 静默降级
        }
        return fields;
    }

    /**
     * 取 result JSON：优先用 ☆ 锚点已切分出的 fields.result（边界可靠），
     * 否则回退到 payload/body 中 indexOf("result=") 提取（兼容历史日志）。
     */
    private String resultJsonOf(Map<String, Object> fields, String payload, String body) {
        if (fields != null && fields.get("result") instanceof String s && s.contains("{")) {
            int start = s.indexOf('{');
            int end = s.lastIndexOf('}');
            if (end > start) {
                return s.substring(start, end + 1);
            }
        }
        return extractResultJson(payload, body);
    }

    private String extractResultJson(String payload, String body) {
        // 优先从 fields.request/result（已切分）取；其次从 body 直接 indexOf "result=" / "request="
        String source = payload != null ? payload : body;
        int idx = source.indexOf("result=");
        if (idx < 0) {
            idx = source.indexOf("request=");
        }
        if (idx < 0) {
            return null;
        }
        String candidate = source.substring(idx).replaceFirst("^(result|request)=", "").trim();
        // 取最外层 {...}（容错：JSON 可能前后有非 JSON 文字）
        int braceStart = candidate.indexOf('{');
        int braceEnd = candidate.lastIndexOf('}');
        if (braceStart < 0 || braceEnd < 0 || braceEnd <= braceStart) {
            return null;
        }
        return candidate.substring(braceStart, braceEnd + 1);
    }

    private String extractKv(String text, String prefix, String end) {
        int idx = text.indexOf(prefix);
        if (idx < 0) return null;
        int start = idx + prefix.length();
        int endIdx = text.indexOf(end, start);
        if (endIdx < 0) return text.substring(start).trim();
        return text.substring(start, endIdx).trim();
    }

    private Long extractNumericKv(String text, String prefix, String suffix) {
        String val = extractKv(text, prefix, suffix);
        if (val == null) return null;
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 在 result JSON 中定位 usage 对象：优先顶层，其次 chatResponse.metadata.usage。
     */
    private JSONObject findUsage(JSONObject obj) {
        if (obj == null) return null;
        if (obj.getJSONObject("usage") != null) {
            return obj.getJSONObject("usage");
        }
        JSONObject chatResponse = obj.getJSONObject("chatResponse");
        if (chatResponse != null) {
            JSONObject metadata = chatResponse.getJSONObject("metadata");
            if (metadata != null) {
                return metadata.getJSONObject("usage");
            }
        }
        return null;
    }

    private void putIfPresent(Map<String, Object> fields, JSONObject obj, String key) {
        if (obj != null && obj.containsKey(key)) {
            fields.put(key, obj.get(key));
        }
    }

    private Map<String, Object> ensureFields(Map<String, Object> fields) {
        if (fields == null) {
            return new LinkedHashMap<>();
        }
        return fields;
    }

    /**
     * apiKey 字段值脱敏：将 "apiKey":"sk-xxxx" 中的 key 值替换为 "sk-***"。
     */
    private String desensitize(String text) {
        if (text == null || text.isEmpty()) return text;
        Matcher m = API_KEY_PATTERN.matcher(text);
        return m.replaceAll("$1sk-***$4");
    }

    /**
     * HH:mm:ss.SSS 转毫秒时间戳（日志不含日期，按系统当天处理；跨天历史日志会归到当天，可接受的简化）。
     * 注意必须用系统时区的当天零点，不能用 UTC 取模（会偏 8 小时）。
     */
    private long parseTimeToMillis(String time) {
        try {
            java.time.LocalTime t = java.time.LocalTime.parse(time);
            return java.time.LocalDate.now()
                    .atTime(t)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }
}

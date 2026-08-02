package com.dingring.common.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 日志辅助工具：统一日志格式 & MDC 链路追踪。
 * <p>日志格式约定：{@code [methodName][eventCode] eventName + msg}
 * <p>示例：{@code [DiscussionEngine.runLoop][LOOP_START] 循环启动 groupId=123}
 */
public final class LogHelper {

    private static final String TRACE_KEY = "traceId";

    /** 反射方法缓存，避免重复 getMethod 调用 */
    private static final ConcurrentHashMap<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();

    private LogHelper() {
    }

    /**
     * 获取指定类的 Logger 实例。
     *
     * @param clazz 目标类
     * @return SLF4J Logger
     */
    public static Logger of(Class<?> clazz) {
        return LoggerFactory.getLogger(clazz);
    }

    // ======================== ① 通用日志打印 ========================

    /**
     * 通用日志打印（INFO 级别）。
     *
     * @param clazz     日志所属类
     * @param methodName 类名.方法名，如 "DiscussionEngine.runLoop"
     * @param eventCode 事件码，大写蛇形，如 "LOOP_START"
     * @param eventName 事件名称，中文人读，如 "循环启动"
     * @param msg       详情，使用 SLF4J {@code {}} 占位符
     * @param args      占位符参数
     */
    public static void printLog(Class<?> clazz, String methodName, String eventCode,
                                String eventName, String msg, Object... args) {
        Logger log = LoggerFactory.getLogger(clazz);
        doLog(log, "INFO", methodName, eventCode, eventName, msg, null, args);
    }

    /**
     * 通用日志打印（WARN 级别，无异常）。
     *
     * @param clazz     日志所属类
     * @param methodName 类名.方法名
     * @param eventCode 事件码
     * @param eventName 事件名称
     * @param msg       详情，使用 SLF4J {@code {}} 占位符
     * @param args      占位符参数
     */
    public static void printWarnLog(Class<?> clazz, String methodName, String eventCode,
                                    String eventName, String msg, Object... args) {
        Logger log = LoggerFactory.getLogger(clazz);
        doLog(log, "WARN", methodName, eventCode, eventName, msg, null, args);
    }

    /**
     * 通用日志打印（WARN 级别，有异常）。
     *
     * @param clazz     日志所属类
     * @param methodName 类名.方法名
     * @param eventCode 事件码
     * @param eventName 事件名称
     * @param msg       详情，使用 SLF4J {@code {}} 占位符
     * @param e         异常
     * @param args      占位符参数
     */
    public static void printWarnLog(Class<?> clazz, String methodName, String eventCode,
                                    String eventName, String msg, Throwable e, Object... args) {
        Logger log = LoggerFactory.getLogger(clazz);
        doLog(log, "WARN", methodName, eventCode, eventName, msg, e, args);
    }

    /**
     * 通用日志打印（ERROR 级别，无异常）。
     *
     * @param clazz     日志所属类
     * @param methodName 类名.方法名
     * @param eventCode 事件码
     * @param eventName 事件名称
     * @param msg       详情，使用 SLF4J {@code {}} 占位符
     * @param args      占位符参数
     */
    public static void printErrorLog(Class<?> clazz, String methodName, String eventCode,
                                     String eventName, String msg, Object... args) {
        Logger log = LoggerFactory.getLogger(clazz);
        doLog(log, "ERROR", methodName, eventCode, eventName, msg, null, args);
    }

    /**
     * 通用日志打印（ERROR 级别，有异常）。
     *
     * @param clazz     日志所属类
     * @param methodName 类名.方法名
     * @param eventCode 事件码
     * @param eventName 事件名称
     * @param msg       详情，使用 SLF4J {@code {}} 占位符
     * @param e         异常
     * @param args      占位符参数
     */
    public static void printErrorLog(Class<?> clazz, String methodName, String eventCode,
                                     String eventName, String msg, Throwable e, Object... args) {
        Logger log = LoggerFactory.getLogger(clazz);
        doLog(log, "ERROR", methodName, eventCode, eventName, msg, e, args);
    }

    // ======================== ② MDC 链路追踪 ========================

    /**
     * 将群组/话题 ID 写入 MDC，格式 {@code G{groupId}-T{topicId}}。
     * 写入后日志 Pattern 中的 {@code %X{traceId}} 即可自动携带。
     */
    public static void putTrace(Long groupId, Long topicId) {
        String traceId = "G" + (groupId == null ? "?" : groupId)
                + "-T" + (topicId == null ? "?" : topicId);
        MDC.put(TRACE_KEY, traceId);
    }

    /** 清除 MDC 中的 traceId，避免线程复用时污染。 */
    public static void clearTrace() {
        MDC.remove(TRACE_KEY);
    }

    // ======================== ③ 格式化辅助 ========================

    /**
     * 将消息列表格式化为可读文本，如 {@code [USER: 你好, ASSISTANT: 好的]}。
     * <p>兼容 Spring AI 的 {@code Message} 类型（反射读取，避免 common 模块硬依赖 spring-ai）
     * 以及含 {@code role()}/{@code content()} 方法的 record（如 ChatTurn）。
     */
    public static String formatTurns(List<?> messages) {
        if (messages == null || messages.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Object msg = messages.get(i);
            String role = extractRole(msg);
            String text = extractText(msg);
            sb.append(role).append(": ").append(text);
        }
        sb.append("]");
        return sb.toString();
    }

    // ======================== 内部方法 ========================

    /**
     * 统一日志格式化核心方法，所有公开日志方法均委托此方法。
     *
     * @param log        Logger 实例
     * @param level      日志级别：INFO / WARN / ERROR
     * @param methodName 类名.方法名
     * @param eventCode  事件码
     * @param eventName  事件名称
     * @param msg        详情模板（SLF4J {@code {}} 占位符）
     * @param e          异常，可为 null
     * @param args       占位符参数
     */
    private static void doLog(Logger log, String level, String methodName,
                               String eventCode, String eventName,
                               String msg, Throwable e, Object... args) {
        String formatted = formatSlf4j(msg, args);
        String prefix = "[" + methodName + "][" + eventCode + "] " + eventName + " ";
        switch (level) {
            case "INFO" -> log.info(prefix + formatted, e);
            case "WARN" -> log.warn(prefix + formatted, e);
            case "ERROR" -> log.error(prefix + formatted, e);
        }
    }

    private static String formatSlf4j(String msg, Object... args) {
        if (args == null || args.length == 0) {
            return msg;
        }
        StringBuilder sb = new StringBuilder();
        int argIndex = 0;
        int pos = 0;
        int placeholder;
        while ((placeholder = msg.indexOf("{}", pos)) >= 0 && argIndex < args.length) {
            sb.append(msg, pos, placeholder);
            sb.append(args[argIndex++]);
            pos = placeholder + 2;
        }
        sb.append(msg.substring(pos));
        return sb.toString();
    }

    /**
     * 从消息对象提取角色名。优先反射读取 Spring AI Message.getMessageType()，
     * 其次读取 record 的 role() 方法。使用 ConcurrentHashMap 缓存 Method 对象。
     */
    private static String extractRole(Object msg) {
        if (msg == null) {
            return "NULL";
        }
        // Spring AI Message: getMessageType() 返回 MessageType 枚举
        Method getTypeMethod = getCachedMethod(msg.getClass(), "getMessageType");
        if (getTypeMethod != null) {
            try {
                Object type = getTypeMethod.invoke(msg);
                if (type != null) {
                    return type.toString();
                }
            } catch (Exception ignored) {
                // 反射调用失败，继续尝试
            }
        }
        // Record 风格: role()
        Method roleMethod = getCachedMethod(msg.getClass(), "role");
        if (roleMethod != null) {
            try {
                Object role = roleMethod.invoke(msg);
                if (role != null) {
                    return role.toString();
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
        return msg.getClass().getSimpleName();
    }

    /**
     * 从消息对象提取文本内容。优先反射读取 Spring AI Message.getText()，
     * 其次读取 record 的 content() 方法。使用 ConcurrentHashMap 缓存 Method 对象。
     */
    private static String extractText(Object msg) {
        if (msg == null) {
            return "<null>";
        }
        // Spring AI Message: getText()
        Method getTextMethod = getCachedMethod(msg.getClass(), "getText");
        if (getTextMethod != null) {
            try {
                Object text = getTextMethod.invoke(msg);
                return text == null ? "<null>" : text.toString();
            } catch (Exception ignored) {
                // ignore
            }
        }
        // Record 风格: content()
        Method contentMethod = getCachedMethod(msg.getClass(), "content");
        if (contentMethod != null) {
            try {
                Object content = contentMethod.invoke(msg);
                return content == null ? "<null>" : content.toString();
            } catch (Exception ignored) {
                // ignore
            }
        }
        return msg.toString();
    }

    /**
     * 从缓存获取 Method 对象，缓存未命中时通过反射获取并缓存。
     *
     * @param clazz      目标类
     * @param methodName 方法名
     * @return Method 对象，不存在时返回 null
     */
    private static Method getCachedMethod(Class<?> clazz, String methodName) {
        String cacheKey = clazz.getName() + "#" + methodName;
        return METHOD_CACHE.computeIfAbsent(cacheKey, k -> {
            try {
                return clazz.getMethod(methodName);
            } catch (NoSuchMethodException e) {
                return null;
            }
        });
    }
}
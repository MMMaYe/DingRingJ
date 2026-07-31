package com.dingring.common.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 日志辅助工具：统一 LLM 调用日志格式 & MDC 链路追踪。
 * <p>日志格式约定：{@code [模块][动作] key1=v1 key2=v2 | 详情}
 */
public final class LogHelper {

    private static final String TRACE_KEY = "traceId";

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
     * 通用日志打印（INFO 级别），格式为 {@code [module][action] detail}。
     * <p>detail 支持 {@code %s}/{@code %d} 等占位符，args 为对应的参数值；
     * 不传 args 时 detail 原样输出。
     *
     * @param log    调用方的 Logger
     * @param module 模块名（如 "LLM"、"路由"、"编排"）
     * @param action 动作名（如 "请求"、"响应"、"异常"）
     * @param detail 详情模板或原文
     * @param args   占位符参数（可选）
     */
    public static void printLog(Logger log, String module, String action, String detail, Object... args) {
        if (!log.isInfoEnabled()) {
            return;
        }
        Throwable throwable = extractThrowable(args);
        Object[] fmtArgs = trimThrowable(args);
        String msg = fmtArgs.length > 0 ? String.format(detail, fmtArgs) : detail;
        if (throwable != null) {
            log.info("[{}][{}] {}", module, action, msg, throwable);
        } else {
            log.info("[{}][{}] {}", module, action, msg);
        }
    }

    /**
     * 通用日志打印（WARN 级别），适用于异常/错误场景。
     * <p>detail 支持 {@code %s}/{@code %d} 等占位符，args 为对应的参数值；
     * 不传 args 时 detail 原样输出。
     *
     * @param log    调用方的 Logger
     * @param module 模块名
     * @param action 动作名
     * @param detail 详情模板或原文
     * @param args   占位符参数（可选）
     */
    public static void printWarnLog(Logger log, String module, String action, String detail, Object... args) {
        if (!log.isWarnEnabled()) {
            return;
        }
        Throwable throwable = extractThrowable(args);
        Object[] fmtArgs = trimThrowable(args);
        String msg = fmtArgs.length > 0 ? String.format(detail, fmtArgs) : detail;
        if (throwable != null) {
            log.warn("[{}][{}] {}", module, action, msg, throwable);
        } else {
            log.warn("[{}][{}] {}", module, action, msg);
        }
    }

    /**
     * 通用日志打印（WARN 级别，带异常堆栈）。
     *
     * @param log    调用方的 Logger
     * @param module 模块名
     * @param action 动作名
     * @param detail 详情内容
     * @param e      异常
     */
    public static void printWarnLog(Logger log, String module, String action, String detail, Throwable e) {
        log.warn("[{}][{}] {}", module, action, detail, e);
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

    /** 从 args 末尾提取 Throwable（SLF4J 约定：最后一个参数为异常时打印堆栈）。 */
    private static Throwable extractThrowable(Object[] args) {
        if (args != null && args.length > 0 && args[args.length - 1] instanceof Throwable) {
            return (Throwable) args[args.length - 1];
        }
        return null;
    }

    /** 返回去掉末尾 Throwable 的参数数组，用于 String.format。 */
    private static Object[] trimThrowable(Object[] args) {
        if (args != null && args.length > 0 && args[args.length - 1] instanceof Throwable) {
            Object[] trimmed = new Object[args.length - 1];
            System.arraycopy(args, 0, trimmed, 0, args.length - 1);
            return trimmed;
        }
        return args == null ? new Object[0] : args;
    }

    /**
     * 从消息对象提取角色名。优先反射读取 Spring AI Message.getMessageType()，
     * 其次读取 record 的 role() 方法。
     */
    private static String extractRole(Object msg) {
        if (msg == null) {
            return "NULL";
        }
        // Spring AI Message: getMessageType() 返回 MessageType 枚举
        try {
            Method m = msg.getClass().getMethod("getMessageType");
            Object type = m.invoke(msg);
            if (type != null) {
                return type.toString();
            }
        } catch (Exception ignored) {
            // 非 Spring AI Message 类型，继续尝试
        }
        // Record 风格: role()
        try {
            Method m = msg.getClass().getMethod("role");
            Object role = m.invoke(msg);
            if (role != null) {
                return role.toString();
            }
        } catch (Exception ignored) {
            // ignore
        }
        return msg.getClass().getSimpleName();
    }

    /**
     * 从消息对象提取文本内容。优先反射读取 Spring AI Message.getText()，
     * 其次读取 record 的 content() 方法。
     */
    private static String extractText(Object msg) {
        if (msg == null) {
            return "<null>";
        }
        // Spring AI Message: getText()
        try {
            Method m = msg.getClass().getMethod("getText");
            Object text = m.invoke(msg);
            return text == null ? "<null>" : text.toString();
        } catch (Exception ignored) {
            // ignore
        }
        // Record 风格: content()
        try {
            Method m = msg.getClass().getMethod("content");
            Object content = m.invoke(msg);
            return content == null ? "<null>" : content.toString();
        } catch (Exception ignored) {
            // ignore
        }
        return msg.toString();
    }
}

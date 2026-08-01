package com.dingring.infrastructure.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法入参/出参日志注解。
 * <p>标注在方法上，由 {@link EventAspect} 切面拦截，在方法调用前后自动打印入参和返回值的 JSON 日志。
 * <p>日志格式：{@code [ClassName.methodName][eventCode][eventName] request/result/error=<JSON>}
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Event {

    /** 事件码，大写蛇形，如 "METHOD_CALL"，对应日志中的 eventCode 字段 */
    String eventCode();

    /** 事件名称，中文人读，如 "方法调用"；为空时自动取方法名 */
    String eventName() default "";

    /** 是否打印入参，默认 true */
    boolean logArgs() default true;

    /** 是否打印出参/返回值，默认 true */
    boolean logResult() default true;
}
package com.dingring.infrastructure.aop;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * {@link Event} 注解的切面实现。
 * <p>拦截所有标注 {@code @Event} 的方法，在调用前后打印入参和返回值日志。
 * <p>日志格式：{@code [ClassName.methodName][eventCode][eventName] request/result/error=<JSON>}
 */
@Slf4j
@Aspect
@Component
public class EventAspect {

    @Around("@annotation(com.dingring.infrastructure.aop.Event)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        // 获取注解信息
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Event event = method.getAnnotation(Event.class);

        // 构建日志前缀：[ClassName.methodName][eventCode][eventName]
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = className + "." + signature.getName();
        String eventCode = event.eventCode();
        String eventName = event.eventName().isEmpty() ? signature.getName() : event.eventName();
        String logPrefix = "[" + methodName + "][" + eventCode + "][" + eventName + "]";

        // 打印入参
        if (event.logArgs()) {
            String argsJson = JSON.toJSONString(joinPoint.getArgs());
            log.info("{} request={}", logPrefix, argsJson);
        }

        try {
            Object result = joinPoint.proceed();

            // 打印出参
            if (event.logResult()) {
                String resultJson = JSON.toJSONString(result);
                log.info("{} result={}", logPrefix, resultJson);
            }

            return result;
        } catch (Throwable e) {
            log.warn("{} error={}", logPrefix, e.getMessage());
            throw e;
        }
    }
}
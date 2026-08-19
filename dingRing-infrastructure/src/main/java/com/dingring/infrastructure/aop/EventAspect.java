package com.dingring.infrastructure.aop;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializeConfig;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.alibaba.fastjson.serializer.ValueFilter;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * {@link Event} 注解的切面实现。
 * <p>拦截所有标注 {@code @Event} 的方法，在调用前后打印入参和返回值日志。
 * <p>日志格式：{@code [ClassName.methodName][eventCode][eventName] request/result/error=<JSON>}
 * <p>对函数式接口（lambda/Consumer/Function 等）与无法被 fastjson 正确序列化的对象做跳过/占位处理，
 * 避免打印大量 {@code {}} 空对象占位符造成日志噪音。
 */
@Slf4j
@Aspect
@Component
public class EventAspect {

    /** 函数式接口集合：这些对象被 fastjson 序列化成 {}，日志中用字符串占位即可 */
    private static final List<Class<?>> FUNCTIONAL_TYPES = List.of(
            Runnable.class, Supplier.class, Consumer.class, Function.class,
            BiConsumer.class, BiFunction.class, Predicate.class, BiPredicate.class
    );

    /** fastjson 序列化配置：enum 用 name()，关闭循环引用检测避免出现 $ref */
    private static final SerializeConfig SERIALIZE_CONFIG = new SerializeConfig();

    /**
     * 全局值过滤器：解决两类"整条日志退化为 unserializable 占位"的序列化故障，
     * 经 {@code JSON.toJSONString(obj, config, filter, features)} 重载挂载（fastjson 1.2.83
     * 的 SerializeConfig.addFilter 是类级 API，做不了全局）。
     * <p>
     * 背景（问题一：JDK 反射对象导致整体失败）：@Tool 工具方法经 Spring AI 包装为
     * ToolCallback 后持有 java.lang.reflect.Method 引用；fastjson 1.2.83 深度序列化
     * Method 时会反射进 sun.reflect.annotation.*（如 annotatedReceiverType），而
     * Java 9+ 模块系统禁止无名模块访问 java.base 内部包，抛 JSONException →
     * safeToJson 整体 catch 返回 {@code <unserializable:ArrayList>}——此前 LLM_REQUEST
     * 日志里 request 全量丢失即此因（探针实证：cannot access
     * sun.reflect.annotation... module java.base does not export）。
     * 处理：Method/Field/Type 值占位不下降——JVM 反射元数据内部无观测价值，
     * 换取其余字段全量输出。
     * <p>
     * 背景（问题二：ToolCallback 语义缺失）：工具对象直接序列化输出的是框架内脏
     * （toolMethod/toolContextSource 等字段），且 ToolDefinition 是 record、fastjson 1.x
     * 会输出 {}。观测上真正有价值的是"这次装配了哪些工具"。处理：手动映射为
     * {name, description}——语义全量且绕开 record 序列化缺陷。
     * <p>
     * 为什么放 ValueFilter 而非 sanitizeForLog：filter 在序列化期对任意深度的嵌套值
     * 生效（对象图深处的 Method/ToolCallback 同样命中），sanitizeForLog 的递归只需
     * 处理 record/集合结构，两者职责分离。
     */
    private static final ValueFilter LOG_VALUE_FILTER = new ValueFilter() {
        @Override
        public Object process(Object object, String name, Object value) {
            if (value instanceof Method || value instanceof Field || value instanceof Type) {
                return "<" + value.getClass().getSimpleName() + ">";
            }
            if (value instanceof ToolCallback callback) {
                Map<String, Object> tool = new LinkedHashMap<>();
                if (callback.getToolDefinition() != null) {
                    tool.put("name", callback.getToolDefinition().name());
                    tool.put("description", callback.getToolDefinition().description());
                }
                return tool;
            }
            // ToolDefinition 独立出现（如 MethodToolCallback.toolDefinition 字段值）：
            // 实现类 DefaultToolDefinition 是 record，fastjson 1.x 直接序列化输出 {}
            if (value instanceof org.springframework.ai.tool.definition.ToolDefinition definition) {
                Map<String, Object> tool = new LinkedHashMap<>();
                tool.put("name", definition.name());
                tool.put("description", definition.description());
                return tool;
            }
            return value;
        }
    };

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

        // 先准备入参 JSON，延迟到方法返回后与出参一起打印，确保入参出参落在同一条日志上
        String argsJson = null;
        if (event.logArgs()) {
            String[] paramNames = signature.getParameterNames();
            Class<?>[] paramTypes = signature.getParameterTypes();
            Object[] args = joinPoint.getArgs();

            // 对每个参数先做一次"预处理"：函数式接口占位，嵌套集合/数组元素做 {} -> <ClassName> 替换
            List<Object> printable = new ArrayList<>(args.length);
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (isFunctionalOrLambda(arg, paramTypes[i])) {
                    String typeName = paramTypes[i] == null ? "" : paramTypes[i].getSimpleName();
                    String name = paramNames != null && paramNames.length > i ? paramNames[i] : "arg" + i;
                    printable.add("<" + name + ":" + typeName + ">");
                } else {
                    printable.add(sanitizeForLog(arg));
                }
            }
            argsJson = safeToJson(printable);
        }

        try {
            Object result = joinPoint.proceed();

            // 入参和出参合并到同一条日志，避免分散在两行难以关联
            if (event.logArgs() && event.logResult()) {
                String resultJson = safeToJson(sanitizeForLog(result));
                log.info("{} request={} result={}", logPrefix, argsJson, resultJson);
            } else if (event.logArgs()) {
                log.info("{} request={}", logPrefix, argsJson);
            } else if (event.logResult()) {
                String resultJson = safeToJson(sanitizeForLog(result));
                log.info("{} result={}", logPrefix, resultJson);
            }

            return result;
        } catch (Throwable e) {
            log.warn("{} error={}", logPrefix, e.getMessage());
            throw e;
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 判断参数是否为函数式接口 / lambda，fastjson 无法序列化其内容，只需占位即可。
     */
    private boolean isFunctionalOrLambda(Object arg, Class<?> declaredType) {
        if (arg == null) {
            return false;
        }
        if (declaredType != null && isFunctionalInterface(declaredType)) {
            return true;
        }
        String clsName = arg.getClass().getName();
        if (clsName.contains("$$Lambda$")) {
            return true;
        }
        for (Class<?> f : FUNCTIONAL_TYPES) {
            if (f.isInstance(arg)) {
                return true;
            }
        }
        return false;
    }

    private boolean isFunctionalInterface(Class<?> type) {
        if (type == null) {
            return false;
        }
        for (Class<?> f : FUNCTIONAL_TYPES) {
            if (f.isAssignableFrom(type)) {
                return true;
            }
        }
        return type.isAnnotationPresent(FunctionalInterface.class);
    }

    /**
     * 把将要放入日志的对象"清洗"一遍：
     * - Java Record：通过 {@link Class#getRecordComponents()} 反射拆包成 Map（解决 fastjson 1.x 不识别 record accessor 的问题）
     * - 对集合/数组递归应用（保证嵌套 record 也能正确展开）
     * - 对最终序列化为 {} 的其他对象，替换为 {@code <ClassName>} 占位符
     * - 不改变任何原始对象，新构造副本用于日志打印
     */
    private Object sanitizeForLog(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof Collection<?> col) {
            List<Object> copy = new ArrayList<>(col.size());
            for (Object item : col) {
                copy.add(sanitizeForLog(item));
            }
            return copy;
        }
        if (obj.getClass().isArray()) {
            Object[] arr = toObjectArray(obj);
            Object[] copy = new Object[arr.length];
            for (int i = 0; i < arr.length; i++) {
                copy[i] = sanitizeForLog(arr[i]);
            }
            return copy;
        }
        // 基本类型 / String / Number / Boolean 直接返回（fastjson 序列化它们不会是 {}）
        if (isPrimitiveLike(obj)) {
            return obj;
        }
        // Map：递归处理 value
        if (obj instanceof Map<?, ?> m) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                copy.put(sanitizeForLog(e.getKey()), sanitizeForLog(e.getValue()));
            }
            return copy;
        }
        // ToolCallback：映射为 {name, description}。此处与 LOG_VALUE_FILTER 双保险——
        // ValueFilter 只拦截 JavaBean 字段值/Map 值，集合元素（List<ToolCallback>，
        // 如 ModelRequest.dynamicToolCallbacks）不经过 filter 直接序列化会整体失败，
        // 而本方法的 Collection 分支会逐元素递归到此处
        if (obj instanceof ToolCallback callback) {
            Map<String, Object> tool = new LinkedHashMap<>();
            if (callback.getToolDefinition() != null) {
                tool.put("name", callback.getToolDefinition().name());
                tool.put("description", callback.getToolDefinition().description());
            }
            return tool;
        }
        // JDK 反射元数据：占位（同上，覆盖 ValueFilter 够不到的集合元素路径）
        if (obj instanceof Method || obj instanceof Field || obj instanceof Type) {
            return "<" + obj.getClass().getSimpleName() + ">";
        }
        // Java Record：反射拆包成 Map，保证 ChatTurn(role, content) 这类 record 可以完整输出
        if (obj.getClass().isRecord()) {
            return recordToMap(obj);
        }
        // 其余对象：先序列化为 JSON 预览一下，如果是 {} 则用占位符替换
        String preview = safeToJsonRaw(obj);
        if ("{}".equals(preview)) {
            return "<" + obj.getClass().getSimpleName() + ">";
        }
        return obj;
    }

    /**
     * 通过 {@link RecordComponent} 把任意 record 实例转成有序 Map。
     * 这是解决 fastjson 1.x 对 record 支持缺陷的"通用兜底"：
     * fastjson 在 1.2.x 版本寻找 getter 时只认 {@code getXxx / isXxx} 命名，
     * 而 record 的 accessor 方法直接叫 {@code xxx}，导致所有字段被跳过，输出成 {}。
     */
    private Map<String, Object> recordToMap(Object record) {
        RecordComponent[] components = record.getClass().getRecordComponents();
        Map<String, Object> map = new LinkedHashMap<>(components.length);
        for (RecordComponent c : components) {
            try {
                Method accessor = c.getAccessor();
                accessor.setAccessible(true);
                Object value = accessor.invoke(record);
                // 对 record 嵌套的 value 再做一次清洗，支持嵌套 record / 集合
                map.put(c.getName(), sanitizeForLog(value));
            } catch (Exception e) {
                // 单个字段失败不影响其他字段，填入错误占位
                map.put(c.getName(), "<error:" + e.getMessage() + ">");
            }
        }
        return map;
    }

    private static Object[] toObjectArray(Object array) {
        if (array instanceof Object[] arr) {
            return arr;
        }
        int len = java.lang.reflect.Array.getLength(array);
        Object[] out = new Object[len];
        for (int i = 0; i < len; i++) {
            out[i] = java.lang.reflect.Array.get(array, i);
        }
        return out;
    }

    private boolean isPrimitiveLike(Object obj) {
        return obj instanceof String
                || obj instanceof Number
                || obj instanceof Boolean
                || obj instanceof Character
                || obj.getClass().isPrimitive();
    }

    /** 序列化一个对象；任何异常都不会向上抛出，只会返回一个占位字符串 */
    private String safeToJson(Object obj) {
        try {
            return JSON.toJSONString(obj, SERIALIZE_CONFIG, LOG_VALUE_FILTER,
                    SerializerFeature.WriteEnumUsingToString,
                    SerializerFeature.DisableCircularReferenceDetect);
        } catch (Exception e) {
            return "<unserializable:" + (obj == null ? "null" : obj.getClass().getSimpleName()) + ">";
        }
    }

    /** 仅用于"预览是否为 {}"，不做特殊特性，只求快速得出序列化结果字符串 */
    private String safeToJsonRaw(Object obj) {
        try {
            return JSON.toJSONString(obj, SERIALIZE_CONFIG, LOG_VALUE_FILTER,
                    SerializerFeature.WriteEnumUsingToString,
                    SerializerFeature.DisableCircularReferenceDetect);
        } catch (Exception ignore) {
            return "<error>";
        }
    }
}
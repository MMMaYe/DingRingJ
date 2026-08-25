package com.dingring.common.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.alibaba.fastjson.serializer.SerializerFeature;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON 辅助工具：基于 fastjson 的常用操作封装。
 * <p>所有方法均为 {@code public static}，异常时返回 null 或默认值。
 */
public final class JsonHelper {


    private JsonHelper() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    // ======================== ① 序列化 ========================

    /**
     * 将对象序列化为 JSON 字符串。
     *
     * @param obj 待序列化对象
     * @return JSON 字符串；对象为 null 或异常时返回 null
     */
    public static String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return JSON.toJSONString(obj);
        } catch (Exception e) {
            LogHelper.printErrorLog(JsonHelper.class, "JsonHelper.toJson", "SERIALIZE_FAIL", "序列化失败", "error={}", e, e.getMessage());
            return null;
        }
    }

    /**
     * 将交错排列的 key, value, key, value, ... 可变参数组装为 Map 后序列化为 JSON 字符串。
     * <p>参数按成对读取，键一般为 {@link String}，值可为任意类型；使用 {@link LinkedHashMap}
     * 保留插入顺序；最终调用 {@link #mapToJsonStr(Map)} 完成序列化。
     *
     * @param o 交错排列的 key, value 序列，长度必须为偶数
     * @return JSON 字符串；参数为 null/空/奇数、key 为 null 或异常时返回 null
     */
    public static String toJsonStr(Object... o) {
        if (o == null || o.length == 0) {
            return null;
        }
        if ((o.length & 1) != 0) {
            LogHelper.printErrorLog(JsonHelper.class, "JsonHelper.toJsonStr", "TO_JSON_STR_PARAM_ODD",
                    "参数数量必须为偶数", "size={}", o.length);
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>(o.length / 2);
        for (int i = 0; i < o.length; i += 2) {
            Object key = o[i];
            if (key == null) {
                LogHelper.printErrorLog(JsonHelper.class, "JsonHelper.toJsonStr", "TO_JSON_STR_KEY_NULL",
                        "key 不能为 null", "index={}", i);
                return null;
            }
            map.put(key.toString(), o[i + 1]);
        }
        return mapToJsonStr(map);
    }

    /**
     * 将对象序列化为格式化的 JSON 字符串（带缩进）。
     *
     * @param obj 待序列化对象
     * @return 格式化 JSON 字符串；对象为 null 或异常时返回 null
     */
    public static String toJsonPretty(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return JSON.toJSONString(obj, SerializerFeature.PrettyFormat);
        } catch (Exception e) {
            LogHelper.printErrorLog(JsonHelper.class, "JsonHelper.toJsonPretty", "SERIALIZE_PRETTY_FAIL", "美化序列化失败", "error={}", e, e.getMessage());
            return null;
        }
    }

    /**
     * 将 Map 对象序列化为 JSON 字符串。
     *
     * @param map Map 对象
     * @return JSON 字符串；map 为 null/空或异常时返回 null
     */
    public static String mapToJsonStr(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        try {
            return JSON.toJSONString(map);
        } catch (Exception e) {
            LogHelper.printErrorLog(JsonHelper.class, "JsonHelper.mapToJson", "MAP_SERIALIZE_FAIL", "Map序列化失败", "size={} error={}", e, map.size(), e.getMessage());
            return null;
        }
    }

    /**
     * 将 {@code com.alibaba.cloud.ai.graph.OverAllState} 的数据视图序列化为 JSON 字符串。
     * <p>仅序列化 {@code data()} 返回的键值数据，不包含 keyStrategies/store 等运行时组件；
     * 值无法被 fastjson 处理时降级为 toString，保证日志可打印（state 中可能含 Message 等复杂对象）。
     * <p>通过反射读取（与 {@link LogHelper#formatTurns} 同策略），避免 common 模块硬依赖
     * spring-ai-alibaba-graph-core。
     *
     * @param state OverAllState 实例（编译期无该类型，故声明为 Object）
     * @return JSON 字符串，如 {@code {"groupId":1,"intent":"CHAT"}}；state 为 null 或提取失败时返回 null，数据为空时返回 "{}"
     */
    public static String overAllStateToJsonStr(Object state) {
        if (state == null) {
            return null;
        }
        Map<String, Object> data = extractStateData(state);
        if (data == null) {
            return null;
        }
        if (data.isEmpty()) {
            return "{}";
        }
        JSONObject result = new JSONObject(true);
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            result.put(entry.getKey(), toSafeJsonValue(entry.getValue()));
        }
        try {
            return JSON.toJSONString(result, SerializerFeature.WriteMapNullValue);
        } catch (Exception e) {
            LogHelper.printErrorLog(JsonHelper.class, "JsonHelper.overAllStateToJsonStr", "STATE_SERIALIZE_FAIL",
                    "OverAllState序列化失败", "error={}", e, e.getMessage());
            return null;
        }
    }

    /**
     * 将 {@code com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest} 序列化为 JSON 字符串。
     * <p>输出结构（观测导向，非对象原样字段）：模型参数从 {@code getOptions()} 展开，
     * messages 提取为 {@code [{role, text}]}，tools/dynamicToolCallbacks 提取为
     * {@code [{name, description}]}，systemMessage 输出全文。
     * <p>为什么不能直接 {@link #toJson}：ToolCallback 持有 {@code java.lang.reflect.Method}，
     * fastjson 深度序列化会触发 Java 9+ 模块访问限制导致整体失败；ToolDefinition 是
     * record，fastjson 1.x 输出 {}。故本方法逐字段安全提取。
     * <p>通过反射读取（与 {@link #overAllStateToJsonStr(Object)} 同策略），避免 common
     * 模块硬依赖 spring-ai-alibaba-graph-core。
     *
     * @param modelRequest ModelRequest 实例（编译期无该类型，故声明为 Object）
     * @return JSON 字符串；modelRequest 为 null、无 ModelRequest 特征方法或异常时返回 null
     */
    public static String modelRequestToJsonStr(Object modelRequest) {
        if (modelRequest == null) {
            return null;
        }
        // 特征校验：既无 getMessages 也无 getOptions，可判定传入了错误类型，给出诊断而非输出全 null 的无信息量结果
        if (!hasMethod(modelRequest, "getMessages") && !hasMethod(modelRequest, "getOptions")) {
            LogHelper.printErrorLog(JsonHelper.class, "JsonHelper.modelRequestToJsonStr", "NOT_MODEL_REQUEST",
                    "对象无getMessages/getOptions方法，疑似非ModelRequest", "type={}", modelRequest.getClass().getName());
            return null;
        }
        try {
            Map<String, Object> result = new LinkedHashMap<>(8);
            // 模型参数：options 展开为顶层字段
            Object options = invokeNoArg(modelRequest, "getOptions");
            result.put("model", invokeNoArg(options, "getModel"));
            result.put("temperature", invokeNoArg(options, "getTemperature"));
            result.put("maxTokens", invokeNoArg(options, "getMaxTokens"));
            // systemMessage 全文（DingRing 讨论场景刻意为 null，打印 null 有观测价值）
            Object systemMessage = invokeNoArg(modelRequest, "getSystemMessage");
            result.put("systemMessage", systemMessage == null ? null : invokeNoArg(systemMessage, "getText"));
            // messages：[{role, text}]
            result.put("messages", toMessageInfoList(invokeNoArg(modelRequest, "getMessages")));
            // tools / dynamicToolCallbacks：[{name, description}]
            result.put("tools", toToolInfoList(invokeNoArg(modelRequest, "getTools")));
            result.put("dynamicTools", toToolInfoList(invokeNoArg(modelRequest, "getDynamicToolCallbacks")));
            return JSON.toJSONString(result, SerializerFeature.WriteMapNullValue);
        } catch (Exception e) {
            LogHelper.printErrorLog(JsonHelper.class, "JsonHelper.modelRequestToJsonStr", "MODEL_REQUEST_SERIALIZE_FAIL",
                    "ModelRequest序列化失败", "type={} error={}", e, modelRequest.getClass().getName(), e.getMessage());
            return null;
        }
    }

    // ======================== ② 反序列化 ========================

    /**
     * 将 JSON 字符串反序列化为指定类型的对象。
     *
     * @param json  JSON 字符串
     * @param clazz 目标类型
     * @param <T>   泛型
     * @return 反序列化后的对象；json 为空或异常时返回 null
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.isEmpty() || clazz == null) {
            return null;
        }
        try {
            return JSON.parseObject(json, clazz);
        } catch (Exception e) {
            LogHelper.printErrorLog(JsonHelper.class, "JsonHelper.fromJson", "DESERIALIZE_FAIL", "反序列化失败", "type={} error={}", e, clazz.getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * 将 JSON 字符串反序列化为泛型类型对象（如 {@code List<Foo>}、{@code Map<String, Bar>}）。
     *
     * @param json          JSON 字符串
     * @param typeReference 泛型类型引用
     * @param <T>           泛型
     * @return 反序列化后的对象；json 为空或异常时返回 null
     */
    public static <T> T fromJson(String json, TypeReference<T> typeReference) {
        if (json == null || json.isEmpty() || typeReference == null) {
            return null;
        }
        try {
            return JSON.parseObject(json, typeReference.getType());
        } catch (Exception e) {
            LogHelper.printErrorLog(JsonHelper.class, "JsonHelper.fromJson", "DESERIALIZE_TYPE_REF_FAIL", "类型引用反序列化失败", "error={}", e, e.getMessage());
            return null;
        }
    }

    /**
     * 将 JSON 字符串反序列化为 {@link Type} 指定的对象。
     *
     * @param json JSON 字符串
     * @param type 目标类型
     * @param <T>  泛型
     * @return 反序列化后的对象；json 为空或异常时返回 null
     */
    public static <T> T fromJson(String json, Type type) {
        if (json == null || json.isEmpty() || type == null) {
            return null;
        }
        try {
            return JSON.parseObject(json, type);
        } catch (Exception e) {
            LogHelper.printErrorLog(JsonHelper.class, "JsonHelper.fromJson", "DESERIALIZE_TYPE_FAIL", "类型反序列化失败", "error={}", e, e.getMessage());
            return null;
        }
    }

    // ======================== ③ 集合转换 ========================

    /**
     * 将 JSON 字符串转换为 {@code Map<String, Object>}。
     *
     * @param json JSON 字符串
     * @return Map 对象；json 为空或异常时返回空 Map
     */
    public static Map<String, Object> toMap(String json) {
        if (json == null || json.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return JSON.parseObject(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            LogHelper.printErrorLog(JsonHelper.class, "JsonHelper.toMap", "TO_MAP_FAIL", "转Map失败", "error={}", e, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 将 JSON 字符串转换为指定元素类型的 {@code List}。
     *
     * @param json  JSON 字符串
     * @param clazz 列表元素类型
     * @param <T>   泛型
     * @return List 对象；json 为空或异常时返回空 List
     */
    public static <T> List<T> toList(String json, Class<T> clazz) {
        if (json == null || json.isEmpty() || clazz == null) {
            return Collections.emptyList();
        }
        try {
            return JSON.parseArray(json, clazz);
        } catch (Exception e) {
            LogHelper.printErrorLog(JsonHelper.class, "JsonHelper.toList", "TO_LIST_FAIL", "转List失败", "type={} error={}", e, clazz.getSimpleName(), e.getMessage());
            return Collections.emptyList();
        }
    }

    // ======================== ④ 校验与取值 ========================

    /**
     * 判断字符串是否为合法的 JSON。
     *
     * @param str 待校验字符串
     * @return 合法返回 true；null 或格式错误返回 false
     */
    public static boolean isValidJson(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        try {
            JSON.parse(str);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从 JSON 对象字符串中获取指定 key 的字符串值。
     *
     * @param json JSON 字符串（应为 JSON 对象）
     * @param key  目标 key
     * @return 对应的字符串值；不存在或异常时返回 null
     */
    public static String getStringValue(String json, String key) {
        if (json == null || json.isEmpty() || key == null) {
            return null;
        }
        try {
            JSONObject jsonObject = JSON.parseObject(json);
            return jsonObject != null ? jsonObject.getString(key) : null;
        } catch (Exception e) {
            LogHelper.printErrorLog(JsonHelper.class, "JsonHelper.getStringValue", "GET_STRING_FAIL", "取值失败", "key={} error={}", e, key, e.getMessage());
            return null;
        }
    }

    /**
     * String转成JSON
     */
    public static JSONObject toJsonObject(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        return JSON.parseObject(json);
    }

    // ======================== 内部方法 ========================

    /**
     * 反射调用 OverAllState.data() 获取数据视图。
     *
     * @param state OverAllState 实例
     * @return 数据 Map；state 无 data() 方法、返回值非 Map 或调用异常时返回 null
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractStateData(Object state) {
        try {
            Method dataMethod = state.getClass().getMethod("data");
            dataMethod.setAccessible(true);
            Object data = dataMethod.invoke(state);
            if (data instanceof Map) {
                return (Map<String, Object>) data;
            }
            LogHelper.printErrorLog(JsonHelper.class, "JsonHelper.overAllStateToJsonStr", "STATE_DATA_INVALID",
                    "data()返回值非Map", "type={}", data.getClass().getName());
            return null;
        } catch (Exception e) {
            LogHelper.printErrorLog(JsonHelper.class, "JsonHelper.overAllStateToJsonStr", "STATE_DATA_EXTRACT_FAIL",
                    "反射读取OverAllState.data失败", "type={} error={}", e, state.getClass().getName(), e.getMessage());
            return null;
        }
    }

    /**
     * 将值转换为可安全序列化的对象：基本类型原样返回，
     * 复杂类型先经 fastjson 转换（保留结构）；fastjson 抛异常或转不出任何字段
     * （如无 getter 的 POJO）时降级为 toString，保证日志信息量。
     */
    private static Object toSafeJsonValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean || value instanceof Character) {
            return value;
        }
        try {
            Object converted = JSON.toJSON(value);
            // 非 Map 对象被转成空 JSONObject：说明 fastjson 读不到任何字段，toString 更有信息量
            if (converted instanceof JSONObject && ((JSONObject) converted).isEmpty() && !(value instanceof Map)) {
                return String.valueOf(value);
            }
            return converted;
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /** 判断对象所属类是否声明了指定 public 无参方法 */
    private static boolean hasMethod(Object target, String methodName) {
        try {
            return target.getClass().getMethod(methodName) != null;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * 反射调用无参方法。任何失败（方法不存在/访问受限/调用异常）均静默返回 null，
     * 由调用方决定 null 语义（字段缺失输出 JSON null，不打错误日志避免噪音）。
     */
    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将 Message 列表转换为 {@code [{role, text}]} 结构。
     * <p>role 优先反射 {@code getMessageType()}（Spring AI Message），
     * text 反射 {@code getText()}；均失败时降级 toString。
     *
     * @param messages getMessages() 的返回值（应为 Collection）
     * @return 列表；入参非 Collection（含 null）时返回 null
     */
    private static List<Object> toMessageInfoList(Object messages) {
        if (!(messages instanceof Collection)) {
            return null;
        }
        List<Object> list = new ArrayList<>(((Collection<?>) messages).size());
        for (Object msg : (Collection<?>) messages) {
            Map<String, Object> info = new LinkedHashMap<>(4);
            Object role = invokeNoArg(msg, "getMessageType");
            info.put("role", role == null ? (msg == null ? "NULL" : msg.getClass().getSimpleName()) : role.toString());
            Object text = invokeNoArg(msg, "getText");
            info.put("text", text == null ? String.valueOf(msg) : text.toString());
            list.add(info);
        }
        return list;
    }

    /**
     * 将工具列表（ToolCallback 或 ToolDefinition 的 Collection）转换为 {@code [{name, description}]}。
     *
     * @param tools getTools()/getDynamicToolCallbacks() 的返回值
     * @return 列表；入参非 Collection（含 null）时返回 null
     */
    private static List<Object> toToolInfoList(Object tools) {
        if (!(tools instanceof Collection)) {
            return null;
        }
        List<Object> list = new ArrayList<>(((Collection<?>) tools).size());
        for (Object tool : (Collection<?>) tools) {
            list.add(toolInfo(tool));
        }
        return list;
    }

    /**
     * 提取单个工具的观测信息：优先经 {@code getToolDefinition()}（ToolCallback），
     * 其次视元素自身为 ToolDefinition（record accessor：{@code name()}/{@code description()}）；
     * 两者都失败时降级 toString（兼容纯工具名 String 列表）。
     */
    private static Object toolInfo(Object tool) {
        if (tool == null) {
            return null;
        }
        Object definition = invokeNoArg(tool, "getToolDefinition");
        if (definition == null) {
            definition = tool;
        }
        Object name = invokeNoArg(definition, "name");
        Object description = invokeNoArg(definition, "description");
        if (name == null && description == null) {
            return String.valueOf(tool);
        }
        Map<String, Object> info = new LinkedHashMap<>(4);
        if (name != null) {
            info.put("name", name.toString());
        }
        if (description != null) {
            info.put("description", description.toString());
        }
        return info;
    }

}

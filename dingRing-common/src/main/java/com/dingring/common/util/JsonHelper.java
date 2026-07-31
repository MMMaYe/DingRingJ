package com.dingring.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * JSON 辅助工具：基于 Jackson {@link ObjectMapper} 的常用操作封装。
 * <p>内部持有静态 ObjectMapper 单例，所有方法均为 {@code public static}。
 */
public final class JsonHelper {

    private static final Logger log = LogHelper.of(JsonHelper.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    private JsonHelper() {
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
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("[Json][序列化] toJson 失败: {}", e.getMessage(), e);
            return null;
        }
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
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("[Json][序列化] toJsonPretty 失败: {}", e.getMessage(), e);
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
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, clazz);
        } catch (Exception e) {
            log.error("[Json][反序列化] fromJson({}) 失败: {}", clazz.getSimpleName(), e.getMessage(), e);
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
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, typeReference);
        } catch (Exception e) {
            log.error("[Json][反序列化] fromJson(TypeReference) 失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 将 JSON 字符串反序列化为 {@link JavaType} 指定的对象。
     *
     * @param json     JSON 字符串
     * @param javaType Jackson JavaType
     * @param <T>      泛型
     * @return 反序列化后的对象；json 为空或异常时返回 null
     */
    public static <T> T fromJson(String json, JavaType javaType) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, javaType);
        } catch (Exception e) {
            log.error("[Json][反序列化] fromJson(JavaType) 失败: {}", e.getMessage(), e);
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
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("[Json][集合] toMap 失败: {}", e.getMessage(), e);
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
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            JavaType type = MAPPER.getTypeFactory().constructCollectionType(List.class, clazz);
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            log.error("[Json][集合] toList({}) 失败: {}", clazz.getSimpleName(), e.getMessage(), e);
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
            MAPPER.readTree(str);
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
            var node = MAPPER.readTree(json);
            var valueNode = node.get(key);
            return valueNode == null || valueNode.isNull() ? null : valueNode.asText();
        } catch (Exception e) {
            log.error("[Json][取值] getStringValue(key={}) 失败: {}", key, e.getMessage(), e);
            return null;
        }
    }

    // ======================== ⑤ 内部实例暴露 ========================

    /**
     * 返回内部 {@link ObjectMapper} 单例，供需要自定义配置的场景使用。
     *
     * @return ObjectMapper 实例
     */
    public static ObjectMapper getObjectMapper() {
        return MAPPER;
    }
}

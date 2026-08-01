package com.dingring.common.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.alibaba.fastjson.serializer.SerializerFeature;

import java.lang.reflect.Type;
import java.util.Collections;
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
    public static String mapToJson(Map<String, Object> map) {
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
}

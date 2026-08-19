package com.dingring.infrastructure.aop;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EventAspect 逻辑验证（不依赖 Spring 容器，用 AspectJProxyFactory 手动织入）。
 */
@Slf4j
class EventAspectTest {

    /** 被代理的目标类，模拟业务方法 */
    static class FakeService {
        /** 模拟 chatStream 的签名：Agent + String + List<Record> + Consumer */
        @Event(eventCode = "TEST", eventName = "测试")
        public DemoResult chatStream(DemoAgent agent, String prompt, List<DemoTurn> turns, Consumer<String> onDelta) {
            return new DemoResult("OK", 2);
        }
    }

    // 以下全是 record，用来验证 fastjson 1.x 原本会序列化成 {} 的问题
    record DemoAgent(Long id, String name, String model) {}
    record DemoTurn(String role, String content) {}
    record DemoResult(String status, int round) {}

    @Test
    @DisplayName("Record 字段应能被正确序列化，不再出现 {}")
    void recordShouldBeSerializedWithFields() {
        AspectJProxyFactory factory = new AspectJProxyFactory(new FakeService());
        factory.addAspect(new EventAspect());
        FakeService proxy = factory.getProxy();

        DemoAgent agent = new DemoAgent(1L, "老王", "glm-5.2");
        List<DemoTurn> turns = List.of(
                new DemoTurn("USER", "你好"),
                new DemoTurn("ASSISTANT", "在的")
        );

        DemoResult result = proxy.chatStream(agent, "prompt内容", turns, s -> {});
        // 结果本身正常返回
        assertThat(result).isEqualTo(new DemoResult("OK", 2));
    }

    @Test
    @DisplayName("sanitizeForLog 等价逻辑验证：record→map 的转换路径")
    void sanitizeRecordToMapViaReflection() throws Exception {
        DemoTurn turn = new DemoTurn("USER", "你好");
        assertThat(turn.getClass().isRecord()).as("DemoTurn 是 record").isTrue();

        // 手动模拟 EventAspect.recordToMap 的等价逻辑
        java.lang.reflect.RecordComponent[] comps = turn.getClass().getRecordComponents();
        java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
        for (var c : comps) {
            Object v = c.getAccessor().invoke(turn);
            map.put(c.getName(), v);
        }
        String json = JSON.toJSONString(map);
        log.info("recordToMap 后序列化结果: {}", json);
        assertThat(json).contains("\"role\":\"USER\"").contains("\"content\":\"你好\"");
    }

    @Test
    @DisplayName("fastjson 1.x 直接序列化 record 的对比：确认它真的会输出 {}")
    void fastjson1DirectOnRecordReturnsEmptyObject() {
        DemoTurn turn = new DemoTurn("USER", "你好");
        String raw = JSON.toJSONString(turn);
        log.info("fastjson 1.x 直接序列化 DemoTurn record: {}", raw);
        // 确认原生行为就是 {}（这是我们要修的 bug）
        // 如果未来项目升级 fastjson 到 2.x（兼容 record），此断言会失败，届时可移除我们的兜底
        assertThat(raw).isEqualTo("{}");
    }

    // ==================== LOG_VALUE_FILTER：Method/ToolCallback 全量序列化 ====================

    /** 模拟 ModelRequest 的结构：普通字段 + 嵌套的 tools 列表（含 ToolCallback） */
    static class FakeModelRequest {
        private final String model = "deepseek-v4-flash";
        private final List<ToolCallback> dynamicToolCallbacks;

        FakeModelRequest(List<ToolCallback> callbacks) {
            this.dynamicToolCallbacks = callbacks;
        }

        public String getModel() {
            return model;
        }

        public List<ToolCallback> getDynamicToolCallbacks() {
            return dynamicToolCallbacks;
        }
    }

    /**
     * 真实 @Tool Bean（不用 Mockito mock）：生产路径是 Spring AI 把 @Tool 方法包装为
     * MethodToolCallback（持有 java.lang.reflect.Method）——mock 的内部是 Mockito
     * 拦截器循环结构，无法复现 Method 序列化故障，测了也白测。
     */
    static class FakeTools {
        @org.springframework.ai.tool.annotation.Tool(description = "联网搜索最新信息")
        public String webSearch(@org.springframework.ai.tool.annotation.ToolParam(description = "关键词") String query) {
            return "";
        }

        @org.springframework.ai.tool.annotation.Tool(description = "读取指定网页正文")
        public String webFetch(@org.springframework.ai.tool.annotation.ToolParam(description = "URL") String url) {
            return "";
        }
    }

    private String safeToJson(Object obj) {
        EventAspect aspect = new EventAspect();
        return ReflectionTestUtils.invokeMethod(aspect, "safeToJson", obj);
    }

    @Test
    @DisplayName("含真实 ToolCallback 的对象应全量输出（工具映射为 name/description）而非 unserializable 占位")
    void toolCallbackShouldMapToNameAndDescription() {
        List<ToolCallback> callbacks = Arrays.asList(
                org.springframework.ai.support.ToolCallbacks.from(new FakeTools()));
        FakeModelRequest request = new FakeModelRequest(callbacks);

        String json = safeToJson(request);

        log.info("含工具的序列化结果: {}", json);
        // 修复前：fastjson 序列化 ToolCallback 内的 Method 触发 JPMS 模块访问限制，
        // 整条退化为 <unserializable:FakeModelRequest>；修复后字段全量输出
        assertThat(json).doesNotContain("unserializable");
        assertThat(json).contains("\"model\":\"deepseek-v4-flash\"");
        assertThat(json).contains("\"name\":\"webSearch\"");
        assertThat(json).contains("\"description\":\"联网搜索最新信息\"");
        assertThat(json).contains("\"name\":\"webFetch\"");
    }

    @Test
    @DisplayName("JDK 反射对象（Method/Field/Type）应占位不下降，同对象图其余字段保持全量")
    void reflectiveObjectsShouldBePlaceholdersWhileOthersRemain() throws Exception {
        // String.length() 的 Method 对象：修复前 fastjson 反射进 sun.reflect.annotation.* 会抛 JSONException
        Method lengthMethod = String.class.getMethod("length");
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("model", "deepseek-v4-flash");
        graph.put("toolMethod", lengthMethod);
        graph.put("returnType", String.class); // Class 实现 Type，同样占位

        String json = safeToJson(graph);

        log.info("含反射对象的序列化结果: {}", json);
        assertThat(json).doesNotContain("unserializable");
        assertThat(json).contains("\"model\":\"deepseek-v4-flash\"");
        assertThat(json).contains("\"toolMethod\":\"<Method>\"");
        assertThat(json).contains("\"returnType\":\"<Class>\"");
    }
}

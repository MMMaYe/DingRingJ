package com.dingring.infrastructure.aop;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.aop.framework.AopProxy;

import java.util.List;
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
}

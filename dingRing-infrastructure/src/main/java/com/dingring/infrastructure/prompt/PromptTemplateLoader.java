package com.dingring.infrastructure.prompt;

import com.alibaba.cloud.ai.prompt.ConfigurablePromptTemplate;
import com.alibaba.cloud.ai.prompt.ConfigurablePromptTemplateFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 提示词模板加载器（Phase A 基础设施）。
 * <p>设计目的：
 * <ul>
 *   <li>封装 SAA {@link ConfigurablePromptTemplateFactory}，提供统一的 render(name, vars) 接口</li>
 *   <li>有 Nacos 时：从 Nacos 实时加载最新模板（热更新，无需重启）</li>
 *   <li>无 Nacos 或模板渲染失败时：降级为 classpath:/prompt-config.json 本地加载</li>
 * </ul>
 * <p>提示词的单一来源：各调用方统一走 {@link #render}，不再硬编码常量。
 * <p>占位符语法：{@code {varName}}（SAA ConfigurablePromptTemplate 原生支持，比 Java {@code %s} 更安全）
 */
@Slf4j
@Component
@ConditionalOnClass(ConfigurablePromptTemplateFactory.class)
public class PromptTemplateLoader {

    /** 本地兜底模板文件路径（无 Nacos 时从 classpath 加载） */
    private static final String LOCAL_CONFIG_PATH = "prompt-config.json";

    /** SAA 工厂（有 Nacos 配置时由自动装配注入，无 Nacos 时为 null） */
    private final ConfigurablePromptTemplateFactory factory;

    /** 本地兜底模板缓存（name -> template 字符串） */
    private final Map<String, String> localTemplates = new java.util.concurrent.ConcurrentHashMap<>();

    /** JSON 解析器 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 构造注入 SAA 工厂。
     * <p>required=false：无 Nacos 配置时 factory 为 null，降级到本地 classpath 加载。
     * 这样本地开发无需启动 Nacos 也能使用 PromptTemplateLoader。
     *
     * @param factory SAA ConfigurablePromptTemplateFactory（可能为 null）
     */
    @Autowired
    public PromptTemplateLoader(
            @org.springframework.lang.Nullable ConfigurablePromptTemplateFactory factory) {
        this.factory = factory;
    }

    /**
     * 启动时预加载本地兜底模板，便于无 Nacos 环境快速降级。
     * 加载失败仅 WARN 日志，不阻塞启动（Phase A 阶段无调用方，Phase C 才正式使用）。
     */
    @PostConstruct
    void preloadLocalTemplates() {
        try (InputStream is = new ClassPathResource(LOCAL_CONFIG_PATH).getInputStream()) {
            List<Map<String, String>> entries = objectMapper.readValue(is, new TypeReference<>() {});
            for (Map<String, String> entry : entries) {
                String name = entry.get("name");
                String template = entry.get("template");
                if (name != null && template != null) {
                    localTemplates.put(name, template);
                }
            }
            log.info("本地 prompt 模板预加载完成: {} 个 -> {}", localTemplates.size(), localTemplates.keySet());
        } catch (Exception e) {
            log.warn("本地 prompt 模板加载失败（classpath:{}），无 Nacos 时 render 将返回空字符串: {}",
                    LOCAL_CONFIG_PATH, e.getMessage());
        }
    }

    /**
     * 渲染模板（有变量）。
     * <p>优先级：Nacos 工厂 > 本地 classpath 兜底 > 空字符串。
     *
     * @param name 模板名（对应 prompt-config.json 的 name 字段，如 "intent-classify"）
     * @param vars 模板变量（如 {@code Map.of("agentName", "柯南")}），无变量模板传 {@code Map.of()}
     * @return 渲染后的 prompt 字符串；模板不存在或渲染失败返回空字符串
     */
    public String render(String name, Map<String, Object> vars) {
        // 1. 优先用 SAA 工厂（有 Nacos 时实时加载最新模板，支持热更新）
        if (factory != null) {
            try {
                // defaultTemplate 传入本地兜底模板：Nacos 上没有此模板时用本地版本
                String fallback = localTemplates.getOrDefault(name, "");
                ConfigurablePromptTemplate tpl = factory.create(name, fallback);
                return tpl.create(vars).getContents();
            } catch (Exception e) {
                log.warn("SAA 工厂渲染模板失败，降级到本地: name={} error={}", name, e.getMessage());
            }
        }
        // 2. 降级：本地 classpath 模板 + 简单字符串替换
        return renderLocal(name, vars);
    }

    /**
     * 渲染模板（无变量便捷方法）。
     */
    public String render(String name) {
        return render(name, Map.of());
    }

    /**
     * 本地兜底渲染：从 classpath 加载的模板做简单 {@code {varName}} 替换。
     * <p>SAA 的 ConfigurablePromptTemplate 使用 Spring AI 的 StringTemplate 引擎，
     * 本地降级用简单字符串替换（不依赖 SAA 引擎，保证无 Nacos 时也能工作）。
     */
    private String renderLocal(String name, Map<String, Object> vars) {
        String template = localTemplates.get(name);
        if (template == null || template.isEmpty()) {
            log.warn("模板不存在（Nacos 和本地均未找到）: name={}", name);
            return "";
        }
        if (vars == null || vars.isEmpty()) {
            return template;
        }
        // 简单 {varName} 替换：按名匹配，缺失变量保留原样
        String result = template;
        for (Map.Entry<String, Object> entry : vars.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return result;
    }
}

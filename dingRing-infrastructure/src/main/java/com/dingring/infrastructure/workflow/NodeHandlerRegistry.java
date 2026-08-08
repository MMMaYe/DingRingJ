package com.dingring.infrastructure.workflow;

import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 节点处理器注册表：按 Bean 名查找 NodeAction 实现。
 * <p>Spring 自动注入所有 NodeAction Bean（key=Bean名，value=Bean实例），
 * SaaWorkflow 在构建 StateGraph 时按名查找。
 */
@Slf4j
@Component
public class NodeHandlerRegistry {

    private final Map<String, NodeAction> handlers;

    public NodeHandlerRegistry(Map<String, NodeAction> handlers) {
        this.handlers = handlers;
        log.info("NodeHandlerRegistry 初始化完成，已注册节点: {}", handlers.keySet());
    }

    public NodeAction getHandler(String name) {
        NodeAction handler = handlers.get(name);
        if (handler == null) {
            throw new IllegalStateException("未找到节点处理器: " + name + "，已注册: " + handlers.keySet());
        }
        return handler;
    }
}

package com.dingring.app.workflow;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 结论生成专用执行器：与群内串行执行器解耦，结论 LLM 不再阻塞群主循环。
 * <p>设计要点：
 * <ul>
 *   <li>结论生成是 I/O 密集的 LLM 调用，用虚拟线程池共享单池，不随群数量增长（区别于每群一个的单线程执行器）</li>
 *   <li>并发幂等由 {@link ConclusionService} 的 per-topic 守卫 + Topic 乐观锁保证，本类只负责调度</li>
 *   <li>不设并发上限：单用户规模下同时触发收束的群极少；若接入多租户可在此加 Semaphore 限流</li>
 * </ul>
 */
@Component
public class ConclusionExecutor {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public void execute(Runnable task) {
        executor.execute(task);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}

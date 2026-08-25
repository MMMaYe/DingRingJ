package com.dingring.infrastructure.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 日志文件增量采集器。
 *
 * <p>跟踪 {@code logs/dingring.log} 的读取位置 {@link #readPos}（服务端内部维护的精确字节偏移），
 * 每次查询前由 {@link #consumeNew()} 把新增内容消费进内存缓存。
 *
 * <h3>不丢不重保证</h3>
 * <ul>
 *   <li>游标=文件字节偏移且由服务端单点推进：前端只带 seq（{@code afterSeq}）做增量过滤，
 *       不存在"按事件起始偏移重读导致重复入缓存"的问题</li>
 *   <li>只消费到块内最后一个换行符为止：logback 单条事件一次 write 落盘且以 %n 结尾，
 *       读到半截行（写入竞争）时暂存 remainder，下轮补齐后再解析，不会切裂多行事件</li>
 *   <li>文件长度小于 readPos（日志滚动/重启清空）时自动重置，从头消费新文件</li>
 *   <li>偏移按 UTF-8 字节数累计（中文 3 字节），不再用字符数估算</li>
 * </ul>
 *
 * <h3>解析缓存</h3>
 * 内存保留最近 {@value #CACHE_CAPACITY} 条事件（含全文），淘汰队头。
 * 万级日志、单条平均 1KB 时约 10MB 内存，可接受。
 *
 * <p>注：dev 环境开启（{@code dingring.debug.observability.enabled=true}），prod 不注册。
 */
@Component
@ConditionalOnProperty(name = "dingring.debug.observability.enabled", havingValue = "true")
public class LogFileCollector {

    private static final Logger log = LoggerFactory.getLogger(LogFileCollector.class);

    /** 默认日志文件路径（相对工作目录） */
//    private static final String DEFAULT_LOG_PATH = "logs/dingring.log";
    private static final String DEFAULT_LOG_PATH = "logs/dingring.log";

    /** 缓存容量 */
    private static final int CACHE_CAPACITY = 10000;

    /** 启动预热：只回溯文件尾部最近 8MB（避免全量读 50MB 大文件） */
    private static final long PREHEAT_BYTES = 8L * 1024 * 1024;

    /** 单次消费最大字节数（防突发巨量日志一次读爆内存，剩余部分下轮继续） */
    private static final long MAX_READ_PER_CALL = 16L * 1024 * 1024;

    /** 行首时间戳模式：HH:mm:ss.SSS [ */
    private static final Pattern EVENT_START = Pattern.compile("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+\\[");

    private final LogEventParser parser = new LogEventParser();
    private final AtomicLong seqGen = new AtomicLong(0);

    /** 解析缓存（按文件顺序、seq 递增），容量受限 */
    private final List<LogEventRecord> cache = new ArrayList<>();
    private long minSeq = 0;

    private Path logPath;

    /** 已消费到的文件字节偏移（始终落在某个换行符之后或文件头） */
    private long readPos = 0;

    @Value("${dingring.debug.observability.log-path:}")
    private String configuredPath;

    @PostConstruct
    public void init() {
        this.logPath = Paths.get(configuredPath != null && !configuredPath.isEmpty()
                ? configuredPath : DEFAULT_LOG_PATH).toAbsolutePath();
        log.info("LogObservability 启动，日志文件: {}", logPath);
        try {
            consumeNew();
            log.info("LogObservability 预热加载 {} 条事件", cache.size());
        } catch (Exception e) {
            log.warn("LogObservability 预热失败（不影响业务）: {}", e.getMessage());
        }
    }

    /**
     * 消费文件新增内容到缓存。幂等，可在每次查询前调用。
     */
    public synchronized void consumeNew() {
        try {
            if (!Files.exists(logPath)) {
                return;
            }
            long fileLen = Files.size(logPath);

            // 日志滚动/清空重启：文件变短 → 重置，从头消费新文件
            if (fileLen < readPos) {
                log.info("日志文件已重置/滚动 (readPos={} > fileSize={})，清空缓存重新消费", readPos, fileLen);
                cache.clear();
                readPos = 0;
            }
            if (fileLen == readPos) {
                return;
            }

            // 首次消费且文件较大：只回溯尾部 PREHEAT_BYTES（丢弃开头半截行）
            boolean firstConsume = readPos == 0 && cache.isEmpty();
            long start = readPos;
            if (firstConsume && fileLen > PREHEAT_BYTES) {
                start = fileLen - PREHEAT_BYTES;
            }
            long end = Math.min(fileLen, start + MAX_READ_PER_CALL);

            byte[] buf = readRange(start, end);
            if (buf.length == 0) {
                return;
            }

            // 只消费到最后一个换行符；末尾半截行（写入竞争）暂存到下轮
            int lastNl = lastIndexOf(buf, (byte) '\n');
            if (lastNl < 0) {
                // 整块没有换行：预热场景跳过头部的场景下理论上必有，防御性返回等下轮
                if (firstConsume && start > 0) {
                    readPos = end; // 跳过无法对齐的历史内容
                }
                return;
            }
            int consumeLen = lastNl + 1;
            String content = new String(buf, 0, consumeLen, StandardCharsets.UTF_8);

            List<RawEvent> rawEvents = splitEvents(content, start);
            for (RawEvent raw : rawEvents) {
                cache.add(parser.parse(raw.text, seqGen.incrementAndGet(), raw.offset));
            }
            readPos = start + consumeLen;

            // 淘汰超容量部分
            while (cache.size() > CACHE_CAPACITY) {
                cache.remove(0);
            }
            if (!cache.isEmpty()) {
                minSeq = cache.get(0).seq();
            }
        } catch (IOException e) {
            log.warn("LogObservability consumeNew 失败: {}", e.getMessage());
        }
    }

    /** 当前缓存快照（按 seq 递增）。 */
    public synchronized List<LogEventRecord> snapshot() {
        return new ArrayList<>(cache);
    }

    /** 按 seq 单条查询（前端展开全文用）。 */
    public synchronized LogEventRecord findBySeq(long seq) {
        for (LogEventRecord r : cache) {
            if (r.seq() == seq) return r;
        }
        return null;
    }

    /** 最新已分配的 seq（前端增量游标：afterSeq）。 */
    public long getLatestSeq() {
        return seqGen.get();
    }

    public long getMinSeq() {
        return minSeq;
    }

    public long getFileSize() {
        try {
            return Files.exists(logPath) ? Files.size(logPath) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    // ==================== 内部方法 ====================

    private byte[] readRange(long start, long end) throws IOException {
        int len = (int) (end - start);
        if (len <= 0) return new byte[0];
        try (RandomAccessFile raf = new RandomAccessFile(logPath.toFile(), "r")) {
            raf.seek(start);
            byte[] buf = new byte[len];
            int read = raf.read(buf);
            if (read <= 0) return new byte[0];
            if (read == len) return buf;
            byte[] trimmed = new byte[read];
            System.arraycopy(buf, 0, trimmed, 0, read);
            return trimmed;
        }
    }

    private static int lastIndexOf(byte[] buf, byte b) {
        for (int i = buf.length - 1; i >= 0; i--) {
            if (buf[i] == b) return i;
        }
        return -1;
    }

    /**
     * 按行首时间戳模式切分事件、多行合并。
     * 偏移按 UTF-8 字节数精确累计（content 与字节区间一一对应）。
     */
    private List<RawEvent> splitEvents(String content, long baseOffset) {
        List<RawEvent> events = new ArrayList<>();
        String[] lines = content.split("\n", -1);
        StringBuilder current = new StringBuilder();
        long currentOffset = baseOffset;
        long lineOffset = baseOffset;

        for (String line : lines) {
            if (EVENT_START.matcher(line).find()) {
                if (current.length() > 0) {
                    events.add(new RawEvent(current.toString(), currentOffset));
                }
                current = new StringBuilder(line);
                currentOffset = lineOffset;
            } else {
                if (current.length() == 0) {
                    // 不对齐的行（预热跳入点首行/极少见异常），单独成事件
                    current = new StringBuilder(line);
                    currentOffset = lineOffset;
                } else {
                    current.append('\n').append(line);
                }
            }
            // 下一行起始偏移 = 当前行偏移 + 行 UTF-8 字节数 + 1 字节换行符
            lineOffset += line.getBytes(StandardCharsets.UTF_8).length + 1;
        }
        if (current.length() > 0) {
            events.add(new RawEvent(current.toString(), currentOffset));
        }
        return events;
    }

    /** 原始事件文本 + 起始字节偏移（精确） */
    private record RawEvent(String text, long offset) {}
}

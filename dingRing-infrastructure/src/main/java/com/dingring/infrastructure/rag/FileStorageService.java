package com.dingring.infrastructure.rag;

import com.dingring.common.util.LogHelper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 本地文件系统存储服务（Phase E）。
 * <p>按日期分目录 + UUID 文件名落盘，避免重名覆盖；返回绝对路径供摄入管道 Tika 读取。
 * <p>存储根目录由配置 dingring.rag.storage.local-path 指定。
 */
@Slf4j
@Service
public class FileStorageService {

    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /** 存储根目录，默认 ./rag-files */
    @Value("${dingring.rag.storage.local-path:./rag-files}")
    private String storagePath;

    private Path root;

    @PostConstruct
    public void init() {
        root = Paths.get(storagePath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建文件存储目录: " + root, e);
        }
        log.info("文件存储根目录初始化: {}", root);
    }

    /**
     * 存储上传文件，返回绝对路径。
     * <p>保留原扩展名，便于 Tika 按类型解析。
     */
    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }
        String original = file.getOriginalFilename();
        String ext = extractExtension(original);
        String dateDir = LocalDate.now().format(DATE_DIR);
        String fileName = UUID.randomUUID() + ext;
        Path target = root.resolve(dateDir).resolve(fileName);
        try {
            Files.createDirectories(target.getParent());
            // REPLACE_EXISTING 兜底：UUID 基本不会冲突，覆盖仅为防御性处理
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            LogHelper.printLog(FileStorageService.class, "store", "FILE_STORE",
                    "文件已存储", "original={} size={} path={}", original, file.getSize(), target);
            return target.toString();
        } catch (IOException e) {
            throw new IllegalStateException("文件存储失败: " + original, e);
        }
    }

    /** 删除指定路径文件，不存在视为已清理（幂等） */
    public void delete(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            boolean deleted = Files.deleteIfExists(Paths.get(path));
            if (deleted) {
                LogHelper.printLog(FileStorageService.class, "delete", "FILE_STORE",
                        "文件已删除", "path={}", path);
            }
        } catch (IOException e) {
            // 物理文件删除失败不阻断主流程（可能已被手动清理或权限问题），仅告警
            LogHelper.printWarnLog(FileStorageService.class, "delete", "FILE_STORE",
                    "文件删除失败", "path={} 错误: {}", path, e.getMessage(), e);
        }
    }

    private String extractExtension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }
}

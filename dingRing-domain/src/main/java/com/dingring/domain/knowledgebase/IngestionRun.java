package com.dingring.domain.knowledgebase;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class IngestionRun {

    public static final String EXECUTOR_BUILTIN = "builtin";
    public static final String EXECUTOR_EXTERNAL_MCP = "external-mcp";

    public static final String STATUS_UPLOAD_CREATED = "UPLOAD_CREATED";
    public static final String STATUS_CLEANING_RUNNING = "CLEANING_RUNNING";
    public static final String STATUS_CLEANING_WAITING = "CLEANING_WAITING";
    public static final String STATUS_PARSED = "PARSED";
    public static final String STATUS_CHUNKED = "CHUNKED";
    public static final String STATUS_EMBEDDING = "EMBEDDING";
    public static final String STATUS_INDEXING = "INDEXING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private Long id;
    private String runId;
    private Long fileId;
    private String docContentHash;
    private Integer documentVersion;
    private String executor;
    private String status;
    private Integer attempt;
    private String errorMessage;
    private LocalDateTime submittedAt;
    private Long activeSlot;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public boolean isActive() {
        return activeSlot != null && !isTerminal();
    }

    public boolean isTerminal() {
        return STATUS_READY.equals(status) || STATUS_FAILED.equals(status) || STATUS_CANCELLED.equals(status);
    }
}

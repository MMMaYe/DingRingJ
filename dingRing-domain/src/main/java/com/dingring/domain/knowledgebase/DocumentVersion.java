package com.dingring.domain.knowledgebase;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DocumentVersion {

    private Long id;
    private Long fileId;
    private Integer documentVersion;
    private String documentTitle;
    private String docContentHash;
    private String rawPath;
    private String cleanPath;
    private String cleaningModel;
    private String cleaningPromptVersion;
    private String chunkingVersion;
    private String embeddingModel;
    private Integer chunkCount;
    private Boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

package com.example.demo.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("rag_unit")
public class RagUnit {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String sourceId;              // Original file ID/Path

    private String fileHash;              // SHA-256 hash of original file (for deduplication)

    private String filename;              // Original filename (indexed for fast lookup)

    private SourceType sourceType;

    private String content;

    private String minioPath;
    private String minioUrl;

    private Integer chunkIndex;

    private Long startTime;               // Video timestamp (ms)
    private Long endTime;                 // Video timestamp (ms)

    private String metadata;              // JSON metadata

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

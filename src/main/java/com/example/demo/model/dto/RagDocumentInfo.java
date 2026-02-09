package com.example.demo.model.dto;

import com.example.demo.model.SourceType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagDocumentInfo {
    private String sourceId;           // 文件ID
    private String fileHash;           // 文件SHA-256哈希值（用于删除等操作）
    private String filename;           // 文件名
    private SourceType sourceType;     // 文件类型
    private String minioUrl;           // MinIO访问URL
    private String minioPath;          // MinIO存储路径
    private Integer chunkCount;        // 分块数量
    private LocalDateTime createdAt;   // 创建时间
    private LocalDateTime updatedAt;   // 更新时间
}

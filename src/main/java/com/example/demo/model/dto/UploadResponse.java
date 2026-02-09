package com.example.demo.model.dto;

import com.example.demo.model.SourceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadResponse {
    private boolean success;
    private String message;
    private String sourceId;
    private String filename;
    private SourceType sourceType;
    private int chunksCreated;
    private String minioPath;
    private String minioUrl;
    private List<String> unitIds;

    /**
     * 同步模式成功响应（保留用于兼容）
     */
    public static UploadResponse success(String sourceId, String filename, SourceType sourceType,
                                         int chunksCreated, String minioPath, String minioUrl, List<String> unitIds) {
        return UploadResponse.builder()
                .success(true)
                .message("File processed successfully")
                .sourceId(sourceId)
                .filename(filename)
                .sourceType(sourceType)
                .chunksCreated(chunksCreated)
                .minioPath(minioPath)
                .minioUrl(minioUrl)
                .unitIds(unitIds)
                .build();
    }

    /**
     * 错误响应
     */
    public static UploadResponse error(String message) {
        return UploadResponse.builder()
                .success(false)
                .message(message)
                .build();
    }
}

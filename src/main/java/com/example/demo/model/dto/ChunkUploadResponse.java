package com.example.demo.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * 分片上传响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkUploadResponse {

    /**
     * 文件是否已存在（秒传）
     */
    private Boolean fileExists;

    /**
     * 如果已存在，返回sourceId
     */
    private String sourceId;

    /**
     * 是否需要上传
     */
    private Boolean needUpload;

    /**
     * 已上传的分片编号
     */
    private Set<Integer> uploadedChunks;

    /**
     * 上传进度百分比
     */
    private Double progress;

    /**
     * 消息
     */
    private String message;

    /**
     * 秒传响应（文件已存在）
     */
    public static ChunkUploadResponse instantUpload(String sourceId) {
        return ChunkUploadResponse.builder()
                .fileExists(true)
                .sourceId(sourceId)
                .needUpload(false)
                .progress(100.0)
                .message("文件已存在，秒传成功")
                .build();
    }

    /**
     * 需要上传响应
     */
    public static ChunkUploadResponse needUpload(Set<Integer> uploadedChunks, double progress) {
        return ChunkUploadResponse.builder()
                .fileExists(false)
                .needUpload(true)
                .uploadedChunks(uploadedChunks)
                .progress(progress)
                .message("可以继续上传")
                .build();
    }
}

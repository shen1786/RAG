package com.example.demo.model.dto;

import com.example.demo.model.DocumentFileStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkUploadResponse {

    private Boolean fileExists;
    private String sourceId;
    private Boolean needUpload;
    private Set<Integer> uploadedChunks;
    private Double progress;
    private String message;
    private DocumentFileStatus status;
    private String errorMessage;

    public static ChunkUploadResponse instantUpload(String sourceId) {
        return ChunkUploadResponse.builder()
                .fileExists(true)
                .sourceId(sourceId)
                .needUpload(false)
                .progress(100.0)
                .status(DocumentFileStatus.SUCCESS)
                .message("文件已存在，秒传成功")
                .build();
    }

    public static ChunkUploadResponse needUpload(Set<Integer> uploadedChunks, double progress) {
        return ChunkUploadResponse.builder()
                .fileExists(false)
                .needUpload(true)
                .uploadedChunks(uploadedChunks)
                .progress(progress)
                .message("可以继续上传")
                .build();
    }

    public static ChunkUploadResponse trackedFile(String sourceId, DocumentFileStatus status, String errorMessage) {
        return ChunkUploadResponse.builder()
                .fileExists(false)
                .sourceId(sourceId)
                .needUpload(false)
                .progress(status == DocumentFileStatus.SUCCESS ? 100.0 : 0.0)
                .status(status)
                .errorMessage(errorMessage)
                .message("文件已有处理记录")
                .build();
    }
}

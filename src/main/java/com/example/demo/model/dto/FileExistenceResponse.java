package com.example.demo.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 文件存在性检查响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileExistenceResponse {

    /**
     * 文件是否已存在
     */
    private Boolean exists;

    /**
     * 如果存在，返回源文件ID
     */
    private String sourceId;

    /**
     * 如果存在，返回文件名
     */
    private String filename;

    /**
     * 如果存在，返回切片数量
     */
    private Integer chunkCount;

    /**
     * 如果存在，返回MinIO路径
     */
    private String minioPath;

    /**
     * 如果存在，返回MinIO URL
     */
    private String minioUrl;

    /**
     * 如果存在，返回所有切片ID
     */
    private List<String> unitIds;

    /**
     * 文件不存在的响应
     */
    public static FileExistenceResponse notExists() {
        return FileExistenceResponse.builder()
                .exists(false)
                .build();
    }

    /**
     * 文件存在的响应
     */
    public static FileExistenceResponse exists(String sourceId, String filename,
                                               Integer chunkCount, String minioPath,
                                               String minioUrl, List<String> unitIds) {
        return FileExistenceResponse.builder()
                .exists(true)
                .sourceId(sourceId)
                .filename(filename)
                .chunkCount(chunkCount)
                .minioPath(minioPath)
                .minioUrl(minioUrl)
                .unitIds(unitIds)
                .build();
    }
}

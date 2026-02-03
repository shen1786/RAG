package com.example.demo.model.dto;

import com.example.demo.model.SourceType;
import lombok.Data;

import java.util.List;

@Data
public class UploadResponse {
    private String sourceId;
    private String filename;
    private SourceType sourceType;
    private int chunksCreated;
    private String minioPath;
    private String minioUrl;
    private List<String> unitIds;
    private String message;

    public static UploadResponse success(String sourceId, String filename, SourceType sourceType,
                                         int chunksCreated, String minioPath, String minioUrl, List<String> unitIds) {
        UploadResponse response = new UploadResponse();
        response.setSourceId(sourceId);
        response.setFilename(filename);
        response.setSourceType(sourceType);
        response.setChunksCreated(chunksCreated);
        response.setMinioPath(minioPath);
        response.setMinioUrl(minioUrl);
        response.setUnitIds(unitIds);
        response.setMessage("File processed successfully");
        return response;
    }

    public static UploadResponse error(String message) {
        UploadResponse response = new UploadResponse();
        response.setMessage(message);
        return response;
    }
}

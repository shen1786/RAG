package com.example.demo.model.dto;

import com.example.demo.model.DocumentFile;
import com.example.demo.model.DocumentFileStatus;
import com.example.demo.model.SourceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentFileStatusResponse {

    private String sourceId;
    private String fileHash;
    private String filename;
    private SourceType sourceType;
    private Long fileSize;
    private Integer chunkCount;
    private DocumentFileStatus status;
    private String errorMessage;
    private String minioUrl;
    private LocalDateTime updatedAt;
    private Boolean canDelete;

    public static DocumentFileStatusResponse from(DocumentFile documentFile) {
        return DocumentFileStatusResponse.builder()
                .sourceId(documentFile.getSourceId())
                .fileHash(documentFile.getFileHash())
                .filename(documentFile.getFilename())
                .sourceType(documentFile.getSourceType())
                .fileSize(documentFile.getFileSize())
                .chunkCount(documentFile.getChunkCount())
                .status(documentFile.getStatus())
                .errorMessage(documentFile.getErrorMessage())
                .minioUrl(documentFile.getMinioUrl())
                .updatedAt(documentFile.getUpdatedAt())
                .canDelete(!Boolean.TRUE.equals(documentFile.getDeleted()))
                .build();
    }
}

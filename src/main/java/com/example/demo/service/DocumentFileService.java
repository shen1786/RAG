package com.example.demo.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.demo.mapper.DocumentFileMapper;
import com.example.demo.model.DocumentFile;
import com.example.demo.model.DocumentFileStatus;
import com.example.demo.model.SourceType;
import com.example.demo.model.dto.DocumentFileStatusResponse;
import com.example.demo.model.dto.PageRequest;
import com.example.demo.model.dto.PageResponse;
import com.example.demo.model.dto.RagDocumentInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class DocumentFileService {

    private final DocumentFileMapper documentFileMapper;

    public DocumentFileService(DocumentFileMapper documentFileMapper) {
        this.documentFileMapper = documentFileMapper;
    }

    public DocumentFile getByFileHash(String userId, String fileHash) {
        QueryWrapper<DocumentFile> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId).eq("file_hash", fileHash).last("LIMIT 1");
        return documentFileMapper.selectOne(wrapper);
    }

    public DocumentFile getActiveByFileHash(String userId, String fileHash) {
        QueryWrapper<DocumentFile> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId).eq("file_hash", fileHash).eq("deleted", 0).last("LIMIT 1");
        return documentFileMapper.selectOne(wrapper);
    }

    public DocumentFile getActiveByFilename(String userId, String filename) {
        QueryWrapper<DocumentFile> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId).eq("filename", filename).eq("deleted", 0).last("LIMIT 1");
        return documentFileMapper.selectOne(wrapper);
    }

    public List<DocumentFile> getAllActiveDocuments(String userId) {
        return documentFileMapper.selectAllActive(userId);
    }

    public void createUploadingRecord(String userId, String sourceId, String fileHash, String filename, Long fileSize, SourceType sourceType) {
        DocumentFile existing = getByFileHash(userId, fileHash);
        if (existing == null) {
            DocumentFile documentFile = new DocumentFile();
            documentFile.setSourceId(sourceId);
            documentFile.setFileHash(fileHash);
            documentFile.setUserId(userId);
            documentFile.setFilename(filename);
            documentFile.setFileSize(fileSize);
            documentFile.setSourceType(sourceType);
            documentFile.setStatus(DocumentFileStatus.UPLOADING);
            documentFile.setChunkCount(0);
            documentFile.setDeleted(false);
            documentFile.setErrorMessage(null);
            documentFileMapper.insert(documentFile);
            return;
        }

        existing.setSourceId(sourceId);
        existing.setUserId(userId);
        existing.setFilename(filename);
        existing.setFileSize(fileSize);
        existing.setSourceType(sourceType);
        existing.setStatus(DocumentFileStatus.UPLOADING);
        existing.setChunkCount(0);
        existing.setDeleted(false);
        existing.setErrorMessage(null);
        existing.setMinioPath(null);
        existing.setMinioUrl(null);
        documentFileMapper.updateById(existing);
    }

    public void markUploadSuccess(String userId, String fileHash, SourceType sourceType, String minioPath, String minioUrl) {
        DocumentFile documentFile = requireByFileHash(userId, fileHash);
        documentFile.setSourceType(sourceType);
        documentFile.setMinioPath(minioPath);
        documentFile.setMinioUrl(minioUrl);
        documentFile.setStatus(DocumentFileStatus.UPLOAD_SUCCESS);
        documentFile.setErrorMessage(null);
        documentFileMapper.updateById(documentFile);
    }

    public void updateStatus(String userId, String fileHash, DocumentFileStatus status) {
        updateStatus(userId, fileHash, status, null, null);
    }

    public void updateStatus(String userId, String fileHash, DocumentFileStatus status, Integer chunkCount, String errorMessage) {
        DocumentFile documentFile = requireByFileHash(userId, fileHash);
        documentFile.setStatus(status);
        if (chunkCount != null) {
            documentFile.setChunkCount(chunkCount);
        }
        if (status != DocumentFileStatus.FAILED) {
            documentFile.setErrorMessage(null);
        }
        if (errorMessage != null) {
            documentFile.setErrorMessage(errorMessage);
        }
        documentFileMapper.updateById(documentFile);
    }

    public void markFailed(String userId, String fileHash, String errorMessage) {
        DocumentFile documentFile = getByFileHash(userId, fileHash);
        if (documentFile == null) {
            return;
        }
        documentFile.setStatus(DocumentFileStatus.FAILED);
        documentFile.setErrorMessage(errorMessage);
        documentFileMapper.updateById(documentFile);
    }

    public void markDeleted(String userId, String fileHash) {
        DocumentFile documentFile = requireByFileHash(userId, fileHash);
        documentFile.setDeleted(true);
        documentFileMapper.updateById(documentFile);
    }

    public void restoreVisible(String userId, String fileHash) {
        DocumentFile documentFile = getByFileHash(userId, fileHash);
        if (documentFile != null) {
            documentFile.setDeleted(false);
            documentFileMapper.updateById(documentFile);
        }
    }

    public void deleteByFileHash(String userId, String fileHash) {
        DocumentFile documentFile = getByFileHash(userId, fileHash);
        if (documentFile != null) {
            documentFileMapper.deleteById(documentFile.getSourceId());
        }
    }

    public boolean isActive(String userId, String fileHash) {
        DocumentFile documentFile = getByFileHash(userId, fileHash);
        return documentFile != null && !Boolean.TRUE.equals(documentFile.getDeleted());
    }

    public DocumentFileStatusResponse getDocumentStatus(String userId, String fileHash) {
        DocumentFile documentFile = getActiveByFileHash(userId, fileHash);
        if (documentFile == null) {
            throw new RuntimeException("文档不存在: " + fileHash);
        }
        return DocumentFileStatusResponse.from(documentFile);
    }

    public PageResponse<RagDocumentInfo> getDocumentsPage(PageRequest request) {
        Long total = documentFileMapper.countDocuments(request.getSourceType(), request.getUserId(), request.getKeyword());
        return PageResponse.of(
                documentFileMapper.selectDocumentsPage(
                        request.getSourceType(),
                        request.getUserId(),
                        request.getKeyword(),
                        request.getSortBy(),
                        request.getSortOrder(),
                        request.getOffset(),
                        request.getPageSize()
                ),
                total,
                request.getPage(),
                request.getPageSize()
        );
    }

    private DocumentFile requireByFileHash(String userId, String fileHash) {
        DocumentFile documentFile = getByFileHash(userId, fileHash);
        if (documentFile == null) {
            throw new RuntimeException("文档不存在: " + fileHash);
        }
        return documentFile;
    }
}

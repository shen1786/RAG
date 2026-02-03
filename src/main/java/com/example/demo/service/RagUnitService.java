package com.example.demo.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.demo.mapper.RagUnitMapper;
import com.example.demo.model.RagUnit;
import com.example.demo.model.SourceType;
import com.example.demo.model.dto.UploadResponse;
import com.example.demo.service.processor.ImageProcessor;
import com.example.demo.service.processor.MediaProcessor;
import com.example.demo.service.processor.TextProcessor;
import com.example.demo.service.processor.VideoProcessor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class RagUnitService {

    @Autowired
    private RagUnitMapper ragUnitMapper;

    @Autowired
    private UploadService uploadService;

    @Autowired
    private VectorStore vectorStore; // Spring AI VectorStore

    @Autowired
    private TextProcessor textProcessor;

    @Autowired
    private ImageProcessor imageProcessor;

    @Autowired
    private VideoProcessor videoProcessor;

    private final Tika tika = new Tika();

    public UploadResponse processAndStore(MultipartFile file) throws Exception {
        String filename = file.getOriginalFilename();
        byte[] fileBytes = file.getBytes();

        // Detect MIME type using Tika
        String mimeType = tika.detect(fileBytes, filename);
        log.info("Detected MIME type for {}: {}", filename, mimeType);

        // Find appropriate processor
        MediaProcessor processor = findProcessor(mimeType);
        if (processor == null) {
            throw new IllegalArgumentException("Unsupported file type: " + mimeType);
        }

        // Generate source ID
        String sourceId = UUID.randomUUID().toString();

        // Upload original file to MinIO
        String minioPath = sourceId + "/" + filename;
        uploadService.uploadFile(new ByteArrayInputStream(fileBytes), minioPath, mimeType);
        String minioUrl = uploadService.getFileUrl(minioPath);

        // Process file to extract RagUnits (pass URL for image processing)
        List<RagUnit> units = processor.process(new ByteArrayInputStream(fileBytes), filename, mimeType, minioUrl);

        // Determine source type
        SourceType sourceType = determineSourceType(mimeType);

        // Save each unit
        List<String> unitIds = new ArrayList<>();
        List<Document> documents = new ArrayList<>();

        for (RagUnit unit : units) {
            unit.setSourceId(sourceId);
            unit.setMinioPath(minioPath);
            unit.setMinioUrl(minioUrl);

            // Save to MySQL
            ragUnitMapper.insert(unit);
            unitIds.add(unit.getId());

            // Prepare for VectorStore
            if (unit.getContent() != null && !unit.getContent().isEmpty()) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("source_id", unit.getSourceId());
                metadata.put("source_type", unit.getSourceType().name());
                metadata.put("unit_id", unit.getId());
                if (unit.getStartTime() != null) metadata.put("start_time", unit.getStartTime());
                if (unit.getEndTime() != null) metadata.put("end_time", unit.getEndTime());
                metadata.put("filename", filename);

                Document doc = new Document(unit.getId(), unit.getContent(), metadata);
                documents.add(doc);
            }
        }

        // Batch add to VectorStore (Redis)
        if (!documents.isEmpty()) {
            try {
                vectorStore.add(documents);
                log.info("Added {} documents to VectorStore", documents.size());
            } catch (Exception e) {
                log.error("Failed to store documents in VectorStore", e);
                // Consider whether to rollback MySQL or just log error. For now, log.
            }
        }

        log.info("Processed file {} into {} chunks", filename, units.size());

        return UploadResponse.success(
                sourceId,
                filename,
                sourceType,
                units.size(),
                minioPath,
                minioUrl,
                unitIds
        );
    }

    private MediaProcessor findProcessor(String mimeType) {
        if (textProcessor.supports(mimeType)) return textProcessor;
        if (imageProcessor.supports(mimeType)) return imageProcessor;
        if (videoProcessor.supports(mimeType)) return videoProcessor;
        return null;
    }

    private SourceType determineSourceType(String mimeType) {
        if (mimeType.startsWith("image/")) return SourceType.IMAGE;
        if (mimeType.startsWith("video/")) return SourceType.VIDEO;
        return SourceType.TEXT;
    }

    public void deleteDocument(String filename) {
        // Find all units related to this filename
        QueryWrapper<RagUnit> wrapper = new QueryWrapper<>();
        wrapper.like("minio_path", "/" + filename);

        List<RagUnit> units = ragUnitMapper.selectList(wrapper);

        // Filter strictly for ending with /filename to handle exact matches
        List<RagUnit> targetUnits = new ArrayList<>();
        String targetPath = null;

        for (RagUnit unit : units) {
            if (unit.getMinioPath() != null && unit.getMinioPath().endsWith("/" + filename)) {
                targetUnits.add(unit);
                targetPath = unit.getMinioPath();
            }
        }

        if (targetUnits.isEmpty()) {
            throw new RuntimeException("Document not found: " + filename);
        }

        // 1. Delete from MinIO
        if (targetPath != null) {
            try {
                uploadService.deleteFile(targetPath);
            } catch (Exception e) {
                log.warn("Failed to delete file from MinIO: {}", targetPath, e);
            }
        }

        // 2. Delete from Redis and MySQL
        List<String> idsToDelete = new ArrayList<>();
        for (RagUnit unit : targetUnits) {
            idsToDelete.add(unit.getId());
            // Delete from MySQL
            ragUnitMapper.deleteById(unit.getId());
        }

        // Delete from VectorStore
        if (!idsToDelete.isEmpty()) {
            try {
                vectorStore.delete(idsToDelete);
            } catch (Exception e) {
                log.warn("Failed to delete documents from VectorStore", e);
            }
        }

        log.info("Deleted document {} with {} chunks", filename, targetUnits.size());
    }

    public List<String> getAllDocuments() {
        QueryWrapper<RagUnit> queryWrapper = new QueryWrapper<>();
        queryWrapper.select("DISTINCT minio_path");
        List<RagUnit> units = ragUnitMapper.selectList(queryWrapper);

        List<String> filenames = new ArrayList<>();
        for (RagUnit unit : units) {
            String path = unit.getMinioPath();
            if (path != null && path.contains("/")) {
                String name = path.substring(path.indexOf("/") + 1);
                if (!filenames.contains(name)) {
                    filenames.add(name);
                }
            }
        }
        return filenames;
    }
}

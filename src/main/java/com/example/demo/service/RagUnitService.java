package com.example.demo.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.demo.mapper.RagUnitMapper;
import com.example.demo.model.DocumentFile;
import com.example.demo.model.DocumentFileStatus;
import com.example.demo.model.RagNodeType;
import com.example.demo.model.RagUnit;
import com.example.demo.model.SourceType;
import com.example.demo.model.dto.DocumentFileStatusResponse;
import com.example.demo.model.dto.FileExistenceResponse;
import com.example.demo.model.dto.FileProcessTask;
import com.example.demo.model.dto.PageRequest;
import com.example.demo.model.dto.PageResponse;
import com.example.demo.model.dto.RagDocumentInfo;
import com.example.demo.model.dto.UploadResponse;
import com.example.demo.service.processor.ImageProcessor;
import com.example.demo.service.processor.MediaProcessor;
import com.example.demo.service.processor.PowerPointProcessor;
import com.example.demo.service.processor.TabularProcessor;
import com.example.demo.service.processor.TextProcessor;
import com.example.demo.service.processor.VideoProcessor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class RagUnitService {

    private final RagUnitMapper ragUnitMapper;
    private final UploadService uploadService;
    private final VectorStore leafVectorStore;
    private final VectorStore summaryVectorStore;
    private final TextProcessor textProcessor;
    private final ImageProcessor imageProcessor;
    private final VideoProcessor videoProcessor;
    private final PowerPointProcessor powerPointProcessor;
    private final com.example.demo.service.processor.PdfProcessor pdfProcessor;
    private final com.example.demo.service.processor.WordProcessor wordProcessor;
    private final TabularProcessor tabularProcessor;
    private final FileProcessProducer fileProcessProducer;
    private final DocumentFileService documentFileService;
    private final DocumentDeleteService documentDeleteService;
    private final HierarchicalIndexingService hierarchicalIndexingService;
    private final Tika tika = new Tika();

    public RagUnitService(RagUnitMapper ragUnitMapper,
                          UploadService uploadService,
                          @Qualifier("leafVectorStore") VectorStore leafVectorStore,
                          @Qualifier("summaryVectorStore") VectorStore summaryVectorStore,
                          TextProcessor textProcessor,
                          ImageProcessor imageProcessor,
                          VideoProcessor videoProcessor,
                          PowerPointProcessor powerPointProcessor,
                          com.example.demo.service.processor.PdfProcessor pdfProcessor,
                          com.example.demo.service.processor.WordProcessor wordProcessor,
                          TabularProcessor tabularProcessor,
                          FileProcessProducer fileProcessProducer,
                          DocumentFileService documentFileService,
                          DocumentDeleteService documentDeleteService,
                          HierarchicalIndexingService hierarchicalIndexingService) {
        this.ragUnitMapper = ragUnitMapper;
        this.uploadService = uploadService;
        this.leafVectorStore = leafVectorStore;
        this.summaryVectorStore = summaryVectorStore;
        this.textProcessor = textProcessor;
        this.imageProcessor = imageProcessor;
        this.videoProcessor = videoProcessor;
        this.powerPointProcessor = powerPointProcessor;
        this.pdfProcessor = pdfProcessor;
        this.wordProcessor = wordProcessor;
        this.tabularProcessor = tabularProcessor;
        this.fileProcessProducer = fileProcessProducer;
        this.documentFileService = documentFileService;
        this.documentDeleteService = documentDeleteService;
        this.hierarchicalIndexingService = hierarchicalIndexingService;
    }

    public void saveBatch(List<RagUnit> units) {
        if (units == null || units.isEmpty()) {
            return;
        }
        for (int i = 0; i < units.size(); i += 1000) {
            int end = Math.min(i + 1000, units.size());
            List<RagUnit> batch = units.subList(i, end);
            for (RagUnit unit : batch) {
                ragUnitMapper.insert(unit);
            }
        }
    }

    public FileExistenceResponse checkFileExists(String userId, String fileHash) {
        validateFileHash(fileHash);
        validateUserId(userId);

        DocumentFile documentFile = documentFileService.getByFileHash(userId, fileHash);
        if (documentFile == null || Boolean.TRUE.equals(documentFile.getDeleted())) {
            return FileExistenceResponse.notExists();
        }

        List<RagUnit> units = getUnitsBySourceId(documentFile.getSourceId());
        List<RagUnit> leafUnits = filterLeafUnits(units);
        List<String> leafUnitIds = new ArrayList<>();
        for (RagUnit unit : leafUnits) {
            leafUnitIds.add(unit.getId());
        }

        if (documentFile.getStatus() == DocumentFileStatus.SUCCESS) {
            return FileExistenceResponse.exists(
                    documentFile.getSourceId(),
                    documentFile.getFilename(),
                    safeChunkCount(documentFile, leafUnits),
                    documentFile.getMinioPath(),
                    documentFile.getMinioUrl(),
                    leafUnitIds
            );
        }

        if (documentFile.getStatus() != null && documentFile.getStatus().isProcessing()) {
            return FileExistenceResponse.processing(
                    documentFile.getSourceId(),
                    documentFile.getFilename(),
                    safeChunkCount(documentFile, leafUnits),
                    documentFile.getMinioPath(),
                    documentFile.getMinioUrl(),
                    documentFile.getStatus()
            );
        }

        if (documentFile.getStatus() == DocumentFileStatus.FAILED) {
            return FileExistenceResponse.failed(
                    documentFile.getSourceId(),
                    documentFile.getFilename(),
                    safeChunkCount(documentFile, leafUnits),
                    documentFile.getMinioPath(),
                    documentFile.getMinioUrl(),
                    documentFile.getErrorMessage()
            );
        }

        return FileExistenceResponse.notExists();
    }

    public DocumentFileStatusResponse getDocumentStatus(String userId, String fileHash) {
        validateFileHash(fileHash);
        validateUserId(userId);
        return documentFileService.getDocumentStatus(userId, fileHash);
    }

    public UploadResponse processAndStoreAsync(MultipartFile file, String fileHash, String userId) throws Exception {
        String filename = file.getOriginalFilename();
        long fileSize = file.getSize();

        validateFileHash(fileHash);
        validateUserId(userId);

        DocumentFile existing = documentFileService.getByFileHash(userId, fileHash);
        if (existing != null) {
            if (Boolean.TRUE.equals(existing.getDeleted())) {
                throw new IllegalStateException("该文档正在删除中，请稍后重试");
            }
            if (existing.getStatus() == DocumentFileStatus.SUCCESS) {
                return buildUploadResponse(existing, "文件已存在，无需重复上传", true);
            }
            if (existing.getStatus() != null && existing.getStatus().isProcessing()) {
                return buildUploadResponse(existing, "文件已在后台处理中，请稍后查看状态", true);
            }
            if (existing.getStatus() == DocumentFileStatus.FAILED) {
                throw new IllegalStateException("该文档已有失败记录，请先删除后重新上传");
            }
        }

        String mimeType;
        try (InputStream inputStream = file.getInputStream()) {
            mimeType = tika.detect(inputStream, filename);
        }

        MediaProcessor processor = findProcessorByMimeType(mimeType);
        if (processor == null) {
            throw new IllegalArgumentException("不支持的文件类型: " + mimeType);
        }

        SourceType sourceType = determineSourceType(mimeType);
        String sourceId = UUID.randomUUID().toString();
        String minioPath = sourceId + "/" + filename;

        documentFileService.createUploadingRecord(userId, sourceId, fileHash, filename, fileSize, sourceType);

        try {
            uploadService.uploadFile(file, minioPath);
            String minioUrl = uploadService.getFileUrl(minioPath);
            documentFileService.markUploadSuccess(userId, fileHash, sourceType, minioPath, minioUrl);

            FileProcessTask task = FileProcessTask.builder()
                    .sourceId(sourceId)
                    .filename(filename)
                    .fileHash(fileHash)
                    .userId(userId)
                    .mimeType(mimeType)
                    .minioPath(minioPath)
                    .minioUrl(minioUrl)
                    .fileSize(fileSize)
                    .createTimestamp(System.currentTimeMillis())
                    .build();

            fileProcessProducer.sendFileProcessTask(task);

            return buildUploadResponse(
                    documentFileService.getActiveByFileHash(userId, fileHash),
                    "文件上传成功，正在后台处理中...",
                    true
            );
        } catch (Exception e) {
            documentFileService.markFailed(userId, fileHash, normalizeErrorMessage(e));
            throw e;
        }
    }

    public MediaProcessor findProcessorByMimeType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return null;
        }
        if (powerPointProcessor.supports(mimeType)) {
            return powerPointProcessor;
        }
        if (pdfProcessor.supports(mimeType)) {
            return pdfProcessor;
        }
        if (wordProcessor.supports(mimeType)) {
            return wordProcessor;
        }
        if (tabularProcessor.supports(mimeType)) {
            return tabularProcessor;
        }
        if (textProcessor.supports(mimeType)) {
            return textProcessor;
        }
        if (imageProcessor.supports(mimeType)) {
            return imageProcessor;
        }
        if (videoProcessor.supports(mimeType)) {
            return videoProcessor;
        }
        return null;
    }

    public String deleteDocumentAsync(String userId, String fileHash) {
        validateUserId(userId);
        return documentDeleteService.asyncDeleteDocument(userId, fileHash);
    }

    @Deprecated
    public String deleteDocumentAsyncByFilename(String userId, String filename) {
        validateUserId(userId);
        return documentDeleteService.asyncDeleteDocumentByFilename(userId, filename);
    }

    @Deprecated
    public void deleteDocument(String filename) {
        documentDeleteService.safeDeleteDocumentSync(filename);
    }

    @Deprecated
    public void deleteDocumentUnsafe(String filename) {
        QueryWrapper<RagUnit> wrapper = new QueryWrapper<>();
        wrapper.like("minio_path", "/" + filename);

        List<RagUnit> units = ragUnitMapper.selectList(wrapper);
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

        if (targetPath != null) {
            uploadService.deleteFile(targetPath);
        }

        List<String> idsToDelete = new ArrayList<>();
        for (RagUnit unit : targetUnits) {
            idsToDelete.add(unit.getId());
            ragUnitMapper.deleteById(unit.getId());
        }

        if (!idsToDelete.isEmpty()) {
            leafVectorStore.delete(idsToDelete);
            summaryVectorStore.delete(idsToDelete);
        }
    }

    public List<String> getAllDocuments(String userId) {
        PageRequest request = new PageRequest();
        request.setPageSize(1000);
        request.setUserId(userId);
        List<String> filenames = new ArrayList<>();
        for (RagDocumentInfo info : documentFileService.getDocumentsPage(request).getRecords()) {
            filenames.add(info.getFilename());
        }
        return filenames;
    }

    public PageResponse<RagDocumentInfo> getDocumentsPage(PageRequest request) {
        return documentFileService.getDocumentsPage(request);
    }

    public List<RagUnit> getUnitsBySourceId(String sourceId) {
        QueryWrapper<RagUnit> wrapper = new QueryWrapper<>();
        wrapper.eq("source_id", sourceId)
                .orderByAsc("tree_level")
                .orderByAsc("ordinal")
                .orderByAsc("chunk_index");
        return ragUnitMapper.selectList(wrapper);
    }

    public List<RagUnit> getLeafUnitsBySourceId(String sourceId) {
        return filterLeafUnits(getUnitsBySourceId(sourceId));
    }

    public void removeIndexedData(String sourceId) {
        List<RagUnit> existingUnits = getUnitsBySourceId(sourceId);
        if (existingUnits.isEmpty()) {
            return;
        }

        List<String> ids = new ArrayList<>();
        for (RagUnit unit : existingUnits) {
            ids.add(unit.getId());
        }

        leafVectorStore.delete(ids);
        summaryVectorStore.delete(ids);

        QueryWrapper<RagUnit> wrapper = new QueryWrapper<>();
        wrapper.eq("source_id", sourceId);
        ragUnitMapper.delete(wrapper);
    }

    public void addUnitsToVectorStores(List<RagUnit> units, String filename) {
        List<Document> leafDocuments = new ArrayList<>();
        List<Document> summaryDocuments = new ArrayList<>();

        for (RagUnit unit : units) {
            if (unit.getContent() == null || unit.getContent().isBlank()) {
                continue;
            }

            Map<String, Object> metadata = buildVectorMetadata(unit, filename);
            Document document = new Document(unit.getId(), unit.getContent(), metadata);

            // 叶子节点和摘要节点分流写入不同索引，后续检索才能按层级控制路径。
            if (unit.getNodeType() == RagNodeType.LEAF || unit.getNodeType() == null) {
                leafDocuments.add(document);
            } else {
                summaryDocuments.add(document);
            }
        }

        if (!leafDocuments.isEmpty()) {
            batchAdd(leafVectorStore, leafDocuments);
        }
        if (!summaryDocuments.isEmpty()) {
            batchAdd(summaryVectorStore, summaryDocuments);
        }
    }

    private void batchAdd(VectorStore vectorStore, List<Document> documents) {
        int batchSize = 20; // 限制每批最多 20 条，安全避开 DashScope text-embedding-v3 API 限制最多 25 条的规定
        for (int i = 0; i < documents.size(); i += batchSize) {
            List<Document> batch = documents.subList(i, Math.min(i + batchSize, documents.size()));
            vectorStore.add(batch);
        }
    }

    public Map<String, Object> buildVectorMetadata(RagUnit unit, String filename) {
        Map<String, Object> metadata = new HashMap<>();
        // 把层级关系一起写进 metadata，便于命中后回溯 source、父节点和树层级。
        metadata.put("source_id", unit.getSourceId());
        metadata.put("source_type", unit.getSourceType().name());
        metadata.put("unit_id", unit.getId());
        if (unit.getUserId() != null) {
            metadata.put("user_id", unit.getUserId());
        }
        metadata.put("filename", filename);
        metadata.put("node_type", unit.getNodeType() != null ? unit.getNodeType().name() : RagNodeType.LEAF.name());
        metadata.put("tree_level", unit.getTreeLevel() != null ? unit.getTreeLevel() : 0);
        if (unit.getParentId() != null) {
            metadata.put("parent_id", unit.getParentId());
        }
        if (unit.getTitle() != null) {
            metadata.put("title", unit.getTitle());
        }
        if (unit.getChildCount() != null) {
            metadata.put("child_count", unit.getChildCount());
        }
        if (unit.getChunkIndex() != null) {
            metadata.put("chunk_index", unit.getChunkIndex());
        }
        if (unit.getStartTime() != null) {
            metadata.put("start_time", unit.getStartTime());
        }
        if (unit.getEndTime() != null) {
            metadata.put("end_time", unit.getEndTime());
        }
        return metadata;
    }

    private UploadResponse buildUploadResponse(DocumentFile documentFile, String message, boolean success) {
        List<RagUnit> leafUnits = documentFile.getStatus() == DocumentFileStatus.SUCCESS
                ? getLeafUnitsBySourceId(documentFile.getSourceId())
                : List.of();
        List<String> unitIds = new ArrayList<>();
        for (RagUnit unit : leafUnits) {
            unitIds.add(unit.getId());
        }

        return UploadResponse.builder()
                .success(success)
                .message(message)
                .sourceId(documentFile.getSourceId())
                .fileHash(documentFile.getFileHash())
                .filename(documentFile.getFilename())
                .sourceType(documentFile.getSourceType())
                .status(documentFile.getStatus())
                .errorMessage(documentFile.getErrorMessage())
                .chunksCreated(safeChunkCount(documentFile, leafUnits))
                .minioPath(documentFile.getMinioPath())
                .minioUrl(documentFile.getMinioUrl())
                .unitIds(unitIds)
                .build();
    }

    private int safeChunkCount(DocumentFile documentFile, List<RagUnit> leafUnits) {
        if (documentFile.getChunkCount() != null && documentFile.getChunkCount() > 0) {
            return documentFile.getChunkCount();
        }
        return leafUnits.size();
    }

    private List<RagUnit> filterLeafUnits(List<RagUnit> units) {
        List<RagUnit> leafUnits = new ArrayList<>();
        for (RagUnit unit : units) {
            if (unit.getNodeType() == null || unit.getNodeType() == RagNodeType.LEAF) {
                leafUnits.add(unit);
            }
        }
        return leafUnits;
    }

    private void validateFileHash(String fileHash) {
        if (fileHash == null || fileHash.trim().isEmpty()) {
            throw new IllegalArgumentException("文件哈希值不能为空");
        }
        if (!fileHash.matches("^[a-fA-F0-9]{64}$")) {
            throw new IllegalArgumentException("无效的 SHA-256 哈希格式");
        }
    }

    private void validateUserId(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("用户 ID 不能为空");
        }
    }

    private String normalizeErrorMessage(Exception e) {
        if (e == null || e.getMessage() == null || e.getMessage().isBlank()) {
            return "文件处理失败";
        }
        return e.getMessage();
    }

    private SourceType determineSourceType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return SourceType.TEXT;
        }
        if (mimeType.startsWith("image/")) {
            return SourceType.IMAGE;
        }
        if (mimeType.startsWith("video/")) {
            return SourceType.VIDEO;
        }
        return SourceType.TEXT;
    }
}

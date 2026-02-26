package com.example.demo.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.demo.mapper.RagUnitMapper;
import com.example.demo.model.RagUnit;
import com.example.demo.model.SourceType;
import com.example.demo.model.dto.FileExistenceResponse;
import com.example.demo.model.dto.FileProcessTask;
import com.example.demo.model.dto.UploadResponse;
import com.example.demo.service.processor.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
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

    @Autowired
    private PowerPointProcessor powerPointProcessor;

    @Autowired
    private FileProcessProducer fileProcessProducer;

    private final Tika tika = new Tika();

    /**
     * 批量保存 RagUnit 到数据库（使用 MyBatis-Plus 批量插入）
     * @param units RagUnit 列表
     */
    public void saveBatch(List<RagUnit> units) {
        if (units == null || units.isEmpty()) {
            return;
        }
        // MyBatis-Plus 的批量插入，默认每批 1000 条
        for (int i = 0; i < units.size(); i += 1000) {
            int end = Math.min(i + 1000, units.size());
            List<RagUnit> batch = units.subList(i, end);
            for (RagUnit unit : batch) {
                ragUnitMapper.insert(unit);
            }
        }
    }

    /**
     * 检查文件是否已存在（通过SHA-256哈希值）
     *
     * @param fileHash 文件SHA-256哈希值（由前端计算）
     * @return 文件存在性信息
     */
    public FileExistenceResponse checkFileExists(String fileHash) {
        if (fileHash == null || fileHash.trim().isEmpty()) {
            throw new IllegalArgumentException("文件哈希值不能为空");
        }

        // 验证SHA-256格式（64位十六进制字符）
        if (!fileHash.matches("^[a-fA-F0-9]{64}$")) {
            throw new IllegalArgumentException("无效的SHA-256哈希值格式");
        }

        log.info("检查文件是否存在: fileHash={}", fileHash);

        // 查询是否存在相同哈希值的文件
        QueryWrapper<RagUnit> wrapper = new QueryWrapper<>();
        wrapper.eq("file_hash", fileHash);
        wrapper.last("LIMIT 1"); // 只需要知道是否存在

        RagUnit existingUnit = ragUnitMapper.selectOne(wrapper);

        if (existingUnit == null) {
            log.info("文件不存在: fileHash={}", fileHash);
            return FileExistenceResponse.notExists();
        }

        // 文件已存在，查询该文件的所有切片
        QueryWrapper<RagUnit> allUnitsWrapper = new QueryWrapper<>();
        allUnitsWrapper.eq("source_id", existingUnit.getSourceId());
        List<RagUnit> allUnits = ragUnitMapper.selectList(allUnitsWrapper);

        List<String> unitIds = new ArrayList<>();
        for (RagUnit unit : allUnits) {
            unitIds.add(unit.getId());
        }

        log.info("文件已存在: fileHash={}, sourceId={}, chunks={}",
                fileHash, existingUnit.getSourceId(), allUnits.size());

        return FileExistenceResponse.exists(
                existingUnit.getSourceId(),
                existingUnit.getFilename(),
                allUnits.size(),
                existingUnit.getMinioPath(),
                existingUnit.getMinioUrl(),
                unitIds
        );
    }

    /**
     * 异步文件处理（新版本 - 推荐使用）
     * 支持SHA-256去重检查
     * 1. 检查文件是否已存在（通过fileHash）
     * 2. 如果不存在，上传文件到MinIO
     * 3. 发送消息到RabbitMQ
     * 4. 立即返回响应，后台异步处理
     */
    public UploadResponse processAndStoreAsync(MultipartFile file, String fileHash) throws Exception {
        String filename = file.getOriginalFilename();
        long fileSize = file.getSize();

        // 验证fileHash
        if (fileHash == null || fileHash.trim().isEmpty()) {
            throw new IllegalArgumentException("文件哈希值不能为空");
        }
        if (!fileHash.matches("^[a-fA-F0-9]{64}$")) {
            throw new IllegalArgumentException("无效的SHA-256哈希值格式");
        }

        // 检查文件是否已存在
        FileExistenceResponse existenceCheck = checkFileExists(fileHash);
        if (existenceCheck.getExists()) {
            log.info("文件已存在，跳过处理: fileHash={}, sourceId={}",
                    fileHash, existenceCheck.getSourceId());

            return UploadResponse.builder()
                    .success(true)
                    .message("文件已存在，无需重复上传")
                    .sourceId(existenceCheck.getSourceId())
                    .filename(existenceCheck.getFilename())
                    .sourceType(determineSourceType(null))
                    .chunksCreated(existenceCheck.getChunkCount())
                    .minioPath(existenceCheck.getMinioPath())
                    .minioUrl(existenceCheck.getMinioUrl())
                    .unitIds(existenceCheck.getUnitIds())
                    .build();
        }

        // 检测MIME类型（使用流式处理，避免加载整个文件到内存）
        String mimeType;
        try (InputStream inputStream = file.getInputStream()) {
            mimeType = tika.detect(inputStream, filename);
        }
        log.info("检测到文件类型: {} -> {}", filename, mimeType);

        // 验证是否支持该文件类型
        MediaProcessor processor = findProcessor(mimeType);
        if (processor == null) {
            throw new IllegalArgumentException("不支持的文件类型: " + mimeType);
        }

        // 生成源文件ID
        String sourceId = UUID.randomUUID().toString();

        // 1. 上传原始文件到MinIO
        String minioPath = sourceId + "/" + filename;
        uploadService.uploadFile(file, minioPath);
        String minioUrl = uploadService.getFileUrl(minioPath);
        log.info("文件已上传到MinIO: {}", minioPath);

        // 2. 构建处理任务并发送到RabbitMQ
        FileProcessTask task = FileProcessTask.builder()
                .sourceId(sourceId)
                .filename(filename)
                .fileHash(fileHash)
                .mimeType(mimeType)
                .minioPath(minioPath)
                .minioUrl(minioUrl)
                .fileSize(fileSize)
                .createTimestamp(System.currentTimeMillis())
                .build();

        fileProcessProducer.sendFileProcessTask(task);

        // 3. 立即返回响应（不等待处理完成）
        SourceType sourceType = determineSourceType(mimeType);
        return UploadResponse.builder()
                .success(true)
                .message("文件上传成功，正在后台处理中...")
                .sourceId(sourceId)
                .filename(filename)
                .sourceType(sourceType)
                .minioPath(minioPath)
                .minioUrl(minioUrl)
                .build();
    }

    /**
     * 异步文件处理（兼容旧版本，不带fileHash）
     * @deprecated 建议使用带fileHash参数的版本
     */
    @Deprecated
    public UploadResponse processAndStoreAsync(MultipartFile file) throws Exception {
        String filename = file.getOriginalFilename();
        byte[] fileBytes = file.getBytes();
        long fileSize = file.getSize();

        // 检测MIME类型
        String mimeType = tika.detect(fileBytes, filename);
        log.info("检测到文件类型: {} -> {}", filename, mimeType);

        // 验证是否支持该文件类型
        MediaProcessor processor = findProcessor(mimeType);
        if (processor == null) {
            throw new IllegalArgumentException("不支持的文件类型: " + mimeType);
        }

        // 生成源文件ID
        String sourceId = UUID.randomUUID().toString();

        // 1. 上传原始文件到MinIO
        String minioPath = sourceId + "/" + filename;
        uploadService.uploadFile(file, minioPath);
        String minioUrl = uploadService.getFileUrl(minioPath);
        log.info("文件已上传到MinIO: {}", minioPath);

        // 2. 构建处理任务并发送到RabbitMQ
        FileProcessTask task = FileProcessTask.builder()
            .sourceId(sourceId)
            .filename(filename)
            .fileHash(null)
            .mimeType(mimeType)
            .minioPath(minioPath)
            .minioUrl(minioUrl)
            .fileSize(fileSize)
            .createTimestamp(System.currentTimeMillis())
            .build();

        fileProcessProducer.sendFileProcessTask(task);

        // 3. 立即返回响应（不等待处理完成）
        SourceType sourceType = determineSourceType(mimeType);
        return UploadResponse.builder()
                .success(true)
                .message("文件上传成功，正在后台处理中...")
                .sourceId(sourceId)
                .filename(filename)
                .sourceType(sourceType)
                .minioPath(minioPath)
                .minioUrl(minioUrl)
                .build();
    }

    /**
     * 同步文件处理（旧版本 - 保留用于兼容或小文件场景）
     * 警告：大文件可能导致HTTP超时
     */
    @Deprecated
    public UploadResponse processAndStoreSync(MultipartFile file) throws Exception {
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
        uploadService.uploadFile(file, minioPath);
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
            unit.setFileHash(null);  // 旧方法不带fileHash
            unit.setFilename(filename);  // 设置filename字段
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

        // Batch add to VectorStore (Redis) with size limit - single thread to avoid rate limiting
        if (!documents.isEmpty()) {
            try {
                int batchSize = 10; // DashScope API limit
                int totalDocs = documents.size();

                if (totalDocs <= batchSize) {
                    // Direct upload if within limit
                    vectorStore.add(documents);
                    log.info("Added {} documents to VectorStore", documents.size());
                } else {
                    // Split into batches and upload sequentially
                    int numBatches = (totalDocs + batchSize - 1) / batchSize;

                    for (int i = 0; i < totalDocs; i += batchSize) {
                        int end = Math.min(i + batchSize, totalDocs);
                        List<Document> batch = documents.subList(i, end);
                        int batchNum = (i / batchSize) + 1;

                        vectorStore.add(batch);
                        log.info("Batch {}/{}: Successfully added {} documents to VectorStore",
                                batchNum, numBatches, batch.size());

                        // Add delay between batches to avoid rate limiting
                        if (i + batchSize < totalDocs) {
                            Thread.sleep(1000); // Wait 1 second between batches
                        }
                    }

                    log.info("Successfully added all {} documents to VectorStore in {} batches",
                            totalDocs, numBatches);
                }
            } catch (Exception e) {
                log.error("Failed to store documents in VectorStore", e);
                throw new RuntimeException("Failed to upload to vector store: " + e.getMessage(), e);
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

    /**
     * 根据MIME类型查找对应的处理器
     * 改为public，供FileProcessConsumer使用
     */
    public MediaProcessor findProcessorByMimeType(String mimeType) {
        if (powerPointProcessor.supports(mimeType)) return powerPointProcessor;
        if (textProcessor.supports(mimeType)) return textProcessor;
        if (imageProcessor.supports(mimeType)) return imageProcessor;
        if (videoProcessor.supports(mimeType)) return videoProcessor;
        return null;
    }

    @Deprecated
    private MediaProcessor findProcessor(String mimeType) {
        return findProcessorByMimeType(mimeType);
    }

    private SourceType determineSourceType(String mimeType) {
        if (mimeType.startsWith("image/")) return SourceType.IMAGE;
        if (mimeType.startsWith("video/")) return SourceType.VIDEO;
        return SourceType.TEXT;
    }

    @Autowired
    private DocumentDeleteService documentDeleteService;

    /**
     * 异步删除文档（新版本 - 使用SHA-256哈希值）
     * 推荐使用，通过fileHash精确定位，性能最优
     *
     * @param fileHash 文件SHA-256哈希值
     * @return 任务ID（用于查询删除状态）
     */
    public String deleteDocumentAsync(String fileHash) {
        return documentDeleteService.asyncDeleteDocument(fileHash);
    }

    /**
     * 异步删除文档（旧版本 - 使用filename）
     * @deprecated 建议使用 deleteDocumentAsync(String fileHash) 替代
     */
    @Deprecated
    public String deleteDocumentAsyncByFilename(String filename) {
        return documentDeleteService.asyncDeleteDocumentByFilename(filename);
    }

    /**
     * 同步删除文档（带补偿机制）- 已废弃
     */
    @Deprecated
    public void deleteDocument(String filename) {
        documentDeleteService.safeDeleteDocumentSync(filename);
    }

    /**
     * 删除文档（旧版本 - 无补偿机制）
     * 已废弃，可能导致数据不一致
     */
    @Deprecated
    public void deleteDocumentUnsafe(String filename) {
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

    public com.example.demo.model.dto.PageResponse<com.example.demo.model.dto.RagDocumentInfo> getDocumentsPage(
            com.example.demo.model.dto.PageRequest request) {

        // 查询总数
        Long total = ragUnitMapper.countDocuments(
                request.getSourceType(),
                request.getKeyword()
        );

        // 查询分页数据
        List<com.example.demo.model.dto.RagDocumentInfo> documents = ragUnitMapper.selectDocumentsPage(
                request.getSourceType(),
                request.getKeyword(),
                request.getSortBy(),
                request.getSortOrder(),
                request.getOffset(),
                request.getPageSize()
        );

        // 提取文件名
        for (com.example.demo.model.dto.RagDocumentInfo doc : documents) {
            String path = doc.getMinioPath();
            if (path != null && path.contains("/")) {
                String filename = path.substring(path.indexOf("/") + 1);
                doc.setFilename(filename);
            }
        }

        return com.example.demo.model.dto.PageResponse.of(
                documents,
                total,
                request.getPage(),
                request.getPageSize()
        );
    }
}

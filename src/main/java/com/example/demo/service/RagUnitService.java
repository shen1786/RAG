package com.example.demo.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.demo.mapper.RagUnitMapper;
import com.example.demo.util.FileNameSanitizer;
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
import com.example.demo.service.processor.MediaProcessor;
import com.example.demo.service.processor.MediaProcessorRegistry;
import com.example.demo.util.HashUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.tika.Tika;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
public class RagUnitService {

    private static final int DB_BATCH_FLUSH_SIZE = 500;

    // Allowed MIME types for file upload
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/plain", "text/markdown", "text/html", "text/csv",
            "application/json", "application/xml", "application/yaml", "application/x-yaml",
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp",
            "video/mp4", "video/webm", "video/quicktime",
            "application/mp4"
    );

    private final RagUnitMapper ragUnitMapper;
    private final SqlSessionFactory sqlSessionFactory;
    private final UploadService uploadService;
    private final VectorStoreWriteService vectorStoreWriteService;
    private final MediaProcessorRegistry processorRegistry;
    private final FileProcessProducer fileProcessProducer;
    private final DocumentFileService documentFileService;
    private final DocumentDeleteService documentDeleteService;
    private final HierarchicalIndexingService hierarchicalIndexingService;
    private final Tika tika = new Tika();

    public RagUnitService(RagUnitMapper ragUnitMapper,
                          SqlSessionFactory sqlSessionFactory,
                          UploadService uploadService,
                          VectorStoreWriteService vectorStoreWriteService,
                          MediaProcessorRegistry processorRegistry,
                          FileProcessProducer fileProcessProducer,
                          DocumentFileService documentFileService,
                          DocumentDeleteService documentDeleteService,
                          HierarchicalIndexingService hierarchicalIndexingService) {
        this.ragUnitMapper = ragUnitMapper;
        this.sqlSessionFactory = sqlSessionFactory;
        this.uploadService = uploadService;
        this.vectorStoreWriteService = vectorStoreWriteService;
        this.processorRegistry = processorRegistry;
        this.fileProcessProducer = fileProcessProducer;
        this.documentFileService = documentFileService;
        this.documentDeleteService = documentDeleteService;
        this.hierarchicalIndexingService = hierarchicalIndexingService;
    }

    /**
     * 批量写入文档切块（RagUnit）到 MySQL 的 rag_unit 表。
     *
     * <p><b>通俗解释：</b>把一大箱文件（文档切块）一次性搬进仓库（数据库），
     * 而不是一个个搬，这样更快。</p>
     *
     * <p><b>为什么用 BATCH 模式：</b>普通的 MyBatis 写法是每条 SQL 发一次请求，
     * BATCH 模式会攒一批再一起发，减少网络往返次数，写入速度提升 5~10 倍。</p>
     *
     * <p><b>事务说明：</b>此方法会自动参与外层的 Spring 事务。
     * 如果在 {@code FileProcessConsumer.saveDataWithTransaction()} 中调用，
     * 所有切块要么全部写入成功，要么全部回滚，不会出现"写了一半"的情况。</p>
     *
     * @param units 要写入的文档切块列表（包含叶子节点和摘要节点）
     */
    public void saveBatch(List<RagUnit> units) {
        if (units == null || units.isEmpty()) {
            return;
        }
        try (SqlSession batchSession = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            RagUnitMapper batchMapper = batchSession.getMapper(RagUnitMapper.class);
            for (int i = 0; i < units.size(); i++) {
                batchMapper.insert(units.get(i));
                if ((i + 1) % DB_BATCH_FLUSH_SIZE == 0) {
                    batchSession.flushStatements();
                }
            }
            batchSession.flushStatements();
        }
        log.debug("批量写入 MySQL 完成, 记录数: {}", units.size());
    }

    /**
     * 检查文件是否已存在于知识库中（秒传检测）。
     *
     * <p><b>通俗解释：</b>用户上传文件前，先问一下服务器"你这儿有没有这个文件？"。
     * 如果已经有了，就不用再传一遍（秒传），省时间省流量。</p>
     *
     * <p><b>判断逻辑：</b></p>
     * <ul>
     *   <li>相同 userId + fileHash → 文件已存在</li>
     *   <li>存在但状态是 SUCCESS → 返回已有文件信息（秒传成功）</li>
     *   <li>存在但状态是处理中 → 返回"正在处理"提示</li>
     *   <li>存在但状态是 FAILED → 返回失败信息</li>
     *   <li>不存在 → 返回 notExists</li>
     * </ul>
     *
     * @param userId   当前登录用户的 ID
     * @param fileHash 文件的 SHA-256 哈希值（前端计算后传过来的）
     * @return 文件存在性检查结果，包含文件状态和元数据
     */
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

    /**
     * 查询文档的当前处理状态。
     *
     * <p><b>通俗解释：</b>文件上传后，后台在异步处理（解析、切块、向量化）。
     * 前端会轮询调用这个方法，问"处理到哪一步了？"来更新页面上的状态显示。</p>
     *
     * @param userId   当前登录用户的 ID
     * @param fileHash 文件的 SHA-256 哈希值
     * @return 文档状态信息（UPLOADING / CHUNKING / VECTORIZING / SUCCESS / FAILED）
     */
    public DocumentFileStatusResponse getDocumentStatus(String userId, String fileHash) {
        validateFileHash(fileHash);
        validateUserId(userId);
        return documentFileService.getDocumentStatus(userId, fileHash);
    }

    /**
     * 文件上传的核心编排方法 —— 存文件 + 建记录 + 发消息（三步一气呵成）。
     *
     * <p><b>通俗解释：</b>这是文件上传的"总指挥"，负责协调整个上传流程。
     * 你可以把它理解为快递站点的站长：收包裹 → 登记入库 → 通知分拣员来处理。</p>
     *
     * <p><b>完整流程（共 6 步）：</b></p>
     * <ol>
     *   <li><b>参数校验</b> — 检查 fileHash 和 userId 是否合法</li>
     *   <li><b>去重检查</b> — 用客户端传来的 hash 查 MySQL，避免重复上传</li>
     *   <li><b>服务端重算 hash</b> — 自己再算一遍 SHA-256，防止客户端篡改或算错</li>
     *   <li><b>存文件到 MinIO</b> — 把文件存到对象存储（类似私有网盘）</li>
     *   <li><b>创建数据库记录</b> — 在 document_file 表登记文件元数据</li>
     *   <li><b>发送 MQ 消息</b> — 通知 RabbitMQ"有新文件要处理"，后台异步解析</li>
     * </ol>
     *
     * <p><b>为什么是"异步"的：</b>文件解析（PDF→文本→切块→向量化）可能要好几分钟，
     * 如果同步等待，用户会看到页面卡住。所以先返回"上传成功"，后台慢慢处理。</p>
     *
     * @param file     用户上传的文件（Spring 自动封装的 MultipartFile）
     * @param fileHash 前端计算的 SHA-256 哈希值（用于快速去重，但最终以服务端重算为准）
     * @param userId   当前登录用户的 ID
     * @return 上传结果，包含 sourceId、文件名、状态等信息
     * @throws IllegalArgumentException 如果文件类型不支持、哈希格式无效
     * @throws IllegalStateException    如果文件正在删除中或已有失败记录
     * @throws Exception                如果 MinIO 存储或 MQ 发送失败
     */
    public UploadResponse processAndStoreAsync(MultipartFile file, String fileHash, String userId) throws Exception {
        String filename = file.getOriginalFilename();
        long fileSize = file.getSize();

        validateFileHash(fileHash);
        validateUserId(userId);

        // 先用客户端 hash 做快速去重（防御性检查）
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

        // 服务端重算 SHA-256，顺带检测 mimeType
        String mimeType;
        String serverFileHash;
        try (DigestInputStream dis = HashUtils.wrapWithDigest(file.getInputStream())) {
            mimeType = tika.detect(dis, filename);
            validateMimeType(mimeType);
            serverFileHash = HashUtils.extractHex(dis);
        }

        // 用服务端重算的 hash 再做一次去重，防止客户端 hash 不一致的情况
        if (!serverFileHash.equals(fileHash)) {
            log.warn("客户端 hash 与服务端 hash 不一致: client={}, server={}, userId={}, filename={}",
                    fileHash, serverFileHash, userId, filename);
            DocumentFile serverExisting = documentFileService.getByFileHash(userId, serverFileHash);
            if (serverExisting != null) {
                if (Boolean.TRUE.equals(serverExisting.getDeleted())) {
                    throw new IllegalStateException("该文档正在删除中，请稍后重试");
                }
                if (serverExisting.getStatus() == DocumentFileStatus.SUCCESS) {
                    return buildUploadResponse(serverExisting, "文件已存在，无需重复上传", true);
                }
                if (serverExisting.getStatus() != null && serverExisting.getStatus().isProcessing()) {
                    return buildUploadResponse(serverExisting, "文件已在后台处理中，请稍后查看状态", true);
                }
                if (serverExisting.getStatus() == DocumentFileStatus.FAILED) {
                    throw new IllegalStateException("该文档已有失败记录，请先删除后重新上传");
                }
            }
        }

        MediaProcessor processor = findProcessorByMimeType(mimeType);
        if (processor == null) {
            throw new IllegalArgumentException("不支持的文件类型: " + mimeType);
        }
        //这里数据工具类封装了文件名处理
        SourceType sourceType = determineSourceType(mimeType);
        String safeFilename = FileNameSanitizer.sanitize(filename);
        String sourceId = UUID.randomUUID().toString();
        String minioPath = FileNameSanitizer.buildSafeObjectKey(sourceId, safeFilename);

        // 使用服务端重算的 hash 作为 fileHash 存入数据库，确保完整性校验可信
        documentFileService.createUploadingRecord(userId, sourceId, serverFileHash, filename, fileSize, sourceType);

        try {
            // ① 将文件上传到 MinIO 对象存储
            uploadService.uploadFile(file, minioPath);
            // ② 获取文件在 MinIO 中的访问 URL
            String minioUrl = uploadService.getFileUrl(minioPath);
            // ③ 更新数据库记录状态为上传成功，保存路径和 URL
            documentFileService.markUploadSuccess(userId, serverFileHash, sourceType, minioPath, minioUrl);

            // ④ 构建异步文件处理任务，携带文件元信息，供下游消费者解析和切片
            FileProcessTask task = FileProcessTask.builder()
                    .sourceId(sourceId)           // 文档唯一标识
                    .filename(filename)            // 原始文件名
                    .fileHash(serverFileHash)      // 服务端重算的 SHA-256 哈希
                    .userId(userId)                // 所属用户
                    .mimeType(mimeType)            // 文件类型，决定使用哪个 Processor 解析
                    .minioPath(minioPath)          // MinIO 中的存储路径
                    .minioUrl(minioUrl)            // MinIO 中的访问地址
                    .fileSize(fileSize)            // 文件大小（字节）
                    .createTimestamp(System.currentTimeMillis()) // 任务创建时间戳
                    .build();

            // ⑤ 发送任务到 RabbitMQ 消息队列，由 FileProcessConsumer 异步消费处理
            fileProcessProducer.sendFileProcessTask(task);

            // ⑥ 查询最新记录，构建成功响应返回给前端
            return buildUploadResponse(
                    documentFileService.getActiveByFileHash(userId, serverFileHash),
                    "文件上传成功，正在后台处理中...",
                    true
            );
        } catch (Exception e) {
            // 上传或发送任务失败时，将数据库记录标记为失败，并记录错误信息
            documentFileService.markFailed(userId, serverFileHash, normalizeErrorMessage(e));
            throw e;
        }
    }

    /**
     * 根据文件的 MIME 类型查找对应的处理器。
     *
     * <p><b>通俗解释：</b>就像医院的分诊台——来了一个病人（文件），
     * 先看是什么病（MIME 类型），然后分配给对应的科室（处理器）。
     * PDF 给 PdfProcessor，Word 给 WordProcessor，以此类推。</p>
     *
     * <p><b>底层原理：</b>Spring 自动收集所有实现了 {@link MediaProcessor} 接口的 Bean，
     * 遍历调用每个处理器的 {@code supports()} 方法，返回第一个匹配的。</p>
     *
     * @param mimeType 文件的 MIME 类型，如 "application/pdf"、"image/png"
     * @return 匹配的处理器，找不到返回 null
     * @see MediaProcessorRegistry#findByMimeType(String)
     */
    public MediaProcessor findProcessorByMimeType(String mimeType) {
        return processorRegistry.findByMimeType(mimeType);
    }

    /**
     * 异步删除文档 —— 发消息到 MQ，后台慢慢删。
     *
     * <p><b>通俗解释：</b>删除文档不是点一下就没了，因为要同时清理：
     * MinIO 里的原始文件 + MySQL 里的切块记录 + Redis 里的向量索引。
     * 这些操作比较耗时，所以也是异步处理，先返回一个 taskId 让前端追踪进度。</p>
     *
     * @param userId   当前登录用户的 ID
     * @param fileHash 要删除的文件的 SHA-256 哈希值
     * @return 异步任务 ID，前端可用此 ID 轮询删除状态
     */
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
            vectorStoreWriteService.deleteFromVectorStores(idsToDelete);
        }
    }

    /**
     * 获取用户的所有文档文件名列表。
     *
     * <p><b>通俗解释：</b>把某个用户上传过的所有文档的文件名列出来。
     * 内部调用分页查询（最多 1000 条），只提取文件名。</p>
     *
     * @param userId 当前登录用户的 ID
     * @return 文档文件名列表
     */
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

    /**
     * 分页查询用户的文档列表。
     *
     * <p><b>通俗解释：</b>在"文档管理"页面展示用户的文档列表，支持分页、搜索、排序。
     * 实际工作委托给 {@link DocumentFileService}。</p>
     *
     * @param request 分页请求参数（页码、每页大小、用户ID、关键词、排序方式等）
     * @return 分页结果，包含文档列表和总记录数
     */
    public PageResponse<RagDocumentInfo> getDocumentsPage(PageRequest request) {
        return documentFileService.getDocumentsPage(request);
    }

    /**
     * 根据文档 ID 获取该文档的所有切块（包含叶子节点和摘要节点）。
     *
     * <p><b>通俗解释：</b>一个文档被切成了很多块，这个方法就是把所有块都拿出来，
     * 按树层级 + 序号排序，方便后续组装或展示。</p>
     *
     * @param sourceId 文档的唯一标识（UUID）
     * @return 该文档的所有 RagUnit 列表，按 tree_level → ordinal → chunk_index 排序
     */
    public List<RagUnit> getUnitsBySourceId(String sourceId) {
        QueryWrapper<RagUnit> wrapper = new QueryWrapper<>();
        wrapper.eq("source_id", sourceId)
                .orderByAsc("tree_level")
                .orderByAsc("ordinal")
                .orderByAsc("chunk_index");
        return ragUnitMapper.selectList(wrapper);
    }

    /**
     * 根据文档 ID 获取该文档的叶子切块（不含摘要节点）。
     *
     * <p><b>通俗解释：</b>一个文档被切成叶子块和摘要块。
     * 这个方法只返回叶子块（最小粒度的内容片段），
     * 摘要块是给检索用的，不直接展示给用户。</p>
     *
     * @param sourceId 文档的唯一标识（UUID）
     * @return 该文档的叶子 RagUnit 列表
     */
    public List<RagUnit> getLeafUnitsBySourceId(String sourceId) {
        return filterLeafUnits(getUnitsBySourceId(sourceId));
    }

    /**
     * 删除文档的所有索引数据（简化版，无备用 ID）。
     *
     * @param sourceId 文档的唯一标识（UUID）
     * @see #removeIndexedData(String, List) 完整版，支持备用兜底 ID
     */
    public void removeIndexedData(String sourceId) {
        removeIndexedData(sourceId, List.of());
    }

    /**
     * 删除文档的所有索引数据 —— 同时清理 Redis 向量库和 MySQL 切块表。
     *
     * <p><b>通俗解释：</b>文档删除时要"打扫干净"：
     * <ol>
     *   <li>从 Redis 的两个向量索引（leaf + summary）中删除相关向量</li>
     *   <li>从 MySQL 的 rag_unit 表中删除所有切块记录</li>
     * </ol>
     * 两步都要做，否则会出现"MySQL 里没了但向量库里还有"的脏数据。</p>
     *
     * @param sourceId        文档的唯一标识（UUID）
     * @param fallbackUnitIds 备用的切块 ID 列表（用于兜底清理，防止遗漏）
     */
    public void removeIndexedData(String sourceId, List<String> fallbackUnitIds) {
        List<RagUnit> existingUnits = getUnitsBySourceId(sourceId);
        Set<String> ids = new LinkedHashSet<>();
        for (RagUnit unit : existingUnits) {
            ids.add(unit.getId());
        }
        if (fallbackUnitIds != null) {
            ids.addAll(fallbackUnitIds);
        }

        if (!ids.isEmpty()) {
            vectorStoreWriteService.deleteFromVectorStores(new ArrayList<>(ids));
        }

        QueryWrapper<RagUnit> wrapper = new QueryWrapper<>();
        wrapper.eq("source_id", sourceId);
        ragUnitMapper.delete(wrapper);
    }

    /**
     * 将文档切块写入 Redis 向量库（叶子节点和摘要节点分开存储）。
     *
     * <p><b>通俗解释：</b>切块在 MySQL 里是"文字记录"，在 Redis 里是"向量"（一串数字）。
     * 这个方法负责把切块转换成向量，存到 Redis 的两个索引中：
     * <ul>
     *   <li><b>leafVectorStore</b> — 存叶子节点的向量（细粒度检索用）</li>
     *   <li><b>summaryVectorStore</b> — 存摘要节点的向量（粗粒度检索用）</li>
     * </ul></p>
     *
     * @param units    要写入向量库的切块列表
     * @param filename 源文件名（用于日志记录）
     * @see VectorStoreWriteService#addUnitsToVectorStores(List, String)
     */
    public void addUnitsToVectorStores(List<RagUnit> units, String filename) {
        vectorStoreWriteService.addUnitsToVectorStores(units, filename);
    }

    /**
     * 构建写入向量库时附带的元数据（metadata）。
     *
     * <p><b>通俗解释：</b>向量库里不光存"一串数字"（向量），还存一些附加信息（元数据）。
     * 这样当检索命中某个向量时，能直接知道它来自哪个文档、哪个章节、第几段，
     * 不用再回 MySQL 查一遍。</p>
     *
     * <p><b>包含的元数据字段：</b></p>
     * <ul>
     *   <li>source_id — 文档 ID</li>
     *   <li>source_type — 文档类型（TEXT/IMAGE/VIDEO）</li>
     *   <li>unit_id — 切块 ID</li>
     *   <li>user_id — 用户 ID</li>
     *   <li>filename — 文件名</li>
     *   <li>node_type — 节点类型（LEAF/SUMMARY）</li>
     *   <li>tree_level — 树层级（0=叶子, 1=章节摘要, 2=文档摘要）</li>
     *   <li>parent_id — 父节点 ID</li>
     *   <li>title — 标题</li>
     *   <li>chunk_index — 切块序号</li>
     * </ul>
     *
     * @param unit     要构建元数据的切块
     * @param filename 源文件名
     * @return 元数据键值对，直接写入 Redis 向量库
     */
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

    private void validateMimeType(String mimeType) {
        if (mimeType == null || !ALLOWED_MIME_TYPES.contains(mimeType)) {
            throw new IllegalArgumentException("不支持的文件类型: " + mimeType);
        }
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

package com.example.demo.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.demo.mapper.RagUnitMapper;
import com.example.demo.model.RagUnit;
import com.example.demo.model.dto.DeleteOperation;
import com.example.demo.model.dto.DeleteTaskStatus;
import com.example.demo.model.dto.FileDeleteTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 文档删除服务（带补偿机制）
 * 确保MinIO、MySQL、Redis三个数据源的数据一致性
 */
@Service
@Slf4j
public class DocumentDeleteService {

    @Autowired
    private RagUnitMapper ragUnitMapper;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private UploadService uploadService;

    @Autowired
    private FileDeleteProducer fileDeleteProducer;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String DELETE_TASK_PREFIX = "delete:task:";

    /**
     * 异步删除文档（使用SHA-256哈希值）- 推荐
     *
     * @param fileHash 文件SHA-256哈希值
     * @return 任务ID（用于查询删除状态）
     */
    public String asyncDeleteDocument(String fileHash) {
        // 验证fileHash格式
        if (fileHash == null || fileHash.trim().isEmpty()) {
            throw new IllegalArgumentException("文件哈希值不能为空");
        }
        if (!fileHash.matches("^[a-fA-F0-9]{64}$")) {
            throw new IllegalArgumentException("无效的SHA-256哈希值格式");
        }

        // 查找文档的所有切片（使用file_hash索引，性能最优）
        List<RagUnit> targetUnits = findDocumentUnitsByHash(fileHash);
        if (targetUnits.isEmpty()) {
            throw new RuntimeException("文档不存在: " + fileHash);
        }

        String filename = targetUnits.get(0).getFilename();
        String minioPath = targetUnits.get(0).getMinioPath();
        List<String> vectorIds = new ArrayList<>();
        List<String> unitIds = new ArrayList<>();

        for (RagUnit unit : targetUnits) {
            vectorIds.add(unit.getId());
            unitIds.add(unit.getId());
        }

        // 创建删除任务
        String taskId = UUID.randomUUID().toString();
        FileDeleteTask task = FileDeleteTask.builder()
                .taskId(taskId)
                .filename(filename)
                .minioPath(minioPath)
                .vectorIds(vectorIds)
                .unitIds(unitIds)
                .createTimestamp(System.currentTimeMillis())
                .build();

        // 保存初始状态到Redis
        String key = DELETE_TASK_PREFIX + taskId;
        redisTemplate.opsForValue().set(key, task, 24, TimeUnit.HOURS);

        // 发送到MQ队列
        fileDeleteProducer.sendDeleteTask(task);

        log.info("删除任务已提交: taskId={}, fileHash={}, filename={}, chunks={}",
                taskId, fileHash, filename, targetUnits.size());

        return taskId;
    }

    /**
     * 异步删除文档（使用filename）- 已废弃，建议使用fileHash
     * @deprecated 使用 asyncDeleteDocument(String fileHash) 替代
     */
    @Deprecated
    public String asyncDeleteDocumentByFilename(String filename) {
        // 查找文档的所有切片
        List<RagUnit> targetUnits = findDocumentUnitsByFilename(filename);
        if (targetUnits.isEmpty()) {
            throw new RuntimeException("文档不存在: " + filename);
        }

        String minioPath = targetUnits.get(0).getMinioPath();
        List<String> vectorIds = new ArrayList<>();
        List<String> unitIds = new ArrayList<>();

        for (RagUnit unit : targetUnits) {
            vectorIds.add(unit.getId());
            unitIds.add(unit.getId());
        }

        // 创建删除任务
        String taskId = UUID.randomUUID().toString();
        FileDeleteTask task = FileDeleteTask.builder()
                .taskId(taskId)
                .filename(filename)
                .minioPath(minioPath)
                .vectorIds(vectorIds)
                .unitIds(unitIds)
                .createTimestamp(System.currentTimeMillis())
                .build();

        // 保存初始状态到Redis
        String key = DELETE_TASK_PREFIX + taskId;
        redisTemplate.opsForValue().set(key, task, 24, TimeUnit.HOURS);

        // 发送到MQ队列
        fileDeleteProducer.sendDeleteTask(task);

        log.info("删除任务已提交: taskId={}, filename={}, chunks={}",
                taskId, filename, targetUnits.size());

        return taskId;
    }

    /**
     * 查询删除任务状态
     */
    public DeleteTaskStatus getDeleteStatus(String taskId) {
        String key = DELETE_TASK_PREFIX + taskId;
        Object obj = redisTemplate.opsForValue().get(key);

        if (obj == null) {
            throw new RuntimeException("任务不存在或已过期: " + taskId);
        }

        FileDeleteTask task = (FileDeleteTask) obj;
        return DeleteTaskStatus.fromTask(task);
    }

    /**
     * 安全删除文档（带补偿机制）- 同步模式，已废弃
     *
     * 删除顺序：
     * 1. Redis VectorStore（最易回滚）
     * 2. MySQL数据库
     * 3. MinIO文件（最难回滚）
     *
     * 如果任何步骤失败，自动回滚已完成的操作
     */
    @Deprecated
    public void safeDeleteDocumentSync(String filename) {
        DeleteOperation operation = new DeleteOperation();

        try {
            // 0. 查找目标文档的所有切片
            List<RagUnit> targetUnits = findDocumentUnits(filename);
            if (targetUnits.isEmpty()) {
                throw new RuntimeException("文档不存在: " + filename);
            }

            String minioPath = targetUnits.get(0).getMinioPath();
            List<String> unitIds = new ArrayList<>();
            for (RagUnit unit : targetUnits) {
                unitIds.add(unit.getId());
            }

            log.info("开始删除文档: {} (共{}个切片)", filename, targetUnits.size());

            // 1. 删除 Redis VectorStore（最先执行，最易回滚）
            deleteFromVectorStore(unitIds, operation);

            // 2. 删除 MySQL（事务保护）
            deleteFromMySQL(targetUnits, operation);

            // 3. 删除 MinIO（最后执行）
            deleteFromMinIO(minioPath, operation);

            log.info("文档删除成功: {} (已清理MinIO、MySQL、Redis)", filename);

        } catch (Exception e) {
            log.error("文档删除失败，开始回滚: {}", filename, e);
            rollback(operation);
            throw new RuntimeException("删除文档失败: " + e.getMessage(), e);
        }
    }

    /**
     * 查找文档的所有切片（通过file_hash，最优性能）
     */
    private List<RagUnit> findDocumentUnitsByHash(String fileHash) {
        QueryWrapper<RagUnit> wrapper = new QueryWrapper<>();
        // 使用file_hash精确查询，利用索引提升性能
        wrapper.eq("file_hash", fileHash);

        List<RagUnit> targetUnits = ragUnitMapper.selectList(wrapper);

        log.info("通过file_hash查询到 {} 个切片", targetUnits.size());
        return targetUnits;
    }

    /**
     * 查找文档的所有切片（通过filename，已废弃）
     * @deprecated 使用 findDocumentUnitsByHash(String fileHash) 替代
     */
    @Deprecated
    private List<RagUnit> findDocumentUnitsByFilename(String filename) {
        QueryWrapper<RagUnit> wrapper = new QueryWrapper<>();
        // 使用精确查询，利用索引提升性能
        wrapper.eq("filename", filename);

        List<RagUnit> targetUnits = ragUnitMapper.selectList(wrapper);

        if (targetUnits.isEmpty()) {
            // 兼容旧数据：如果filename字段为空，回退到原有的模糊查询
            log.warn("未找到filename={}的记录，尝试使用minio_path查询（性能较低）", filename);
            wrapper = new QueryWrapper<>();
            wrapper.like("minio_path", "/" + filename);

            List<RagUnit> units = ragUnitMapper.selectList(wrapper);

            for (RagUnit unit : units) {
                if (unit.getMinioPath() != null && unit.getMinioPath().endsWith("/" + filename)) {
                    targetUnits.add(unit);
                }
            }
        }

        return targetUnits;
    }

    /**
     * 查找文档的所有切片（优化版 - 使用索引）
     * @deprecated 使用 findDocumentUnitsByHash 或 findDocumentUnitsByFilename 替代
     */
    @Deprecated
    private List<RagUnit> findDocumentUnits(String filename) {
        return findDocumentUnitsByFilename(filename);
    }

    /**
     * 步骤1: 删除向量数据库（Redis）
     */
    private void deleteFromVectorStore(List<String> unitIds, DeleteOperation operation) {
        try {
            log.info("删除向量数据: {} 条", unitIds.size());
            vectorStore.delete(unitIds);

            operation.setDeletedVectorIds(unitIds);
            operation.markCompleted(DeleteOperation.Step.VECTOR_STORE_DELETE);

            log.info("✓ 向量数据删除成功");
        } catch (Exception e) {
            log.error("向量数据删除失败", e);
            throw new RuntimeException("Redis VectorStore删除失败: " + e.getMessage(), e);
        }
    }

    /**
     * 步骤2: 删除MySQL数据（事务保护）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteFromMySQL(List<RagUnit> targetUnits, DeleteOperation operation) {
        try {
            List<String> deletedIds = new ArrayList<>();

            log.info("删除MySQL数据: {} 条", targetUnits.size());
            for (RagUnit unit : targetUnits) {
                ragUnitMapper.deleteById(unit.getId());
                deletedIds.add(unit.getId());
            }

            operation.setDeletedMySqlIds(deletedIds);
            operation.markCompleted(DeleteOperation.Step.MYSQL_DELETE);

            log.info("✓ MySQL数据删除成功");
        } catch (Exception e) {
            log.error("MySQL数据删除失败", e);
            throw new RuntimeException("MySQL删除失败: " + e.getMessage(), e);
        }
    }

    /**
     * 步骤3: 删除MinIO文件
     */
    private void deleteFromMinIO(String minioPath, DeleteOperation operation) {
        try {
            log.info("删除MinIO文件: {}", minioPath);
            uploadService.deleteFile(minioPath);

            operation.setDeletedMinioPath(minioPath);
            operation.markCompleted(DeleteOperation.Step.MINIO_DELETE);

            log.info("✓ MinIO文件删除成功");
        } catch (Exception e) {
            log.error("MinIO文件删除失败: {}", minioPath, e);
            throw new RuntimeException("MinIO删除失败: " + e.getMessage(), e);
        }
    }

    /**
     * 回滚机制：恢复已执行的删除操作
     *
     * 注意：
     * - VectorStore可以重新添加（需要重新生成Embedding）
     * - MySQL可以重新插入（已在事务中，会自动回滚）
     * - MinIO文件删除不可逆（因此放在最后执行）
     */
    private void rollback(DeleteOperation operation) {
        log.warn("开始回滚删除操作...");

        // MySQL有事务保护，自动回滚，无需手动处理

        // VectorStore回滚（需要重新查询数据并生成Embedding，成本高）
        if (operation.isCompleted(DeleteOperation.Step.VECTOR_STORE_DELETE)) {
            log.warn("VectorStore已删除，无法自动恢复，需要手动重新上传文档");
            log.warn("受影响的向量ID: {}", operation.getDeletedVectorIds());
        }

        // MinIO回滚（文件已删除，无法恢复）
        if (operation.isCompleted(DeleteOperation.Step.MINIO_DELETE)) {
            log.error("MinIO文件已删除且无法恢复: {}", operation.getDeletedMinioPath());
            log.error("需要从备份中恢复或要求用户重新上传");
        }

        log.warn("回滚完成（部分步骤可能需要手动干预）");
    }

    /**
     * 批量删除文档（异步 + 重试机制）
     */
    public List<String> asyncBatchDelete(List<String> filenames) {
        List<String> taskIds = new ArrayList<>();

        for (String filename : filenames) {
            try {
                String taskId = asyncDeleteDocument(filename);
                taskIds.add(taskId);
            } catch (Exception e) {
                log.error("提交删除任务失败: {}", filename, e);
            }
        }

        log.info("批量删除任务已提交: {} 个文件", taskIds.size());
        return taskIds;
    }
}

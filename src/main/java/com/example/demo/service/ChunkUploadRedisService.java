package com.example.demo.service;

import com.example.demo.model.dto.ChunkUploadStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.concurrent.TimeUnit;

/**
 * 分片上传Redis管理服务
 */
@Service
@Slf4j
public class ChunkUploadRedisService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${file.upload.expire-hours:24}")
    private int expireHours;

    private static final String CHUNK_UPLOAD_PREFIX = "chunk:upload:";
    private static final String CHUNK_SET_PREFIX = "chunk:set:";  // 新增：用于存储已上传分片的 SET

    /**
     * 获取Redis Key
     */
    private String getKey(String fileHash) {
        return CHUNK_UPLOAD_PREFIX + fileHash;
    }

    /**
     * 获取已上传分片 SET 的 Key
     */
    private String getChunkSetKey(String fileHash) {
        return CHUNK_SET_PREFIX + fileHash;
    }

    /**
     * 初始化上传状态
     */
    public void initUploadStatus(String fileHash, String filename, Long fileSize, Integer totalChunks, String tempDir) {
        ChunkUploadStatus status = ChunkUploadStatus.builder()
                .fileHash(fileHash)
                .filename(filename)
                .fileSize(fileSize)
                .totalChunks(totalChunks)
                .uploadedChunks(new HashSet<>())
                .status(ChunkUploadStatus.UploadStatus.UPLOADING)
                .tempDir(tempDir)
                .createTime(System.currentTimeMillis())
                .updateTime(System.currentTimeMillis())
                .build();

        String key = getKey(fileHash);
        redisTemplate.opsForValue().set(key, status, expireHours, TimeUnit.HOURS);
        log.info("初始化上传状态: fileHash={}, totalChunks={}", fileHash, totalChunks);
    }

    /**
     * 获取上传状态
     */
    public ChunkUploadStatus getUploadStatus(String fileHash) {
        String key = getKey(fileHash);
        Object obj = redisTemplate.opsForValue().get(key);
        return obj != null ? (ChunkUploadStatus) obj : null;
    }

    /**
     * 标记分片已上传（使用 Redis SET 解决并发竞态问题）
     */
    public void markChunkUploaded(String fileHash, Integer chunkNumber) {
        String key = getKey(fileHash);
        String chunkSetKey = getChunkSetKey(fileHash);

        // 1. 使用 Redis SET 原子性添加已上传分片（解决竞态条件）
        redisTemplate.opsForSet().add(chunkSetKey, chunkNumber);
        redisTemplate.expire(chunkSetKey, expireHours, TimeUnit.HOURS);

        // 2. 获取当前状态
        ChunkUploadStatus status = getUploadStatus(fileHash);
        if (status == null) {
            log.warn("上传状态不存在: fileHash={}", fileHash);
            return;
        }

        // 3. 从 Redis SET 获取所有已上传分片数量
        Long uploadedCount = redisTemplate.opsForSet().size(chunkSetKey);
        status.setUpdateTime(System.currentTimeMillis());

        // 4. 检查是否所有分片都已上传
        if (uploadedCount != null && uploadedCount >= status.getTotalChunks()) {
            status.setStatus(ChunkUploadStatus.UploadStatus.COMPLETED);
            log.info("所有分片上传完成: fileHash={}, totalChunks={}", fileHash, uploadedCount);
        }

        redisTemplate.opsForValue().set(key, status, expireHours, TimeUnit.HOURS);
        log.debug("标记分片已上传: fileHash={}, chunk={}, uploaded={}/{}",
                fileHash, chunkNumber, uploadedCount, status.getTotalChunks());
    }

    /**
     * 删除上传状态
     */
    public void deleteUploadStatus(String fileHash) {
        String key = getKey(fileHash);
        String chunkSetKey = getChunkSetKey(fileHash);

        // 删除状态对象和分片 SET
        redisTemplate.delete(key);
        redisTemplate.delete(chunkSetKey);
        log.info("删除上传状态: fileHash={}", fileHash);
    }

    /**
     * 检查分片是否已上传（从 Redis SET 中检查）
     */
    public boolean isChunkUploaded(String fileHash, Integer chunkNumber) {
        String chunkSetKey = getChunkSetKey(fileHash);
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(chunkSetKey, chunkNumber));
    }

    /**
     * 标记上传失败
     */
    public void markUploadFailed(String fileHash) {
        ChunkUploadStatus status = getUploadStatus(fileHash);
        if (status != null) {
            status.setStatus(ChunkUploadStatus.UploadStatus.FAILED);
            status.setUpdateTime(System.currentTimeMillis());
            String key = getKey(fileHash);
            redisTemplate.opsForValue().set(key, status, expireHours, TimeUnit.HOURS);
            log.error("标记上传失败: fileHash={}", fileHash);
        }
    }
}

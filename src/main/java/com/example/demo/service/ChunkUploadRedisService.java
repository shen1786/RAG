package com.example.demo.service;

import com.example.demo.model.dto.ChunkUploadStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static final String CHUNK_UPLOAD_PREFIX = "chunk:upload:";
    private static final int EXPIRE_HOURS = 24; // 24小时过期

    /**
     * 获取Redis Key
     */
    private String getKey(String fileHash) {
        return CHUNK_UPLOAD_PREFIX + fileHash;
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
        redisTemplate.opsForValue().set(key, status, EXPIRE_HOURS, TimeUnit.HOURS);
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
     * 标记分片已上传
     */
    public void markChunkUploaded(String fileHash, Integer chunkNumber) {
        String key = getKey(fileHash);
        ChunkUploadStatus status = getUploadStatus(fileHash);

        if (status == null) {
            log.warn("上传状态不存在: fileHash={}", fileHash);
            return;
        }

        status.getUploadedChunks().add(chunkNumber);
        status.setUpdateTime(System.currentTimeMillis());

        // 检查是否所有分片都已上传
        if (status.isAllChunksUploaded()) {
            status.setStatus(ChunkUploadStatus.UploadStatus.COMPLETED);
            log.info("所有分片上传完成: fileHash={}", fileHash);
        }

        redisTemplate.opsForValue().set(key, status, EXPIRE_HOURS, TimeUnit.HOURS);
        log.debug("标记分片已上传: fileHash={}, chunk={}, progress={}%",
                fileHash, chunkNumber, status.getProgress());
    }

    /**
     * 删除上传状态
     */
    public void deleteUploadStatus(String fileHash) {
        String key = getKey(fileHash);
        redisTemplate.delete(key);
        log.info("删除上传状态: fileHash={}", fileHash);
    }

    /**
     * 检查分片是否已上传
     */
    public boolean isChunkUploaded(String fileHash, Integer chunkNumber) {
        ChunkUploadStatus status = getUploadStatus(fileHash);
        return status != null && status.getUploadedChunks().contains(chunkNumber);
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
            redisTemplate.opsForValue().set(key, status, EXPIRE_HOURS, TimeUnit.HOURS);
            log.error("标记上传失败: fileHash={}", fileHash);
        }
    }
}

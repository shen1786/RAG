package com.example.demo.service;

import com.example.demo.model.dto.ChunkUploadStatus;
import com.example.demo.model.dto.FileExistenceResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 分片上传服务
 */
@Service
@Slf4j
public class ChunkUploadService {

    @Autowired
    private ChunkUploadRedisService chunkUploadRedisService;

    @Autowired
    private RagUnitService ragUnitService;

    @Value("${file.upload.temp-dir:./temp/chunks}")
    private String tempDirBase;

    /**
     * 检查文件上传状态（秒传检查 + 断点续传）
     */
    public ChunkUploadStatus checkUploadStatus(String fileHash, String filename,
                                               Long fileSize, Integer totalChunks) {
        // 1. 检查文件是否已存在（秒传）
        FileExistenceResponse existenceCheck = ragUnitService.checkFileExists(fileHash);
        if (existenceCheck.getExists()) {
            log.info("文件已存在，支持秒传: fileHash={}", fileHash);
            return null; // 返回null表示文件已存在
        }

        // 2. 检查Redis中的上传状态（断点续传）
        ChunkUploadStatus status = chunkUploadRedisService.getUploadStatus(fileHash);

        if (status == null) {
            // 3. 初始化新的上传任务
            String tempDir = tempDirBase + "/" + fileHash;
            chunkUploadRedisService.initUploadStatus(fileHash, filename, fileSize, totalChunks, tempDir);
            status = chunkUploadRedisService.getUploadStatus(fileHash);
            log.info("初始化新的上传任务: fileHash={}, totalChunks={}", fileHash, totalChunks);
        } else {
            log.info("恢复断点上传: fileHash={}, progress={}%", fileHash, status.getProgress());
        }

        return status;
    }

    /**
     * 上传分片
     */
    public void uploadChunk(String fileHash, Integer chunkNumber, MultipartFile chunkFile) throws IOException {
        // 1. 检查是否已上传
        if (chunkUploadRedisService.isChunkUploaded(fileHash, chunkNumber)) {
            log.info("分片已存在，跳过: fileHash={}, chunk={}", fileHash, chunkNumber);
            return;
        }

        // 2. 获取上传状态
        ChunkUploadStatus status = chunkUploadRedisService.getUploadStatus(fileHash);
        if (status == null) {
            throw new RuntimeException("上传状态不存在: " + fileHash);
        }

        // 3. 保存分片到临时目录
        String tempDir = status.getTempDir();
        Path tempDirPath = Paths.get(tempDir);
        if (!Files.exists(tempDirPath)) {
            Files.createDirectories(tempDirPath);
        }

        String chunkFilename = String.format("chunk_%05d", chunkNumber);
        Path chunkPath = tempDirPath.resolve(chunkFilename);

        try (InputStream inputStream = chunkFile.getInputStream()) {
            Files.copy(inputStream, chunkPath, StandardCopyOption.REPLACE_EXISTING);
        }

        // 4. 标记分片已上传
        chunkUploadRedisService.markChunkUploaded(fileHash, chunkNumber);

        log.info("分片上传成功: fileHash={}, chunk={}, size={}KB",
                fileHash, chunkNumber, chunkFile.getSize() / 1024);
    }

    /**
     * 合并分片并触发文件处理
     */
    public String mergeChunks(String fileHash, String filename) throws Exception {
        // 1. 获取上传状态
        ChunkUploadStatus status = chunkUploadRedisService.getUploadStatus(fileHash);
        if (status == null) {
            throw new RuntimeException("上传状态不存在: " + fileHash);
        }

        // 2. 检查是否所有分片都已上传
        if (!status.isAllChunksUploaded()) {
            throw new RuntimeException(String.format(
                    "分片未完全上传: %d/%d", status.getUploadedChunks().size(), status.getTotalChunks()));
        }

        // 3. 合并分片
        String tempDir = status.getTempDir();
        Path tempDirPath = Paths.get(tempDir);
        Path mergedFile = tempDirPath.resolve("merged_" + filename);

        log.info("开始合并分片: fileHash={}, totalChunks={}", fileHash, status.getTotalChunks());

        try (FileOutputStream fos = new FileOutputStream(mergedFile.toFile())) {
            // 按顺序合并分片
            for (int i = 0; i < status.getTotalChunks(); i++) {
                String chunkFilename = String.format("chunk_%05d", i);
                Path chunkPath = tempDirPath.resolve(chunkFilename);

                if (!Files.exists(chunkPath)) {
                    throw new RuntimeException("分片文件不存在: " + chunkFilename);
                }

                try (FileInputStream fis = new FileInputStream(chunkPath.toFile())) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                    }
                }
            }
        }

        log.info("分片合并完成: fileHash={}, mergedFile={}", fileHash, mergedFile);

        // 4. 调用原有的文件处理流程（异步）
        // 使用自定义的LocalMultipartFile，避免依赖spring-test且减少内存占用
        File file = mergedFile.toFile();

        MultipartFile multipartFile = new com.example.demo.util.LocalMultipartFile(
                "file",
                filename,
                null, // contentType可以为null，后端会重新检测
                file
        );

        String sourceId = ragUnitService.processAndStoreAsync(multipartFile, fileHash).getSourceId();

        // 5. 清理临时文件和Redis状态
        cleanupTempFiles(tempDirPath);
        chunkUploadRedisService.deleteUploadStatus(fileHash);

        log.info("文件处理完成，临时文件已清理: fileHash={}, sourceId={}", fileHash, sourceId);

        return sourceId;
    }

    /**
     * 清理临时文件
     */
    private void cleanupTempFiles(Path tempDir) {
        try {
            if (Files.exists(tempDir)) {
                try (Stream<Path> paths = Files.walk(tempDir)) {
                    paths.sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.delete(path);
                                } catch (IOException e) {
                                    log.warn("删除临时文件失败: {}", path, e);
                                }
                            });
                }
            }
            log.info("临时文件清理完成: {}", tempDir);
        } catch (IOException e) {
            log.error("清理临时文件失败: {}", tempDir, e);
        }
    }
}

package com.example.demo.service;

import com.example.demo.model.dto.ChunkUploadStatus;
import com.example.demo.model.dto.FileExistenceResponse;
import com.example.demo.model.dto.UploadResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

@Service
@Slf4j
public class ChunkUploadService {

    @Autowired
    private ChunkUploadRedisService chunkUploadRedisService;

    @Autowired
    private RagUnitService ragUnitService;

    @Value("${file.upload.temp-dir:./temp/chunks}")
    private String tempDirBase;

    public ChunkUploadStatus checkUploadStatus(String userId, String fileHash, String filename,
                                               Long fileSize, Integer totalChunks) {
        FileExistenceResponse existenceCheck = ragUnitService.checkFileExists(userId, fileHash);
        if (existenceCheck.getExists()) {
            return null;
        }

        ChunkUploadStatus status = chunkUploadRedisService.getUploadStatus(userId, fileHash);
        if (status == null) {
            String tempDir = tempDirBase + "/" + userId + "/" + fileHash;
            chunkUploadRedisService.initUploadStatus(userId, fileHash, filename, fileSize, totalChunks, tempDir);
            status = chunkUploadRedisService.getUploadStatus(userId, fileHash);
        }
        return status;
    }

    public void uploadChunk(String userId, String fileHash, Integer chunkNumber, MultipartFile chunkFile) throws IOException {
        if (chunkUploadRedisService.isChunkUploaded(userId, fileHash, chunkNumber)) {
            return;
        }

        ChunkUploadStatus status = chunkUploadRedisService.getUploadStatus(userId, fileHash);
        if (status == null) {
            throw new RuntimeException("上传状态不存在: " + fileHash);
        }

        Path tempDirPath = Paths.get(status.getTempDir());
        if (!Files.exists(tempDirPath)) {
            Files.createDirectories(tempDirPath);
        }

        String chunkFilename = String.format("chunk_%05d", chunkNumber);
        Path chunkPath = tempDirPath.resolve(chunkFilename);

        try (InputStream inputStream = chunkFile.getInputStream()) {
            Files.copy(inputStream, chunkPath, StandardCopyOption.REPLACE_EXISTING);
        }

        chunkUploadRedisService.markChunkUploaded(userId, fileHash, chunkNumber);
    }

    public UploadResponse mergeChunks(String userId, String fileHash, String filename) throws Exception {
        ChunkUploadStatus status = chunkUploadRedisService.getUploadStatus(userId, fileHash);
        if (status == null) {
            throw new RuntimeException("上传状态不存在: " + fileHash);
        }

        if (!status.isAllChunksUploaded()) {
            throw new RuntimeException(String.format(
                    "分片未上传完成: %d/%d", status.getUploadedChunks().size(), status.getTotalChunks()));
        }

        Path tempDirPath = Paths.get(status.getTempDir());
        Path mergedFile = tempDirPath.resolve("merged_" + filename);

        try (FileOutputStream fos = new FileOutputStream(mergedFile.toFile())) {
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

        File file = mergedFile.toFile();
        MultipartFile multipartFile = new com.example.demo.util.LocalMultipartFile(
                "file",
                filename,
                null,
                file
        );

        UploadResponse response = ragUnitService.processAndStoreAsync(multipartFile, fileHash, userId);

        cleanupTempFiles(tempDirPath);
        chunkUploadRedisService.deleteUploadStatus(userId, fileHash);
        return response;
    }

    private void cleanupTempFiles(Path tempDir) {
        try {
            if (Files.exists(tempDir)) {
                try (Stream<Path> paths = Files.walk(tempDir)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            log.warn("删除临时文件失败: {}", path, e);
                        }
                    });
                }
            }
        } catch (IOException e) {
            log.error("清理临时文件失败: {}", tempDir, e);
        }
    }
}

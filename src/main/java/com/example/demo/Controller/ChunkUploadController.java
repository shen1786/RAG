package com.example.demo.Controller;

import com.example.demo.model.dto.ApiResponse;
import com.example.demo.model.dto.ChunkUploadResponse;
import com.example.demo.model.dto.ChunkUploadStatus;
import com.example.demo.model.dto.FileExistenceResponse;
import com.example.demo.service.ChunkUploadService;
import com.example.demo.service.RagUnitService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashSet;

/**
 * 分片上传控制器
 * 支持大文件分片上传、断点续传、秒传
 */
@RestController
@RequestMapping("/api/upload/chunk")
@Slf4j
@CrossOrigin(origins = "*")
public class ChunkUploadController {

    @Autowired
    private ChunkUploadService chunkUploadService;

    @Autowired
    private RagUnitService ragUnitService;

    /**
     * 检查上传状态（秒传 + 断点续传）
     *
     * @param fileHash 文件SHA-256哈希值
     * @param filename 文件名
     * @param fileSize 文件大小
     * @param totalChunks 分片总数
     * @return 上传状态
     */
    @GetMapping("/check")
    public ApiResponse<ChunkUploadResponse> checkUploadStatus(
            @RequestParam("fileHash") String fileHash,
            @RequestParam("filename") String filename,
            @RequestParam("fileSize") Long fileSize,
            @RequestParam("totalChunks") Integer totalChunks) {

        log.info("检查上传状态: fileHash={}, filename={}, size={}KB, chunks={}",
                fileHash, filename, fileSize / 1024, totalChunks);

        // 参数验证
        if (fileHash == null || !fileHash.matches("^[a-fA-F0-9]{64}$")) {
            return ApiResponse.validationError("无效的SHA-256哈希值");
        }

        try {
            // 1. 检查文件是否已存在（秒传）
            FileExistenceResponse existenceCheck = ragUnitService.checkFileExists(fileHash);
            if (existenceCheck.getExists()) {
                log.info("文件已存在，秒传: fileHash={}", fileHash);
                return ApiResponse.success("文件已存在，秒传成功",
                        ChunkUploadResponse.instantUpload(existenceCheck.getSourceId()));
            }

            // 2. 检查断点续传状态
            ChunkUploadStatus status = chunkUploadService.checkUploadStatus(
                    fileHash, filename, fileSize, totalChunks);

            ChunkUploadResponse response = ChunkUploadResponse.needUpload(
                    status.getUploadedChunks() != null ? status.getUploadedChunks() : new HashSet<>(),
                    status.getProgress()
            );

            return ApiResponse.success("可以继续上传", response);

        } catch (Exception e) {
            log.error("检查上传状态失败", e);
            return ApiResponse.serverError(e.getMessage());
        }
    }

    /**
     * 上传分片
     *
     * @param fileHash 文件SHA-256哈希值
     * @param chunkNumber 分片编号（从0开始）
     * @param chunk 分片文件
     * @return 上传结果
     */
    @PostMapping
    public ApiResponse<UploadChunkResult> uploadChunk(
            @RequestParam("fileHash") String fileHash,
            @RequestParam("chunkNumber") Integer chunkNumber,
            @RequestParam("chunk") MultipartFile chunk) {

        log.info("上传分片: fileHash={}, chunk={}, size={}KB",
                fileHash, chunkNumber, chunk.getSize() / 1024);

        // 参数验证
        if (fileHash == null || !fileHash.matches("^[a-fA-F0-9]{64}$")) {
            return ApiResponse.validationError("无效的SHA-256哈希值");
        }

        if (chunkNumber == null || chunkNumber < 0) {
            return ApiResponse.validationError("无效的分片编号");
        }

        if (chunk.isEmpty()) {
            return ApiResponse.validationError("分片文件不能为空");
        }

        try {
            chunkUploadService.uploadChunk(fileHash, chunkNumber, chunk);

            UploadChunkResult result = UploadChunkResult.builder()
                    .fileHash(fileHash)
                    .chunkNumber(chunkNumber)
                    .success(true)
                    .build();

            return ApiResponse.success("分片上传成功", result);

        } catch (Exception e) {
            log.error("分片上传失败: fileHash={}, chunk={}", fileHash, chunkNumber, e);
            return ApiResponse.serverError(e.getMessage());
        }
    }

    /**
     * 合并分片
     *
     * @param fileHash 文件SHA-256哈希值
     * @param filename 文件名
     * @return 合并结果
     */
    @PostMapping("/merge")
    public ApiResponse<MergeChunkResult> mergeChunks(
            @RequestParam("fileHash") String fileHash,
            @RequestParam("filename") String filename) {

        log.info("合并分片: fileHash={}, filename={}", fileHash, filename);

        // 参数验证
        if (fileHash == null || !fileHash.matches("^[a-fA-F0-9]{64}$")) {
            return ApiResponse.validationError("无效的SHA-256哈希值");
        }

        if (filename == null || filename.trim().isEmpty()) {
            return ApiResponse.validationError("文件名不能为空");
        }

        try {
            String sourceId = chunkUploadService.mergeChunks(fileHash, filename);

            MergeChunkResult result = MergeChunkResult.builder()
                    .fileHash(fileHash)
                    .filename(filename)
                    .sourceId(sourceId)
                    .success(true)
                    .message("文件合并成功，正在后台处理中...")
                    .build();

            return ApiResponse.success("合并成功", result);

        } catch (Exception e) {
            log.error("合并分片失败: fileHash={}", fileHash, e);
            return ApiResponse.serverError(e.getMessage());
        }
    }

    /**
     * 上传分片结果DTO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UploadChunkResult {
        private String fileHash;
        private Integer chunkNumber;
        private Boolean success;
    }

    /**
     * 合并分片结果DTO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MergeChunkResult {
        private String fileHash;
        private String filename;
        private String sourceId;
        private Boolean success;
        private String message;
    }
}

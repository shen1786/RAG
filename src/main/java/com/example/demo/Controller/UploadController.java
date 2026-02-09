package com.example.demo.Controller;

import com.example.demo.model.dto.ApiResponse;
import com.example.demo.model.dto.FileExistenceResponse;
import com.example.demo.model.dto.UploadResponse;
import com.example.demo.service.RagUnitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件上传控制器
 * 使用统一RESTful API响应格式
 * 支持SHA-256文件去重
 */
@RestController
@RequestMapping("/api/upload")
@Slf4j
@CrossOrigin(origins = "*")
public class UploadController {

    @Autowired
    private RagUnitService ragUnitService;

    /**
     * 检查文件是否已存在（通过SHA-256哈希值）
     *
     * @param fileHash 文件SHA-256哈希值（由前端计算）
     * @return 文件存在性信息
     */
    @GetMapping("/check")
    public ApiResponse<FileExistenceResponse> checkFileExists(
            @RequestParam("fileHash") String fileHash) {
        log.info("检查文件是否存在: fileHash={}", fileHash);

        try {
            FileExistenceResponse response = ragUnitService.checkFileExists(fileHash);

            if (response.getExists()) {
                return ApiResponse.success("文件已存在", response);
            } else {
                return ApiResponse.success("文件不存在，可以上传", response);
            }
        } catch (IllegalArgumentException e) {
            log.error("参数验证失败", e);
            return ApiResponse.validationError(e.getMessage());
        } catch (Exception e) {
            log.error("检查文件存在性失败", e);
            return ApiResponse.serverError(e.getMessage());
        }
    }

    /**
     * 单文件上传接口（异步处理 + SHA-256去重）
     * 文件立即上传到MinIO，后台队列处理
     *
     * @param file 上传的文件
     * @param fileHash 文件SHA-256哈希值（由前端计算，必填）
     * @return 统一响应格式
     */
    @PostMapping
    public ApiResponse<UploadResponse> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("fileHash") String fileHash) {
        log.info("收到文件上传请求: {} (大小: {}KB, hash: {})",
                file.getOriginalFilename(), file.getSize() / 1024, fileHash);

        // 参数验证
        if (file.isEmpty()) {
            return ApiResponse.validationError("文件不能为空");
        }

        if (fileHash == null || fileHash.trim().isEmpty()) {
            return ApiResponse.validationError("文件哈希值不能为空");
        }

        try {
            UploadResponse response = ragUnitService.processAndStoreAsync(file, fileHash);
            return ApiResponse.success(response.getMessage(), response);
        } catch (IllegalArgumentException e) {
            log.error("文件类型不支持或参数错误", e);
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("文件上传失败", e);
            return ApiResponse.serverError(e.getMessage());
        }
    }

    /**
     * 批量文件上传接口（异步处理 + SHA-256去重）
     * 所有文件立即上传到MinIO，后台队列并发处理
     *
     * @param files 上传的文件数组
     * @param fileHashes 文件SHA-256哈希值数组（由前端计算，必填）
     * @return 统一响应格式
     */
    @PostMapping("/batch")
    public ApiResponse<List<UploadResponse>> uploadFiles(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam("fileHashes") String[] fileHashes) {
        log.info("收到批量上传请求: {} 个文件", files.length);

        // 参数验证
        if (files == null || files.length == 0) {
            return ApiResponse.validationError("文件列表不能为空");
        }

        if (fileHashes == null || fileHashes.length == 0) {
            return ApiResponse.validationError("文件哈希值列表不能为空");
        }

        if (files.length != fileHashes.length) {
            return ApiResponse.validationError("文件数量与哈希值数量不匹配");
        }

        if (files.length > 20) {
            return ApiResponse.validationError("单次最多上传20个文件");
        }

        try {
            List<UploadResponse> responses = new ArrayList<>();
            int successCount = 0;
            int skipCount = 0;
            int failCount = 0;

            for (int i = 0; i < files.length; i++) {
                MultipartFile file = files[i];
                String fileHash = fileHashes[i];

                try {
                    if (!file.isEmpty()) {
                        UploadResponse response = ragUnitService.processAndStoreAsync(file, fileHash);
                        responses.add(response);

                        if (response.getMessage().contains("已存在")) {
                            skipCount++;
                        } else {
                            successCount++;
                        }
                    }
                } catch (Exception e) {
                    log.error("文件上传失败: {}", file.getOriginalFilename(), e);
                    UploadResponse errorResponse = UploadResponse.builder()
                            .success(false)
                            .filename(file.getOriginalFilename())
                            .message("上传失败: " + e.getMessage())
                            .build();
                    responses.add(errorResponse);
                    failCount++;
                }
            }

            String message = String.format("批量上传完成 (新上传: %d, 已存在: %d, 失败: %d)",
                    successCount, skipCount, failCount);
            return ApiResponse.success(message, responses);

        } catch (Exception e) {
            log.error("批量上传失败", e);
            return ApiResponse.serverError(e.getMessage());
        }
    }
}

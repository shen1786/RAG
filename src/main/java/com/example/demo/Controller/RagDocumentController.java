package com.example.demo.Controller;

import com.example.demo.model.dto.*;
import com.example.demo.service.DocumentDeleteService;
import com.example.demo.service.RagUnitService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * RAG文档管理控制器
 * 使用统一RESTful API响应格式
 */
@RestController
@RequestMapping("/api/documents")
@Slf4j
@CrossOrigin(origins = "*")
public class RagDocumentController {

    @Autowired
    private RagUnitService ragUnitService;

    @Autowired
    private DocumentDeleteService documentDeleteService;

    /**
     * 分页查询RAG资料库
     *
     * @param page 页码，默认1
     * @param pageSize 每页大小，默认10
     * @param sourceType 文件类型过滤，可选：TEXT/IMAGE/VIDEO
     * @param keyword 文件名关键词搜索
     * @param sortBy 排序字段，默认createdAt
     * @param sortOrder 排序方向，默认DESC
     * @return 统一响应格式包含分页数据
     */
    @GetMapping
    public ApiResponse<PageResponse<RagDocumentInfo>> getDocuments(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortOrder
    ) {
        log.info("查询文档列表 - page: {}, pageSize: {}, sourceType: {}, keyword: {}",
                page, pageSize, sourceType, keyword);

        // 参数验证
        if (page < 1) {
            return ApiResponse.validationError("页码必须大于0");
        }
        if (pageSize < 1 || pageSize > 100) {
            return ApiResponse.validationError("每页大小必须在1-100之间");
        }

        try {
            PageRequest request = new PageRequest();
            request.setPage(page);
            request.setPageSize(pageSize);
            request.setSourceType(sourceType);
            request.setKeyword(keyword);
            request.setSortBy(sortBy);
            request.setSortOrder(sortOrder);

            PageResponse<RagDocumentInfo> response = ragUnitService.getDocumentsPage(request);
            return ApiResponse.success("查询成功", response);

        } catch (Exception e) {
            log.error("查询文档列表失败", e);
            return ApiResponse.serverError(e.getMessage());
        }
    }

    /**
     * 删除指定文件（异步处理 + 重试机制）
     * 使用SHA-256哈希值精确定位文件
     *
     * @param fileHash 文件SHA-256哈希值
     * @return 任务ID和初始状态
     */
    @DeleteMapping("/{fileHash}")
    public ApiResponse<DeleteTaskResponse> deleteDocument(@PathVariable String fileHash) {
        log.info("收到删除请求: fileHash={}", fileHash);

        // 参数验证
        if (fileHash == null || fileHash.trim().isEmpty()) {
            return ApiResponse.validationError("文件哈希值不能为空");
        }

        if (!fileHash.matches("^[a-fA-F0-9]{64}$")) {
            return ApiResponse.validationError("无效的SHA-256哈希值格式");
        }

        try {
            // 异步删除，立即返回任务ID
            String taskId = ragUnitService.deleteDocumentAsync(fileHash);

            DeleteTaskResponse response = DeleteTaskResponse.builder()
                    .taskId(taskId)
                    .fileHash(fileHash)
                    .message("删除任务已提交，正在后台处理中...")
                    .build();

            return ApiResponse.success("删除任务已提交", response);

        } catch (RuntimeException e) {
            if (e.getMessage().contains("不存在")) {
                log.warn("文档不存在: fileHash={}", fileHash);
                return ApiResponse.notFound("文档");
            }
            log.error("提交删除任务失败", e);
            return ApiResponse.error(e.getMessage());

        } catch (Exception e) {
            log.error("提交删除任务失败", e);
            return ApiResponse.serverError(e.getMessage());
        }
    }

    /**
     * 删除指定文件（使用文件名，已废弃）
     * @deprecated 建议使用基于SHA-256哈希值的删除接口
     */
    @Deprecated
    @DeleteMapping("/by-filename/{filename}")
    public ApiResponse<DeleteTaskResponse> deleteDocumentByFilename(@PathVariable String filename) {
        log.info("收到删除请求（通过filename）: {}", filename);

        // 参数验证
        if (filename == null || filename.trim().isEmpty()) {
            return ApiResponse.validationError("文件名不能为空");
        }

        try {
            // 异步删除，立即返回任务ID
            String taskId = ragUnitService.deleteDocumentAsyncByFilename(filename);

            DeleteTaskResponse response = DeleteTaskResponse.builder()
                    .taskId(taskId)
                    .filename(filename)
                    .message("删除任务已提交，正在后台处理中...")
                    .build();

            return ApiResponse.success("删除任务已提交", response);

        } catch (RuntimeException e) {
            if (e.getMessage().contains("不存在")) {
                log.warn("文档不存在: {}", filename);
                return ApiResponse.notFound("文档 '" + filename + "'");
            }
            log.error("提交删除任务失败", e);
            return ApiResponse.error(e.getMessage());

        } catch (Exception e) {
            log.error("提交删除任务失败", e);
            return ApiResponse.serverError(e.getMessage());
        }
    }

    /**
     * 查询删除任务状态
     *
     * @param taskId 任务ID
     * @return 删除状态详情
     */
    @GetMapping("/delete-status/{taskId}")
    public ApiResponse<DeleteTaskStatus> getDeleteStatus(@PathVariable String taskId) {
        log.info("查询删除状态: taskId={}", taskId);

        // 参数验证
        if (taskId == null || taskId.trim().isEmpty()) {
            return ApiResponse.validationError("任务ID不能为空");
        }

        try {
            DeleteTaskStatus status = documentDeleteService.getDeleteStatus(taskId);
            return ApiResponse.success("查询成功", status);

        } catch (RuntimeException e) {
            if (e.getMessage().contains("不存在") || e.getMessage().contains("过期")) {
                log.warn("任务不存在或已过期: {}", taskId);
                return ApiResponse.notFound("删除任务 '" + taskId + "'");
            }
            log.error("查询删除状态失败", e);
            return ApiResponse.error(e.getMessage());

        } catch (Exception e) {
            log.error("查询删除状态失败", e);
            return ApiResponse.serverError(e.getMessage());
        }
    }

    /**
     * 删除响应DTO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeleteTaskResponse {
        private String taskId;
        private String filename;
        private String fileHash;
        private String message;
    }
}

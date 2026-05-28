package com.example.demo.Controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.example.demo.model.dto.ApiResponse;
import com.example.demo.model.dto.DeleteTaskStatus;
import com.example.demo.model.dto.DeleteTaskResponse;
import com.example.demo.model.dto.DocumentFileStatusResponse;
import com.example.demo.model.dto.PageResponse;
import com.example.demo.model.dto.RagDocumentInfo;
import com.example.demo.service.AuthContextService;
import com.example.demo.service.RagDocumentApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG 文档管理控制器
 * 提供文档列表、状态查询和删除相关接口。
 */
@RestController
@RequestMapping("/api/documents")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class RagDocumentController {

    private final RagDocumentApplicationService ragDocumentApplicationService;
    private final AuthContextService authContextService;

    /**
     * 分页查询文档列表。
     *
     * @param page 页码
     * @param pageSize 每页条数
     * @param userId 用户 ID
     * @param sourceType 文件类型
     * @param keyword 关键词
     * @param sortBy 排序字段
     * @param sortOrder 排序方向
     */
    @SaCheckPermission("document:list")
    @GetMapping
    public ApiResponse<PageResponse<RagDocumentInfo>> getDocuments(@RequestParam(defaultValue = "1") Integer page,
                                                                   @RequestParam(defaultValue = "10") Integer pageSize,
                                                                   @RequestParam String userId,
                                                                   @RequestParam(required = false) String sourceType,
                                                                   @RequestParam(required = false) String keyword,
                                                                   @RequestParam(defaultValue = "createdAt") String sortBy,
                                                                   @RequestParam(defaultValue = "DESC") String sortOrder) {
        return ragDocumentApplicationService.getDocuments(page, pageSize, authContextService.resolveUserId(userId), sourceType, keyword, sortBy, sortOrder);
    }

    /**
     * 查询文档状态。
     *
     * @param fileHash 文件 SHA-256 哈希值
     * @param userId 用户 ID
     */
    @SaCheckPermission("document:status")
    @GetMapping("/status/{fileHash}")
    public ApiResponse<DocumentFileStatusResponse> getDocumentStatus(@PathVariable String fileHash,
                                                                      @RequestParam String userId) {
        return ragDocumentApplicationService.getDocumentStatus(fileHash, authContextService.resolveUserId(userId));
    }

    /**
     * 按文件哈希删除文档。
     *
     * @param fileHash 文件 SHA-256 哈希值
     * @param userId 用户 ID
     */
    @SaCheckPermission("document:delete")
    @DeleteMapping("/{fileHash}")
    public ApiResponse<DeleteTaskResponse> deleteDocument(@PathVariable String fileHash,
                                                          @RequestParam String userId) {
        return ragDocumentApplicationService.deleteDocument(fileHash, authContextService.resolveUserId(userId));
    }

    /**
     * 按文件名删除文档。
     * <p>
     * 该接口已废弃，建议使用基于文件哈希的删除方式。
     *
     * @param filename 文件名
     */
    @SaCheckPermission("document:delete")
    @Deprecated
    @DeleteMapping("/by-filename/{filename}")
    public ApiResponse<DeleteTaskResponse> deleteDocumentByFilename(@PathVariable String filename) {
        return ragDocumentApplicationService.deleteDocumentByFilename(authContextService.getCurrentUserId(), filename);
    }

    /**
     * 查询删除任务状态。
     *
     * @param taskId 任务 ID
     */
    @SaCheckPermission("document:delete-status")
    @GetMapping("/delete-status/{taskId}")
    public ApiResponse<DeleteTaskStatus> getDeleteStatus(@PathVariable String taskId) {
        return ragDocumentApplicationService.getDeleteStatus(taskId);
    }
}

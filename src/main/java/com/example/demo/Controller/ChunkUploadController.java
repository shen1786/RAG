package com.example.demo.Controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.example.demo.model.dto.ApiResponse;
import com.example.demo.model.dto.ChunkUploadResponse;
import com.example.demo.model.dto.MergeChunkResult;
import com.example.demo.model.dto.UploadChunkResult;
import com.example.demo.model.dto.UploadResponse;
import com.example.demo.service.AuthContextService;
import com.example.demo.service.ChunkUploadApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 分片上传控制器
 * 支持秒传校验、分片上传和分片合并。
 */
@RestController
@RequestMapping("/api/upload/chunk")
@RequiredArgsConstructor
public class ChunkUploadController {

    private final ChunkUploadApplicationService chunkUploadApplicationService;
    private final AuthContextService authContextService;

    /**
     * 检查分片上传状态。
     *
     * @param fileHash 文件 SHA-256 哈希值
     * @param userId 用户 ID
     * @param filename 文件名
     * @param fileSize 文件大小
     * @param totalChunks 总分片数
     */
    @SaCheckPermission("upload:chunk:check")
    @GetMapping("/check")
    public ApiResponse<ChunkUploadResponse> checkUploadStatus(@RequestParam("fileHash") String fileHash,
                                                              @RequestParam("userId") String userId,
                                                              @RequestParam("filename") String filename,
                                                              @RequestParam("fileSize") Long fileSize,
                                                              @RequestParam("totalChunks") Integer totalChunks) {
        return chunkUploadApplicationService.checkUploadStatus(fileHash, authContextService.resolveUserId(userId), filename, fileSize, totalChunks);
    }

    /**
     * 上传单个分片。
     *
     * @param fileHash 文件 SHA-256 哈希值
     * @param userId 用户 ID
     * @param chunkNumber 分片编号
     * @param chunk 分片文件
     */
    @SaCheckPermission("upload:chunk:file")
    @PostMapping
    public ApiResponse<UploadChunkResult> uploadChunk(@RequestParam("fileHash") String fileHash,
                                                      @RequestParam("userId") String userId,
                                                      @RequestParam("chunkNumber") Integer chunkNumber,
                                                      @RequestParam("chunk") MultipartFile chunk) {
        return chunkUploadApplicationService.uploadChunk(fileHash, authContextService.resolveUserId(userId), chunkNumber, chunk);
    }

    /**
     * 合并所有已上传分片。
     *
     * @param fileHash 文件 SHA-256 哈希值
     * @param userId 用户 ID
     * @param filename 文件名
     */
    @SaCheckPermission("upload:chunk:merge")
    @PostMapping("/merge")
    public ApiResponse<MergeChunkResult> mergeChunks(@RequestParam("fileHash") String fileHash,
                                                     @RequestParam("userId") String userId,
                                                     @RequestParam("filename") String filename) {
        return chunkUploadApplicationService.mergeChunks(fileHash, authContextService.resolveUserId(userId), filename);
    }
}

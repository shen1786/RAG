package com.example.demo.Controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.example.demo.model.dto.ApiResponse;
import com.example.demo.model.dto.FileExistenceResponse;
import com.example.demo.model.dto.UploadResponse;
import com.example.demo.service.AuthContextService;
import com.example.demo.service.UploadApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文件上传控制器
 * 负责单文件、批量文件上传及文件存在性检查。
 */
@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
public class UploadController {

    private final UploadApplicationService uploadApplicationService;
    private final AuthContextService authContextService;

    /**
     * 检查文件是否已存在。
     *
     * @param fileHash 文件 SHA-256 哈希值
     * @param userId 用户 ID
     */
    @SaCheckPermission("upload:check")
    @GetMapping("/check")
    public ApiResponse<FileExistenceResponse> checkFileExists(@RequestParam("fileHash") String fileHash,
                                                              @RequestParam("userId") String userId) {
        return uploadApplicationService.checkFileExists(fileHash, authContextService.resolveUserId(userId));
    }

    /**
     * 单文件上传。
     *
     * @param file 上传文件
     * @param fileHash 文件 SHA-256 哈希值
     * @param userId 用户 ID
     */
    @SaCheckPermission("upload:file")
    @PostMapping
    public ApiResponse<UploadResponse> uploadFile(@RequestParam("file") MultipartFile file,
                                                  @RequestParam("fileHash") String fileHash,
                                                  @RequestParam("userId") String userId) {
        return uploadApplicationService.uploadFile(file, fileHash, authContextService.resolveUserId(userId));
    }

    /**
     * 批量文件上传。
     *
     * @param files 文件列表
     * @param fileHashes 文件哈希列表
     * @param userId 用户 ID
     */
    @SaCheckPermission("upload:batch")
    @PostMapping("/batch")
    public ApiResponse<List<UploadResponse>> uploadFiles(@RequestParam("files") MultipartFile[] files,
                                                         @RequestParam("fileHashes") String[] fileHashes,
                                                         @RequestParam("userId") String userId) {
        return uploadApplicationService.uploadFiles(files, fileHashes, authContextService.resolveUserId(userId));
    }
}

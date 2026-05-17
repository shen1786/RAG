package com.example.demo.service;

import com.example.demo.model.DocumentFileStatus;
import com.example.demo.model.dto.ApiResponse;
import com.example.demo.model.dto.UploadResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UploadApplicationServiceTest {

    @Mock
    private RagUnitService ragUnitService;

    @Mock
    private MultipartFile successFile;

    @Mock
    private MultipartFile skippedFile;

    @Mock
    private MultipartFile failedFile;

    @Test
    void shouldAggregateBatchUploadCounts() throws Exception {
        UploadApplicationService service = new UploadApplicationService(ragUnitService);
        UploadResponse successResponse = UploadResponse.builder()
                .success(true)
                .message("ok")
                .status(DocumentFileStatus.UPLOAD_SUCCESS)
                .build();
        UploadResponse skippedResponse = UploadResponse.builder()
                .success(true)
                .message("skip")
                .status(DocumentFileStatus.SUCCESS)
                .build();

        when(successFile.isEmpty()).thenReturn(false);
        when(skippedFile.isEmpty()).thenReturn(false);
        when(failedFile.isEmpty()).thenReturn(false);
        when(failedFile.getOriginalFilename()).thenReturn("failed.txt");
        when(ragUnitService.processAndStoreAsync(successFile, "hash-1", "u1")).thenReturn(successResponse);
        when(ragUnitService.processAndStoreAsync(skippedFile, "hash-2", "u1")).thenReturn(skippedResponse);
        when(ragUnitService.processAndStoreAsync(failedFile, "hash-3", "u1"))
                .thenThrow(new RuntimeException("磁盘不足"));

        ApiResponse<List<UploadResponse>> response = service.uploadFiles(
                new MultipartFile[]{successFile, skippedFile, failedFile},
                new String[]{"hash-1", "hash-2", "hash-3"},
                "u1"
        );

        assertEquals(200, response.getCode());
        assertEquals("批量上传完成 (新上传: 1, 已存在: 1, 失败: 1)", response.getMessage());
        assertEquals(3, response.getData().size());
        assertFalse(response.getData().get(2).isSuccess());
        assertEquals("上传失败: 磁盘不足", response.getData().get(2).getMessage());
    }

    @Test
    void shouldRejectBatchUploadWhenCountsMismatch() {
        UploadApplicationService service = new UploadApplicationService(ragUnitService);

        ApiResponse<List<UploadResponse>> response = service.uploadFiles(
                new MultipartFile[]{successFile},
                new String[]{"hash-1", "hash-2"},
                "u1"
        );

        assertEquals(400, response.getCode());
        assertTrue(response.getMessage().contains("文件数量与哈希数量不匹配"));
    }
}

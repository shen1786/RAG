package com.example.demo.service;

import com.example.demo.model.DocumentFileStatus;
import com.example.demo.model.dto.ApiResponse;
import com.example.demo.model.dto.ChunkUploadResponse;
import com.example.demo.model.dto.ChunkUploadStatus;
import com.example.demo.model.dto.FileExistenceResponse;
import com.example.demo.model.dto.MergeChunkResult;
import com.example.demo.model.dto.UploadResponse;
import com.example.demo.util.FileNameSanitizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChunkUploadApplicationServiceTest {

    @Mock
    private ChunkUploadService chunkUploadService;

    @Mock
    private RagUnitService ragUnitService;

    @Test
    void shouldReturnInstantUploadWhenFileAlreadyExists() {
        ChunkUploadApplicationService service = new ChunkUploadApplicationService(chunkUploadService, ragUnitService);
        String fileHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        when(ragUnitService.checkFileExists("u1", fileHash)).thenReturn(FileExistenceResponse.exists(
                "source-1",
                "demo.txt",
                1,
                "minio/demo.txt",
                "http://example.com/demo.txt",
                null
        ));

        ApiResponse<ChunkUploadResponse> response = service.checkUploadStatus(fileHash, "u1", "demo.txt", 100L, 1);

        assertEquals(200, response.getCode());
        assertEquals("文件已存在，秒传成功", response.getMessage());
        assertEquals(DocumentFileStatus.SUCCESS, response.getData().getStatus());
        assertEquals("source-1", response.getData().getSourceId());
    }

    @Test
    void shouldRejectInvalidChunkNumber() {
        ChunkUploadApplicationService service = new ChunkUploadApplicationService(chunkUploadService, ragUnitService);
        String fileHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

        ApiResponse<?> response = service.uploadChunk(fileHash, "u1", -1, null);

        assertEquals(400, response.getCode());
        assertEquals("参数验证失败: 无效的分片编号", response.getMessage());
    }

    // --- mergeChunks 路径穿越测试 ---

    @Test
    void mergeChunks_traversalFilename_sanitizePassesSafeNameToService() throws Exception {
        ChunkUploadApplicationService service = new ChunkUploadApplicationService(chunkUploadService, ragUnitService);
        String fileHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        String traversalFilename = "../../etc/passwd";

        UploadResponse uploadResponse = UploadResponse.builder()
                .success(true)
                .message("ok")
                .status(DocumentFileStatus.SUCCESS)
                .build();
        when(chunkUploadService.mergeChunks(eq("u1"), eq(fileHash), any(String.class)))
                .thenReturn(uploadResponse);

        ApiResponse<MergeChunkResult> response = service.mergeChunks(fileHash, "u1", traversalFilename);

        // 验证传给 service 的文件名已被净化：不含 / 和 ..
        String passedFilename = response.getData().getFilename();
        assertFalse(passedFilename.contains("/"), "filename should not contain slash");
        assertFalse(passedFilename.contains(".."), "filename should not contain double dots");
        verify(chunkUploadService).mergeChunks(eq("u1"), eq(fileHash), eq(passedFilename));
    }

    @Test
    void mergeChunks_normalFilename_passesUnchanged() throws Exception {
        ChunkUploadApplicationService service = new ChunkUploadApplicationService(chunkUploadService, ragUnitService);
        String fileHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

        UploadResponse uploadResponse = UploadResponse.builder()
                .success(true)
                .message("ok")
                .status(DocumentFileStatus.SUCCESS)
                .build();
        when(chunkUploadService.mergeChunks(eq("u1"), eq(fileHash), eq("report.pdf")))
                .thenReturn(uploadResponse);

        ApiResponse<MergeChunkResult> response = service.mergeChunks(fileHash, "u1", "report.pdf");

        assertEquals("report.pdf", response.getData().getFilename());
        verify(chunkUploadService).mergeChunks("u1", fileHash, "report.pdf");
    }

    @Test
    void mergeChunks_traversalFilename_serviceThrowsIllegalArgumentException() throws Exception {
        ChunkUploadApplicationService service = new ChunkUploadApplicationService(chunkUploadService, ragUnitService);
        String fileHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

        when(chunkUploadService.mergeChunks(eq("u1"), eq(fileHash), any(String.class)))
                .thenThrow(new IllegalArgumentException("文件名不合法"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.mergeChunks(fileHash, "u1", "../../etc/passwd"));
        assertTrue(ex.getCause() instanceof IllegalArgumentException);
    }
}

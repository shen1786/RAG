package com.example.demo.service;

import com.example.demo.model.DocumentFileStatus;
import com.example.demo.model.dto.ApiResponse;
import com.example.demo.model.dto.ChunkUploadResponse;
import com.example.demo.model.dto.FileExistenceResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}

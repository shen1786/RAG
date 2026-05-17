package com.example.demo.service;

import com.example.demo.model.dto.ApiResponse;
import com.example.demo.model.dto.DeleteTaskResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagDocumentApplicationServiceTest {

    @Mock
    private RagUnitService ragUnitService;

    @Mock
    private DocumentDeleteService documentDeleteService;

    @Test
    void shouldReturnDeleteTaskResponseForExistingDocument() {
        RagDocumentApplicationService service = new RagDocumentApplicationService(ragUnitService, documentDeleteService);
        String fileHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        when(ragUnitService.deleteDocumentAsync("u1", fileHash)).thenReturn("task-1");

        ApiResponse<DeleteTaskResponse> response = service.deleteDocument(fileHash, "u1");

        assertEquals(200, response.getCode());
        assertEquals("task-1", response.getData().getTaskId());
        assertEquals(fileHash, response.getData().getFileHash());
    }

    @Test
    void shouldRejectInvalidFileHashWhenQueryingStatus() {
        RagDocumentApplicationService service = new RagDocumentApplicationService(ragUnitService, documentDeleteService);

        ApiResponse<?> response = service.getDocumentStatus("bad-hash", "u1");

        assertEquals(400, response.getCode());
        assertEquals("参数验证失败: 无效的 SHA-256 哈希值", response.getMessage());
    }
}

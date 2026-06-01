package com.example.demo.model.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ChunkUploadStatusJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldIgnoreLegacyDerivedFieldsWhenDeserializing() throws Exception {
        String json = """
                {
                  "fileHash": "hash-1",
                  "filename": "report.pdf",
                  "fileSize": 1024,
                  "totalChunks": 4,
                  "uploadedChunks": [0, 1],
                  "status": "UPLOADING",
                  "tempDir": "/tmp/chunks",
                  "createTime": 1,
                  "updateTime": 2,
                  "allChunksUploaded": false,
                  "progress": 50.0
                }
                """;

        ChunkUploadStatus status = objectMapper.readValue(json, ChunkUploadStatus.class);

        assertNotNull(status);
        assertEquals("hash-1", status.getFileHash());
        assertEquals(Set.of(0, 1), status.getUploadedChunks());
    }

    @Test
    void shouldNotSerializeDerivedFieldsIntoRedisPayload() throws Exception {
        ChunkUploadStatus status = ChunkUploadStatus.builder()
                .fileHash("hash-1")
                .filename("report.pdf")
                .fileSize(1024L)
                .totalChunks(4)
                .uploadedChunks(Set.of(0, 1))
                .status(ChunkUploadStatus.UploadStatus.UPLOADING)
                .tempDir("/tmp/chunks")
                .createTime(1L)
                .updateTime(2L)
                .build();

        String json = objectMapper.writeValueAsString(status);

        assertFalse(json.contains("allChunksUploaded"));
        assertFalse(json.contains("progress"));
    }
}

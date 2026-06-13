package com.example.demo.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChunkOrphanCleanupServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @InjectMocks
    private ChunkOrphanCleanupService chunkOrphanCleanupService;

    @TempDir
    Path tempDir;

    @Test
    void cleanupOrphanedChunks_noDirectories_isNoop() {
        ReflectionTestUtils.setField(chunkOrphanCleanupService, "tempDirBase", tempDir.toString());
        ReflectionTestUtils.setField(chunkOrphanCleanupService, "orphanThresholdHours", 24);

        // Should not throw, no Redis interaction needed
        chunkOrphanCleanupService.cleanupOrphanedChunks();
    }

    @Test
    void cleanupOrphanedChunks_deletesOrphanDirectoryWithoutRedisKey() throws IOException {
        ReflectionTestUtils.setField(chunkOrphanCleanupService, "tempDirBase", tempDir.toString());
        ReflectionTestUtils.setField(chunkOrphanCleanupService, "orphanThresholdHours", 1);

        // Create directory structure: tempDir/user123/filehash456/chunk.part
        Path userDir = Files.createDirectory(tempDir.resolve("user123"));
        Path hashDir = Files.createDirectory(userDir.resolve("filehash456"));
        Path chunkFile = hashDir.resolve("chunk.part");
        Files.writeString(chunkFile, "test data");

        // Set last modified time to 2 hours ago (past the 1-hour threshold)
        Instant twoHoursAgo = Instant.now().minus(2, ChronoUnit.HOURS);
        Files.setLastModifiedTime(hashDir, FileTime.from(twoHoursAgo));
        Files.setLastModifiedTime(chunkFile, FileTime.from(twoHoursAgo));

        when(redisTemplate.hasKey(anyString())).thenReturn(false);

        chunkOrphanCleanupService.cleanupOrphanedChunks();

        assertFalse(Files.exists(hashDir), "Orphan hash directory should be deleted");
        assertFalse(Files.exists(userDir), "Empty user directory should also be deleted");
    }

    @Test
    void cleanupOrphanedChunks_keepsDirectoryWithRedisKey() throws IOException {
        ReflectionTestUtils.setField(chunkOrphanCleanupService, "tempDirBase", tempDir.toString());
        ReflectionTestUtils.setField(chunkOrphanCleanupService, "orphanThresholdHours", 1);

        Path userDir = Files.createDirectory(tempDir.resolve("user123"));
        Path hashDir = Files.createDirectory(userDir.resolve("filehash456"));
        Files.writeString(hashDir.resolve("chunk.part"), "test data");

        Instant twoHoursAgo = Instant.now().minus(2, ChronoUnit.HOURS);
        Files.setLastModifiedTime(hashDir, FileTime.from(twoHoursAgo));

        when(redisTemplate.hasKey("chunk:upload:user123:filehash456")).thenReturn(true);

        chunkOrphanCleanupService.cleanupOrphanedChunks();

        assertTrue(Files.exists(hashDir), "Directory with active Redis key should not be deleted");
    }

    @Test
    void cleanupOrphanedChunks_keepsDirectoryWithinThreshold() throws IOException {
        ReflectionTestUtils.setField(chunkOrphanCleanupService, "tempDirBase", tempDir.toString());
        ReflectionTestUtils.setField(chunkOrphanCleanupService, "orphanThresholdHours", 24);

        Path userDir = Files.createDirectory(tempDir.resolve("user123"));
        Path hashDir = Files.createDirectory(userDir.resolve("filehash456"));
        Files.writeString(hashDir.resolve("chunk.part"), "recent data");

        // File modified just now, within the 24-hour threshold
        chunkOrphanCleanupService.cleanupOrphanedChunks();

        assertTrue(Files.exists(hashDir), "Directory within threshold should not be deleted");
    }
}

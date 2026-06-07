package com.example.demo.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class HashUtilsTest {

    /**
     * SHA-256("hello") 的已知正确值。
     */
    private static final String HELLO_SHA256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

    @TempDir
    Path tempDir;

    @Test
    void hashStringShouldReturnCorrectSha256() {
        String result = HashUtils.hashString("hello");
        assertEquals(HELLO_SHA256, result);
    }

    @Test
    void hashStringShouldHandleEmptyInput() {
        // SHA-256("") 的已知正确值
        String expected = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        String result = HashUtils.hashString("");
        assertEquals(expected, result);
    }

    @Test
    void hashInputStreamShouldReturnCorrectSha256() throws IOException {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        try (InputStream is = new ByteArrayInputStream(data)) {
            String result = HashUtils.hashInputStream(is);
            assertEquals(HELLO_SHA256, result);
        }
    }

    @Test
    void hashInputStreamShouldHandleEmptyStream() throws IOException {
        String expected = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        try (InputStream is = new ByteArrayInputStream(new byte[0])) {
            String result = HashUtils.hashInputStream(is);
            assertEquals(expected, result);
        }
    }

    @Test
    void hashFileShouldReturnCorrectSha256() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello");

        String result = HashUtils.hashFile(file);
        assertEquals(HELLO_SHA256, result);
    }

    @Test
    void hashFileShouldHandleLargeFile() throws IOException {
        // 生成一个超过 8KB buffer 的大文件（100KB）
        Path file = tempDir.resolve("large.bin");
        byte[] content = new byte[100 * 1024];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i % 256);
        }
        Files.write(file, content);

        // 先用 hashInputStream 计算基准值
        String expected;
        try (InputStream is = Files.newInputStream(file)) {
            expected = HashUtils.hashInputStream(is);
        }

        // 用 hashFile 验证结果一致
        String result = HashUtils.hashFile(file);
        assertEquals(expected, result);
        // 确认是 64 字符的 hex 字符串
        assertNotNull(result);
        assertEquals(64, result.length());
    }

    @Test
    void wrapWithDigestShouldComputeHashDuringRead() throws IOException {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        try (InputStream is = new ByteArrayInputStream(data);
             DigestInputStream dis = HashUtils.wrapWithDigest(is)) {
            // 读完所有数据
            byte[] buf = new byte[1024];
            while (dis.read(buf) != -1) {
                // consume
            }
            String result = HashUtils.extractHex(dis);
            assertEquals(HELLO_SHA256, result);
        }
    }

    @Test
    void differentContentProducesDifferentHash() {
        String hash1 = HashUtils.hashString("hello");
        String hash2 = HashUtils.hashString("world");
        assertNotEquals(hash1, hash2, "不同内容应产生不同 hash");
    }

    @Test
    void sameContentAlwaysProducesSameHash() {
        // 验证 hash 确定性：多次计算相同内容结果一致
        String hash1 = HashUtils.hashString("test-file-content");
        String hash2 = HashUtils.hashString("test-file-content");
        String hash3 = HashUtils.hashString("test-file-content");
        assertEquals(hash1, hash2);
        assertEquals(hash2, hash3);
    }

    @Test
    void hashFileDetectsContentMismatch() throws IOException {
        // 模拟场景：客户端声称 hash 对应 "file-a"，但实际文件内容是 "file-b"
        Path fileA = tempDir.resolve("a.txt");
        Path fileB = tempDir.resolve("b.txt");
        Files.writeString(fileA, "file-a-content");
        Files.writeString(fileB, "file-b-content");

        String serverHash = HashUtils.hashFile(fileA);
        String forgedClientHash = HashUtils.hashFile(fileB);

        // 服务端 hash 与伪造的客户端 hash 不一致 → 检测到篡改
        assertNotEquals(forgedClientHash, serverHash, "应能检测到客户端伪造 hash");
    }

    @Test
    void hashStringOutputShouldBe64HexChars() {
        String result = HashUtils.hashString("test");
        assertEquals(64, result.length());
        // 确认只包含十六进制字符
        assertNotNull(result);
        for (char c : result.toCharArray()) {
            boolean isHex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            assert isHex : "Non-hex character found: " + c;
        }
    }
}

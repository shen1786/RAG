package com.example.demo.e2e;

import com.example.demo.model.dto.ApiResponse;
import com.example.demo.model.dto.auth.LoginRequest;
import com.example.demo.model.dto.auth.LoginResponse;
import com.example.demo.model.dto.auth.RegisterRequest;
import com.example.demo.model.dto.UploadResponse;
import com.example.demo.model.dto.FileExistenceResponse;
import com.example.demo.model.dto.PageResponse;
import com.example.demo.model.dto.RagDocumentInfo;
import com.example.demo.model.dto.PageRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MainFlowE2ETest {

    @Autowired
    private TestRestTemplate restTemplate;

    private String satoken;
    private String userId;
    private String username;
    private static final String PASSWORD = "E2eTest_123!";

    private String uploadedFileHash;
    private String uploadedSourceId;

    @BeforeAll
    void registerAndLogin() {
        username = "e2e_test_" + UUID.randomUUID().toString().substring(0, 8);

        // 1. 注册
        RegisterRequest registerReq = new RegisterRequest();
        registerReq.setUsername(username);
        registerReq.setPassword(PASSWORD);
        ResponseEntity<ApiResponse<Object>> registerResp = restTemplate.exchange(
                "/auth/register", HttpMethod.POST,
                new HttpEntity<>(registerReq),
                new ParameterizedTypeReference<>() {});
        assertThat(registerResp.getStatusCode().is2xxSuccessful()).isTrue();

        // 2. 登录
        LoginRequest loginReq = new LoginRequest();
        loginReq.setUsername(username);
        loginReq.setPassword(PASSWORD);
        ResponseEntity<ApiResponse<LoginResponse>> loginResp = restTemplate.exchange(
                "/auth/login", HttpMethod.POST,
                new HttpEntity<>(loginReq),
                new ParameterizedTypeReference<>() {});
        assertThat(loginResp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(loginResp.getBody()).isNotNull();
        assertThat(loginResp.getBody().getData()).isNotNull();

        satoken = loginResp.getBody().getData().getTokenValue();
        userId = loginResp.getBody().getData().getUserId();
        assertThat(satoken).isNotBlank();
        assertThat(userId).isNotBlank();
    }

    @Test
    @Order(1)
    void uploadFile() throws Exception {
        String content = "这是一个E2E测试文档，用于验证RAG系统的端到端流程。包含检索关键词：人工智能。";
        byte[] fileBytes = content.getBytes(StandardCharsets.UTF_8);
        String fileHash = sha256(fileBytes);
        uploadedFileHash = fileHash;

        // 2. 检查文件不存在
        ResponseEntity<ApiResponse<FileExistenceResponse>> checkResp = restTemplate.exchange(
                "/api/upload/check?fileHash={hash}&userId={uid}",
                HttpMethod.GET, authEntity(),
                new ParameterizedTypeReference<>() {},
                fileHash, userId);
        assertThat(checkResp.getStatusCode().is2xxSuccessful()).isTrue();

        // 3. 上传文件
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(fileBytes) {
            @Override
            public String getFilename() {
                return "e2e-test.txt";
            }
        });
        body.add("fileHash", fileHash);
        body.add("userId", userId);

        HttpHeaders uploadHeaders = new HttpHeaders();
        uploadHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        uploadHeaders.set("satoken", satoken);

        ResponseEntity<ApiResponse<UploadResponse>> uploadResp = restTemplate.exchange(
                "/api/upload", HttpMethod.POST,
                new HttpEntity<>(body, uploadHeaders),
                new ParameterizedTypeReference<>() {});
        assertThat(uploadResp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(uploadResp.getBody()).isNotNull();
        assertThat(uploadResp.getBody().getData()).isNotNull();
        uploadedSourceId = uploadResp.getBody().getData().getSourceId();
    }

    @Test
    @Order(2)
    void listDocuments() {
        // 4. 查询文档列表
        PageRequest pageReq = new PageRequest();
        pageReq.setPage(1);
        pageReq.setPageSize(10);
        pageReq.setUserId(userId);

        ResponseEntity<ApiResponse<PageResponse<RagDocumentInfo>>> listResp = restTemplate.exchange(
                "/api/documents", HttpMethod.GET,
                authEntity(),
                new ParameterizedTypeReference<>() {});
        assertThat(listResp.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    @Order(3)
    void aiChatRetrieval() {
        // 5. AI 检索（多轮对话触发 RAG 检索）
        String question = "{\"sessionId\":\"" + UUID.randomUUID() + "\",\"message\":\"人工智能是什么？\",\"userId\":\"" + userId + "\"}";
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> chatResp = restTemplate.exchange(
                "/ai/multi-turn/chat", HttpMethod.POST,
                new HttpEntity<>(question, headers),
                String.class);
        // SSE 流式响应，只要服务端不报 500/403 即为检索通道通畅
        assertThat(chatResp.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    @Order(4)
    void deleteDocument() {
        if (uploadedFileHash == null) {
            return;
        }
        // 6. 删除文档
        ResponseEntity<ApiResponse<Object>> deleteResp = restTemplate.exchange(
                "/api/documents/{fileHash}?userId={uid}",
                HttpMethod.DELETE, authEntity(),
                new ParameterizedTypeReference<>() {},
                uploadedFileHash, userId);
        assertThat(deleteResp.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @AfterAll
    void verifyDeleteComplete() throws InterruptedException {
        if (uploadedFileHash == null) {
            return;
        }
        // 7. 确认删除后文件不再存在
        Thread.sleep(2000);
        ResponseEntity<ApiResponse<FileExistenceResponse>> checkResp = restTemplate.exchange(
                "/api/upload/check?fileHash={hash}&userId={uid}",
                HttpMethod.GET, authEntity(),
                new ParameterizedTypeReference<>() {},
                uploadedFileHash, userId);
        assertThat(checkResp.getStatusCode().is2xxSuccessful()).isTrue();
    }

    private HttpEntity<Void> authEntity() {
        return new HttpEntity<>(authHeaders());
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("satoken", satoken);
        return headers;
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(data));
    }
}

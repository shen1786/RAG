package com.example.demo.Controller;


import com.example.demo.Config.DateTimeTools;
import com.example.demo.Config.SessionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import reactor.core.publisher.Flux;
import org.springframework.http.HttpStatus;


import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiController {
    private final ChatClient deepchatClient;
    private final VectorStore vectorStore;
    private final DateTimeTools dateTimeTools;
    private final StringRedisTemplate redisTemplate;
    private final SessionManager sessionManager;
    @GetMapping(value = "/chatmemory/chat", produces = "text/plain;charset=UTF-8")
    public String chat(String msg, String userId) {
        // 1️⃣ 手动做向量检索（不再用 Advisor）
        List<Document> docs = vectorStore.similaritySearch(msg);

        boolean hit = !docs.isEmpty()
                && docs.get(0).getScore() != null
                && docs.get(0).getScore() > 0.7;

        String finalPrompt;

        if (hit) {
            // 2️⃣ LangChain4j 风格 Soft RAG Prompt
            String knowledge = docs.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n"));

            finalPrompt = """
你是一个智能问答系统。
以下是一些可能有帮助的参考资料，请优先使用它们回答问题。
你可以使用系统提供的工具来获取实时信息。
当问题涉及当前时间、日期等实时数据时，请调用工具。

【参考资料】
%s

【用户问题】
%s
""".formatted(knowledge, msg);

        } else {
            // 3️⃣ 纯通用问答
            finalPrompt = msg;
        }

        return deepchatClient.prompt()
                .advisors(advisorSpec ->
                        advisorSpec.param(CONVERSATION_ID, userId))
                .tools(dateTimeTools)
                .user(finalPrompt)
                .call()
                .content();
    }

    /**
     * 获取用户会话列表接口
     * 用于获取用户的所有会话
     */
    @PostMapping("/session/list")
    public SessionListResponse getUserSessions(@RequestBody SessionListRequest request) {
        Set<String> sessions = sessionManager.getUserSessions(request.getUserId());

        return new SessionListResponse(
            request.getUserId(),
            sessions,
            sessions.size(),
            System.currentTimeMillis()
        );
    }

    /**
     * 删除会话接口
     * 用于删除指定的会话
     */
    @PostMapping("/session/delete")
    public SessionDeleteResponse deleteSession(@RequestBody SessionDeleteRequest request) {
        // 验证会话是否存在且属于该用户
        String sessionUserId = sessionManager.getUserIdBySession(request.getSessionId());
        if (sessionUserId == null || !sessionUserId.equals(request.getUserId())) {
            throw new IllegalArgumentException("会话不存在或无权删除");
        }

        sessionManager.deleteSession(request.getUserId(), request.getSessionId());

        return new SessionDeleteResponse(
            request.getSessionId(),
            System.currentTimeMillis(),
            "会话删除成功"
        );
    }

    /**
     * 创建新会话接口
     * 用于用户打开新的对话窗口时获取会话ID
     */
    @PostMapping("/session/create")
    public SessionCreateResponse createSession(@RequestBody SessionCreateRequest request) {
        String sessionId = sessionManager.createSession(request.getUserId());

        return new SessionCreateResponse(
            sessionId,
            System.currentTimeMillis(),
            "会话创建成功"
        );
    }

    /**
     * 新增多轮对话接口
     * 支持更丰富的上下文管理和会话控制
     */
    @PostMapping(value = "/multi-turn/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public MultiTurnChatResponse multiTurnChat(@RequestBody MultiTurnChatRequest request) {
        // 验证会话是否存在
        if (!sessionManager.sessionExists(request.getSessionId())) {
            throw new IllegalArgumentException("会话不存在或已过期");
        }

        // 更新会话活跃时间
        String userId = sessionManager.getUserIdBySession(request.getSessionId());
        if (userId == null) {
            throw new IllegalArgumentException("无法获取会话用户信息");
        }

        sessionManager.updateSessionActivity(userId, request.getSessionId());
        // 1. 向量检索
        List<Document> docs = vectorStore.similaritySearch(request.getMessage());

        boolean hit = !docs.isEmpty()
                && docs.get(0).getScore() != null
                && docs.get(0).getScore() > 0.7;

        String finalPrompt;

        if (hit) {
            // 构建增强提示词
            String knowledge = docs.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n"));

            finalPrompt = String.format("""
你是一个智能问答助手，能够基于提供的知识库信息进行专业回答。
当你认为知识库信息对回答有帮助时，请优先使用它们。

【知识库参考】
%s

【用户问题】
%s

请基于以上信息提供专业、准确的回答。
""",
                    knowledge,
                    request.getMessage());
        } else {
            // 普通问答
            finalPrompt = String.format("""
你是一个智能问答助手。

【用户问题】
%s

请提供专业、准确的回答。
""",
                    request.getMessage());
        }

        // 2. 构建AI调用
        ChatClientResponse response = deepchatClient.prompt()
                .advisors(advisorSpec ->
                    advisorSpec.param(CONVERSATION_ID, request.getSessionId()))
                .tools(dateTimeTools)
                .user(finalPrompt)
                .call()
                .chatClientResponse();

        // 3. 获取回复内容
        String reply = response.chatResponse().getResult().getOutput().getText();

        // 4. 构建响应
        return new MultiTurnChatResponse(
                request.getSessionId(),
                request.getTurnCount() + 1,
                reply,
                hit,
                docs.size(),
                System.currentTimeMillis()
        );
    }

    // 请求DTO
    public static class MultiTurnChatRequest {
        private String userId;
        private String sessionId;
        private Integer turnCount;
        private String message;

        // Getters and Setters
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }

        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }

        public Integer getTurnCount() { return turnCount; }
        public void setTurnCount(Integer turnCount) { this.turnCount = turnCount; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    // 响应DTO
    public static class MultiTurnChatResponse {
        private String sessionId;
        private Integer turnCount;
        private String reply;
        private Boolean knowledgeUsed;
        private Integer retrievedDocuments;
        private Long timestamp;

        public MultiTurnChatResponse(String sessionId, Integer turnCount, String reply,
                                  Boolean knowledgeUsed, Integer retrievedDocuments, Long timestamp) {
            this.sessionId = sessionId;
            this.turnCount = turnCount;
            this.reply = reply;
            this.knowledgeUsed = knowledgeUsed;
            this.retrievedDocuments = retrievedDocuments;
            this.timestamp = timestamp;
        }

        // Getters
        public String getSessionId() { return sessionId; }
        public Integer getTurnCount() { return turnCount; }
        public String getReply() { return reply; }
        public Boolean getKnowledgeUsed() { return knowledgeUsed; }
        public Integer getRetrievedDocuments() { return retrievedDocuments; }
        public Long getTimestamp() { return timestamp; }
    }

    // 会话创建请求DTO
    public static class SessionCreateRequest {
        private String userId;

        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
    }

    // 会话创建响应DTO
    public static class SessionCreateResponse {
        private String sessionId;
        private Long timestamp;
        private String message;

        public SessionCreateResponse(String sessionId, Long timestamp, String message) {
            this.sessionId = sessionId;
            this.timestamp = timestamp;
            this.message = message;
        }

        public String getSessionId() { return sessionId; }
        public Long getTimestamp() { return timestamp; }
        public String getMessage() { return message; }
    }

    // 会话删除请求DTO
    public static class SessionDeleteRequest {
        private String userId;
        private String sessionId;

        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }

        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    }

    // 会话删除响应DTO
    public static class SessionDeleteResponse {
        private String sessionId;
        private Long timestamp;
        private String message;

        public SessionDeleteResponse(String sessionId, Long timestamp, String message) {
            this.sessionId = sessionId;
            this.timestamp = timestamp;
            this.message = message;
        }

        public String getSessionId() { return sessionId; }
        public Long getTimestamp() { return timestamp; }
        public String getMessage() { return message; }
    }

    // 会话列表请求DTO
    public static class SessionListRequest {
        private String userId;

        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
    }

    // 会话列表响应DTO
    public static class SessionListResponse {
        private String userId;
        private Set<String> sessions;
        private Integer sessionCount;
        private Long timestamp;

        public SessionListResponse(String userId, Set<String> sessions, Integer sessionCount, Long timestamp) {
            this.userId = userId;
            this.sessions = sessions;
            this.sessionCount = sessionCount;
            this.timestamp = timestamp;
        }

        public String getUserId() { return userId; }
        public Set<String> getSessions() { return sessions; }
        public Integer getSessionCount() { return sessionCount; }
        public Long getTimestamp() { return timestamp; }
    }

    // 异常处理
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleIllegalArgument(IllegalArgumentException e) {
        return new ErrorResponse(e.getMessage(), 400);
    }

    // 错误响应DTO
    public static class ErrorResponse {
        private String message;
        private int code;

        public ErrorResponse(String message, int code) {
            this.message = message;
            this.code = code;
        }

        public String getMessage() { return message; }
        public int getCode() { return code; }
    }

}

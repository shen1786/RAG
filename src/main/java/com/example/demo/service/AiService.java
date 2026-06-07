package com.example.demo.service;

import com.example.demo.Config.DateTimeTools;
import com.example.demo.Config.SessionManager;
import com.example.demo.Config.SummaryWindowChatMemory;
import com.example.demo.model.dto.ApiResponse;
import com.example.demo.model.dto.MultiTurnChatRequest;
import com.example.demo.model.dto.RetrievalResult;
import com.example.demo.model.dto.SessionCreateRequest;
import com.example.demo.model.dto.SessionCreateResponse;
import com.example.demo.model.dto.SessionDeleteRequest;
import com.example.demo.model.dto.SessionDeleteResponse;
import com.example.demo.model.dto.SessionListRequest;
import com.example.demo.model.dto.SessionListResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import com.example.demo.model.dto.HierarchyHit;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatClient deepchatClient;
    private final RagRetrievalService ragRetrievalService;
    private final QueryRewriteService queryRewriteService;
    private final RetrievalSubQueryService retrievalSubQueryService;
    private final UserProfileService userProfileService;
    private final DateTimeTools dateTimeTools;
    private final SummaryWindowChatMemory chatMemory;
    private final SessionManager sessionManager;

    @CircuitBreaker(name = "dashscope-chat", fallbackMethod = "chatFallback")
    public String chat(String msg, String userId) {
        RetrievalResult result = ragRetrievalService.retrieve(msg, userId);
        String systemPrompt = buildSingleTurnSystemPrompt(result);

        return deepchatClient.prompt()
                .advisors(advisorSpec -> advisorSpec.param(CONVERSATION_ID, userId))
                .tools(dateTimeTools)
                .system(systemPrompt)
                .user(msg)
                .call()
                .content();
    }

    public ApiResponse<SessionListResponse> getUserSessions(SessionListRequest request) {
        Set<String> sessions = sessionManager.getUserSessions(request.getUserId());
        SessionListResponse data = new SessionListResponse(
                request.getUserId(),
                sessions,
                sessions.size(),
                System.currentTimeMillis()
        );
        return ApiResponse.success(data);
    }

    public ApiResponse<SessionDeleteResponse> deleteSession(SessionDeleteRequest request) {
        validateSessionOwnership(request.getUserId(), request.getSessionId());
        // 先读完整历史供画像提炼（清除后就读不到了）
        List<Message> history = getHistoryMessages(request.getSessionId());
        submitProfileExtractionIfNeeded(request.getUserId(), history);
        // 统一清理三层记忆（窗口+完整历史+摘要）
        chatMemory.clear(request.getSessionId());
        sessionManager.deleteSession(request.getUserId(), request.getSessionId());

        SessionDeleteResponse data = new SessionDeleteResponse(
                request.getSessionId(),
                System.currentTimeMillis(),
                "会话删除成功，画像提炼已在后台进行"
        );
        return ApiResponse.success(data);
    }

    public ApiResponse<String> extractProfile(SessionDeleteRequest request) {
        if (!isSessionOwnedByUser(request.getUserId(), request.getSessionId())) {
            return ApiResponse.success("会话无效，可能已过期或不存在");
        }

        List<Message> history = getHistoryMessages(request.getSessionId());
        submitProfileExtractionIfNeeded(request.getUserId(), history);
        return ApiResponse.success("画像提炼任务已提交");
    }

    public ApiResponse<List<Map<String, Object>>> getHistory(String userId, String sessionId) {
        validateSessionOwnership(userId, sessionId);
        List<Map<String, Object>> rawHistory = chatMemory.getFullHistoryDetail(sessionId);
        // 转换为前端期望的格式：{role: 'user'|'ai', content: '...'}
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : rawHistory) {
            String type = (String) item.get("type");
            if (type == null) continue;
            String role = switch (type.toLowerCase()) {
                case "user" -> "user";
                case "assistant" -> "ai";
                default -> null;
            };
            if (role == null) continue;
            result.add(Map.of(
                    "role", role,
                    "content", item.getOrDefault("content", "")
            ));
        }
        return ApiResponse.success(result);
    }

    public ApiResponse<SessionCreateResponse> createSession(SessionCreateRequest request) {
        String sessionId = sessionManager.createSession(request.getUserId());
        SessionCreateResponse data = new SessionCreateResponse(
                sessionId,
                System.currentTimeMillis(),
                "会话创建成功"
        );
        return ApiResponse.success(data);
    }

    public String chatFallback(String msg, String userId, Throwable t) {
        log.warn("chat 熔断降级: userId={}, error={}", userId, t.getMessage());
        return "AI 服务暂时不可用，请稍后重试";
    }

    public Flux<ServerSentEvent<String>> multiTurnChat(MultiTurnChatRequest request) {
        String userId = requireActiveSessionUser(request.getSessionId());
        if (request.getUserId() != null && !request.getUserId().equals(userId)) {
            throw new IllegalArgumentException("会话用户与当前登录用户不一致");
        }
        String originalQuery = request.getMessage();
        String rewrittenQuery = queryRewriteService.rewrite(request.getSessionId(), originalQuery);
        log.info("多轮对话检索 - 原始查询: '{}', 改写后: '{}'", originalQuery, rewrittenQuery);

        List<String> retrievalQueries = retrievalSubQueryService.generateSubQueries(rewrittenQuery, originalQuery);
        RetrievalResult result = ragRetrievalService.retrieveWithMultiPathRecall(
                rewrittenQuery,
                retrievalQueries,
                userId
        );

        String systemPrompt = buildMultiTurnSystemPrompt(userId, result);

        // 1. 组装引文 JSON
        List<Map<String, Object>> citations = new ArrayList<>();
        if (result.getHierarchyHits() != null) {
            for (int i = 0; i < result.getHierarchyHits().size(); i++) {
                HierarchyHit hit = result.getHierarchyHits().get(i);
                Map<String, Object> cite = new HashMap<>();
                cite.put("sourceName", hit.getFilename() != null ? hit.getFilename() : "");
                cite.put("minioUrl", hit.getMinioUrl() != null ? hit.getMinioUrl() : "");
                cite.put("docTitle", hit.getDocTitle() != null ? hit.getDocTitle() : "");
                cite.put("sectionTitle", hit.getSectionTitle() != null ? hit.getSectionTitle() : "");
                cite.put("chunkIndex", hit.getLeafChunkIndex() != null ? hit.getLeafChunkIndex() + 1 : null);
                
                String label;
                if (hit.getSectionTitle() != null && !hit.getSectionTitle().isBlank()) {
                    label = hit.getSectionTitle();
                } else if (hit.getLeafChunkIndex() != null) {
                    label = "分段 " + (hit.getLeafChunkIndex() + 1);
                } else {
                    label = "段落 " + (i + 1);
                }
                cite.put("label", label);
                cite.put("text", hit.getContent() != null ? hit.getContent() : "");
                
                double scoreDouble = hit.getLeafScore() != null ? hit.getLeafScore() : 0.0;
                int scorePercent = (int) Math.round(scoreDouble * 100);
                cite.put("score", scorePercent);
                
                citations.add(cite);
            }
        }

        String citationsJson;
        try {
            citationsJson = MAPPER.writeValueAsString(citations);
        } catch (Exception e) {
            log.error("序列化引文失败", e);
            citationsJson = "[]";
        }

        // 2. 发送引文事件，紧接着推送大模型文本 Token
        ServerSentEvent<String> citationsEvent = ServerSentEvent.<String>builder()
                .event("citations")
                .data(citationsJson)
                .build();

        Flux<ServerSentEvent<String>> citationsFlux = Flux.just(citationsEvent);

        Flux<ServerSentEvent<String>> textFlux = deepchatClient.prompt()
                .advisors(advisorSpec -> advisorSpec.param(CONVERSATION_ID, request.getSessionId()))
                .tools(dateTimeTools)
                .system(systemPrompt)
                .user(originalQuery)
                .stream()
                .content()
                .map(token -> ServerSentEvent.<String>builder()
                        .event("message")
                        .data(token)
                        .build());

        return Flux.concat(citationsFlux, textFlux);
    }

    public Flux<ServerSentEvent<String>> multiTurnChatFallback(MultiTurnChatRequest request, Throwable t) {
        log.warn("multiTurnChat 熔断降级: sessionId={}, error={}", request.getSessionId(), t.getMessage());
        ServerSentEvent<String> errorEvent = ServerSentEvent.<String>builder()
                .event("error")
                .data("AI 服务暂时不可用，请稍后重试")
                .build();
        return Flux.just(errorEvent);
    }

    private String buildSingleTurnSystemPrompt(RetrievalResult result) {
        String systemPrompt = "你是一个智能问答系统。\n"
                + "你可以使用系统提供的工具来获取实时信息。\n"
                + "当问题涉及当前时间、日期等实时数据时，请调用工具。";
        if (!result.isHit()) {
            return systemPrompt;
        }
        return systemPrompt
                + "\n\n【回答约束】\n"
                + "1. 必须优先依据【参考资料】回答，不能与引用内容相矛盾。\n"
                + "2. 如果【参考资料】中出现了用户问题相关实体或事实，不得回答“知识库未出现”“未提供相关内容”。\n"
                + "3. 如果引用内容不足以完整回答，只能说明“引用中只看到...”并列出已看到的信息，不要编造缺失部分。\n"
                + "4. 回答时尽量使用引用中的原词和结构。\n\n【参考资料】\n"
                + result.getKnowledgeText();
    }

    private String buildMultiTurnSystemPrompt(String userId, RetrievalResult result) {
        StringBuilder systemPrompt = new StringBuilder("你是一个智能问答助手。");
        String userProfile = userProfileService.getProfile(userId);
        if (userProfile != null) {
            systemPrompt.append("\n\n【用户背景与偏好（长期记忆）】\n")
                    .append(userProfile)
                    .append("\n请根据上述用户特征调整你的回答风格和内容。");
        }

        if (result.isHit()) {
            systemPrompt.append(String.format(
                    "\n\n【回答约束】\n"
                            + "1. 必须优先依据【知识库参考】回答，不能与引用内容相矛盾。\n"
                            + "2. 如果【知识库参考】中出现了用户问题相关实体或事实，不得回答“知识库未出现”“未提供相关内容”。\n"
                            + "3. 如果引用内容不足以完整回答，只能说明“引用中只看到...”并列出已看到的信息，不要编造缺失部分。\n"
                            + "4. 回答时尽量使用引用中的原词和结构。\n\n【知识库参考】\n%s",
                    result.getKnowledgeText()
            ));
        } else {
            systemPrompt.append("\n请提供专业、准确的回答。");
        }
        return systemPrompt.toString();
    }

    private String requireActiveSessionUser(String sessionId) {
        if (!sessionManager.sessionExists(sessionId)) {
            throw new IllegalArgumentException("会话不存在或已过期");
        }

        String userId = sessionManager.getUserIdBySession(sessionId);
        if (userId == null) {
            throw new IllegalArgumentException("无法获取会话用户信息");
        }

        sessionManager.updateSessionActivity(userId, sessionId);
        return userId;
    }

    private boolean isSessionOwnedByUser(String userId, String sessionId) {
        String sessionUserId = sessionManager.getUserIdBySession(sessionId);
        return sessionUserId != null && sessionUserId.equals(userId);
    }

    private void validateSessionOwnership(String userId, String sessionId) {
        if (!isSessionOwnedByUser(userId, sessionId)) {
            throw new IllegalArgumentException("会话不存在或无权删除");
        }
    }

    private List<Message> getHistoryMessages(String sessionId) {
        return chatMemory.getFullHistory(sessionId);
    }

    private void submitProfileExtractionIfNeeded(String userId, List<Message> history) {
        if (history != null && !history.isEmpty()) {
            userProfileService.extractProfileAsync(userId, history);
        }
    }
}

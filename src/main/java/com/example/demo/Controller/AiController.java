package com.example.demo.Controller;


import com.example.demo.Config.DateTimeTools;
import com.example.demo.Config.SessionManager;
import com.example.demo.model.dto.*;
import com.example.demo.service.QueryRewriteService;
import com.example.demo.service.RagRetrievalService;
import com.example.demo.service.UserProfileService;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;

import com.alibaba.cloud.ai.memory.redis.RedisChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@Slf4j
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiController {
    private final ChatClient deepchatClient;
    private final RagRetrievalService ragRetrievalService;
    private final QueryRewriteService queryRewriteService;
    private final UserProfileService userProfileService;
    private final DateTimeTools dateTimeTools;
    private final RedisChatMemoryRepository chatMemoryRepository;
    private final SessionManager sessionManager;
    @GetMapping(value = "/chatmemory/chat", produces = "text/plain;charset=UTF-8")
    public String chat(String msg, String userId) {
        // 1️⃣ 向量粗召回 + Rerank 精排
        RetrievalResult result = ragRetrievalService.retrieve(msg);

        String systemPrompt = "你是一个智能问答系统。\n" +
                "你可以使用系统提供的工具来获取实时信息。\n" +
                "当问题涉及当前时间、日期等实时数据时，请调用工具。";

        if (result.isHit()) {
            // 2️⃣ 命中知识库，将参考资料放入 System Prompt
            systemPrompt += "\n\n以下是一些可能有帮助的参考资料，请优先使用它们回答问题：\n\n【参考资料】\n" + result.getKnowledgeText();
        }

        // 3️⃣ 调用大模型，把原始短文本给 user，巨量参考资料给 system
        return deepchatClient.prompt()
                .advisors(advisorSpec ->
                        advisorSpec.param(CONVERSATION_ID, userId))
                .tools(dateTimeTools)
                .system(systemPrompt)
                .user(msg)
                .call()
                .content();
    }

    /**
     * 获取用户会话列表接口
     * 用于获取用户的所有会话
     */
    @PostMapping("/session/list")
    public ApiResponse<SessionListResponse> getUserSessions(@RequestBody SessionListRequest request) {
        Set<String> sessions = sessionManager.getUserSessions(request.getUserId());

        SessionListResponse data = new SessionListResponse(
            request.getUserId(),
            sessions,
            sessions.size(),
            System.currentTimeMillis()
        );
        return ApiResponse.success(data);
    }

    /**
     * 删除会话接口
     * 用于删除指定的会话
     */
    @PostMapping("/session/delete")
    public ApiResponse<SessionDeleteResponse> deleteSession(@RequestBody SessionDeleteRequest request) {
        // 验证会话是否存在且属于该用户
        String sessionUserId = sessionManager.getUserIdBySession(request.getSessionId());
        if (sessionUserId == null || !sessionUserId.equals(request.getUserId())) {
            throw new IllegalArgumentException("会话不存在或无权删除");
        }

        // ★ 先同步读取对话历史（必须在删除之前读取，否则数据会丢失）
        List<Message> history = chatMemoryRepository.findByConversationId(request.getSessionId());

        // ★ 异步提炼用户画像（长期记忆），传入预读取的历史消息
        if (history != null && !history.isEmpty()) {
            userProfileService.extractProfileAsync(request.getUserId(), history);
        }

        // 删除会话（会清除 Redis 中的 ChatMemory）
        sessionManager.deleteSession(request.getUserId(), request.getSessionId());

        SessionDeleteResponse data = new SessionDeleteResponse(
            request.getSessionId(),
            System.currentTimeMillis(),
            "会话删除成功，画像提炼已在后台进行"
        );
        return ApiResponse.success(data);
    }

    /**
     * 手动触发画像提炼接口
     * 用于前端在用户关闭浏览器或切换会话时，主动将这段对话记忆进行结转
     */
    @PostMapping("/session/extract-profile")
    public ApiResponse<String> extractProfile(@RequestBody SessionDeleteRequest request) {
        // 验证会话是否存在且属于该用户
        String sessionUserId = sessionManager.getUserIdBySession(request.getSessionId());
        if (sessionUserId == null || !sessionUserId.equals(request.getUserId())) {
            return ApiResponse.success("会话无效，可能已过期或不存在");
        }

        // 同步读取对话历史
        List<Message> history = chatMemoryRepository.findByConversationId(request.getSessionId());

        // 异步提炼用户画像
        if (history != null && !history.isEmpty()) {
            userProfileService.extractProfileAsync(request.getUserId(), history);
        }

        return ApiResponse.success("画像提炼任务已提交");
    }

    /**
     * 获取会话历史记录
     * 切换会话时提取该会话的历史对话在前端显示
     */
    @GetMapping("/session/history")
    public ApiResponse<List<java.util.Map<String, Object>>> getHistory(@org.springframework.web.bind.annotation.RequestParam String sessionId) {
        List<Message> history = chatMemoryRepository.findByConversationId(sessionId);
        List<java.util.Map<String, Object>> result = new java.util.ArrayList<>();
        if (history != null) {
            for (Message msg : history) {
                String roleVal = msg.getMessageType().getValue().toLowerCase();
                // 仅返回用户和助手的消息，忽略 system
                if (roleVal.equals("user") || roleVal.equals("assistant")) {
                    java.util.Map<String, Object> map = new java.util.HashMap<>();
                    map.put("role", roleVal.equals("user") ? "user" : "ai");
                    map.put("content", msg.getText());
                    result.add(map);
                }
            }
        }
        return ApiResponse.success(result);
    }


    /**
     * 创建新会话接口
     * 用于用户打开新的对话窗口时获取会话ID
     */
    @PostMapping("/session/create")
    public ApiResponse<SessionCreateResponse> createSession(@RequestBody SessionCreateRequest request) {
        String sessionId = sessionManager.createSession(request.getUserId());

        SessionCreateResponse data = new SessionCreateResponse(
            sessionId,
            System.currentTimeMillis(),
            "会话创建成功"
        );
        return ApiResponse.success(data);
    }

    /**
     * 新增多轮对话接口（流式输出）
     * 支持更丰富的上下文管理和会话控制，以 SSE 流式返回 token
     */
    @PostMapping(value = "/multi-turn/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> multiTurnChat(@RequestBody MultiTurnChatRequest request) {
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

        // 1. 查询改写：将含有代词/省略的用户问题改写为独立完整的查询语句
        String originalQuery = request.getMessage();
        String rewrittenQuery = queryRewriteService.rewrite(request.getSessionId(), originalQuery);
        log.info("多轮对话检索 - 原始查询: '{}', 改写后: '{}'", originalQuery, rewrittenQuery);

        // 2. 用改写后的查询做向量粗召回 + Rerank 精排
        RetrievalResult result = ragRetrievalService.retrieve(rewrittenQuery);

        // 3. 构建 System Prompt（注入长期记忆 + 知识库参考）
        StringBuilder systemPrompt = new StringBuilder("你是一个智能问答助手。");

        // ★ 注入用户画像（长期记忆），让 AI 了解用户的背景和偏好
        String userProfile = userProfileService.getProfile(userId);
        if (userProfile != null) {
            systemPrompt.append("\n\n【用户背景与偏好（长期记忆）】\n")
                       .append(userProfile)
                       .append("\n请根据上述用户特征调整你的回答风格和内容。");
        }

        if (result.isHit()) {
            // 知识库信息放在 system 中，防止污染 ChatMemory
            systemPrompt.append(String.format("\n\n能够基于提供的知识库信息进行专业回答。\n当你认为知识库信息对回答有帮助时，请优先使用它们。\n\n【知识库参考】\n%s",
                    result.getKnowledgeText()));
        } else {
            systemPrompt.append("\n请提供专业、准确的回答。");
        }

        // 4. 构建AI调用 — 使用 .stream() 替代 .call() 以流式返回
        return deepchatClient.prompt()
                .advisors(advisorSpec ->
                    advisorSpec.param(CONVERSATION_ID, request.getSessionId()))
                .tools(dateTimeTools)
                .system(systemPrompt.toString())
                .user(originalQuery)
                .stream()
                .content();
    }
}

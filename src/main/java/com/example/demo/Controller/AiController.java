package com.example.demo.Controller;


import com.example.demo.Config.DateTimeTools;
import com.example.demo.Config.SessionManager;
import com.example.demo.model.dto.*;
import com.example.demo.service.RagRetrievalService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiController {
    private final ChatClient deepchatClient;
    private final RagRetrievalService ragRetrievalService;
    private final DateTimeTools dateTimeTools;
    private final StringRedisTemplate redisTemplate;
    private final SessionManager sessionManager;
    @GetMapping(value = "/chatmemory/chat", produces = "text/plain;charset=UTF-8")
    public String chat(String msg, String userId) {
        // 1️⃣ 向量粗召回 + Rerank 精排
        RetrievalResult result = ragRetrievalService.retrieve(msg);

        String finalPrompt;

        if (result.isHit()) {
            // 2️⃣ 命中知识库，构建增强 Prompt
            finalPrompt = """


【参考资料】
%s

【用户问题】
%s
""".formatted(result.getKnowledgeText(), msg);

        } else {
            // 3️⃣ 纯通用问答
            finalPrompt = msg;
        }

        return deepchatClient.prompt("你是一个智能问答系统。\n" +
                        "以下是一些可能有帮助的参考资料，请优先使用它们回答问题。\n" +
                        "你可以使用系统提供的工具来获取实时信息。\n" +
                        "当问题涉及当前时间、日期等实时数据时，请调用工具。")
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

        sessionManager.deleteSession(request.getUserId(), request.getSessionId());

        SessionDeleteResponse data = new SessionDeleteResponse(
            request.getSessionId(),
            System.currentTimeMillis(),
            "会话删除成功"
        );
        return ApiResponse.success(data);
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
     * 新增多轮对话接口
     * 支持更丰富的上下文管理和会话控制
     */
    @PostMapping(value = "/multi-turn/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<MultiTurnChatResponse> multiTurnChat(@RequestBody MultiTurnChatRequest request) {
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

        // 1. 向量粗召回 + Rerank 精排
        RetrievalResult result = ragRetrievalService.retrieve(request.getMessage());

        String finalPrompt;

        if (result.isHit()) {
            // 构建增强提示词
            finalPrompt = String.format("""
你是一个智能问答助手，能够基于提供的知识库信息进行专业回答。
当你认为知识库信息对回答有帮助时，请优先使用它们。

【知识库参考】
%s

【用户问题】
%s

请基于以上信息提供专业、准确的回答。
""",
                    result.getKnowledgeText(),
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
        MultiTurnChatResponse data = new MultiTurnChatResponse(
                request.getSessionId(),
                request.getTurnCount() + 1,
                reply,
                result.isHit(),
                result.getFinalCount(),
                System.currentTimeMillis()
        );
        return ApiResponse.success(data);
    }
}

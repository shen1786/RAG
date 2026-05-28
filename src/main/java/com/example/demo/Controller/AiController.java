package com.example.demo.Controller;


import cn.dev33.satoken.annotation.SaCheckPermission;
import com.example.demo.model.dto.*;
import com.example.demo.service.AiService;
import com.example.demo.service.AuthContextService;
import com.example.demo.service.AsrService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.io.InputStream;
import java.util.List;

@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiController {
    private final AiService aiService;
    private final AuthContextService authContextService;
    private final AsrService asrService;

    @SaCheckPermission("ai:chat")
    @GetMapping(value = "/chatmemory/chat", produces = "text/plain;charset=UTF-8")
    public String chat(String msg, String userId) {
        return aiService.chat(msg, authContextService.resolveUserId(userId));
    }

    /**
     * 获取用户会话列表接口
     * 用于获取用户的所有会话
     */
    @SaCheckPermission("ai:session:list")
    @PostMapping("/session/list")
    public ApiResponse<SessionListResponse> getUserSessions(@RequestBody SessionListRequest request) {
        request.setUserId(authContextService.resolveUserId(request.getUserId()));
        return aiService.getUserSessions(request);
    }

    /**
     * 删除会话接口
     * 用于删除指定的会话
     */
    @SaCheckPermission("ai:session:delete")
    @PostMapping("/session/delete")
    public ApiResponse<SessionDeleteResponse> deleteSession(@RequestBody SessionDeleteRequest request) {
        request.setUserId(authContextService.resolveUserId(request.getUserId()));
        return aiService.deleteSession(request);
    }

    /**
     * 手动触发画像提炼接口
     * 用于前端在用户关闭浏览器或切换会话时，主动将这段对话记忆进行结转
     */
    @SaCheckPermission("ai:session:extract-profile")
    @PostMapping("/session/extract-profile")
    public ApiResponse<String> extractProfile(@RequestBody SessionDeleteRequest request) {
        request.setUserId(authContextService.resolveUserId(request.getUserId()));
        return aiService.extractProfile(request);
    }

    /**
     * 获取会话历史记录
     * 切换会话时提取该会话的历史对话在前端显示
     */
    @SaCheckPermission("ai:session:history")
    @GetMapping("/session/history")
    public ApiResponse<List<java.util.Map<String, Object>>> getHistory(@org.springframework.web.bind.annotation.RequestParam String sessionId) {
        return aiService.getHistory(authContextService.getCurrentUserId(), sessionId);
    }


    /**
     * 创建新会话接口
     * 用于用户打开新的对话窗口时获取会话ID
     */
    @SaCheckPermission("ai:session:create")
    @PostMapping("/session/create")
    public ApiResponse<SessionCreateResponse> createSession(@RequestBody SessionCreateRequest request) {
        request.setUserId(authContextService.resolveUserId(request.getUserId()));
        return aiService.createSession(request);
    }

    /**
     * 新增多轮对话接口（流式输出）
     * 支持更丰富的上下文管理和会话控制，以 SSE 流式返回 token
     */
    @SaCheckPermission("ai:multi-turn:chat")
    @PostMapping(value = "/multi-turn/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> multiTurnChat(@RequestBody MultiTurnChatRequest request) {
        request.setUserId(authContextService.resolveUserId(request.getUserId()));
        return aiService.multiTurnChat(request);
    }

    /**
     * 语音 ASR 接口
     * 接收前端录制的 PCM 音频，并返回识别出的文本
     */
    @SaCheckPermission("ai:chat")
    @PostMapping(value = "/asr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<String> transcribe(@RequestParam("file") MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            String text = asrService.transcribe(in);
            return ApiResponse.success("识别成功", text);
        } catch (Exception e) {
            return ApiResponse.error("语音识别失败: " + e.getMessage());
        }
    }
}

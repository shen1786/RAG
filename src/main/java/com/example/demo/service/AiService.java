package com.example.demo.service;

import com.example.demo.Config.DateTimeTools;
import com.example.demo.model.dto.ApiResponse;
import com.example.demo.model.dto.MultiTurnChatRequest;
import com.example.demo.model.dto.RetrievalResult;
import com.example.demo.model.dto.SessionCreateRequest;
import com.example.demo.model.dto.SessionCreateResponse;
import com.example.demo.model.dto.SessionDeleteRequest;
import com.example.demo.model.dto.SessionDeleteResponse;
import com.example.demo.model.dto.SessionListRequest;
import com.example.demo.model.dto.SessionListResponse;
import com.example.demo.service.retrieval.RerankHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import com.example.demo.model.dto.HierarchyHit;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

/**
 * AI 问答核心编排服务。
 *
 * <h2>职责</h2>
 * 作为 RAG 问答链路的<strong>顶层编排者</strong>，协调查询改写、子查询生成、多路检索、
 * 重排、知识文本构建、Prompt 组装、LLM 调用及 SSE 流式响应等全部环节。
 * 本身不包含检索/重排等具体算法实现，而是委托给各专职服务。
 *
 * <h2>核心流程（多轮对话）</h2>
 * <pre>
 * 用户提问
 *   │
 *   ▼
 * ① 会话校验 — ChatSessionService.requireActiveSessionUser()
 *   │           验证 sessionId 属于当前登录用户，防止越权
 *   ▼
 * ② 查询改写 — QueryRewriteService.rewrite()
 *   │           结合最近 6 条历史，用 LLM 将指代/省略补全为自包含查询
 *   │           例："它有哪些依赖？" → "RagUnitService 有哪些依赖注入？"
 *   ▼
 * ③ 子查询生成 — RetrievalSubQueryService.generateSubQueries()
 *   │             从同义词、相关概念、属性等角度生成最多 4 条补充查询
 *   │             提高向量召回的覆盖率
 *   ▼
 * ④ 多路检索 — RagRetrievalService.retrieveWithMultiPathRecall()
 *   │           路径A：主查询 → 层级策略(摘要→章节→叶子) → 平铺降级
 *   │           路径B：子查询并行召回 → 合并去重
 *   │           最终：rerank 精排 → 阈值过滤 → 构建知识文本
 *   ▼
 * ⑤ Prompt 组装 — buildMultiTurnSystemPrompt()
 *   │              拼装：用户画像(长期记忆) + 回答约束 + 检索到的知识文本
 *   ▼
 * ⑥ SSE 流式响应
 *       event: citations  → 引文列表 JSON（前端立即展示来源卡片）
 *       event: message    → LLM 逐 token 流式返回（前端打字机效果）
 * </pre>
 *
 * <h2>降级策略</h2>
 * 整条链路设计了 6 层降级，确保任一环节故障不会导致整体不可用：
 * <ol>
 *   <li>查询改写失败 → 使用原始查询</li>
 *   <li>子查询生成失败 → 只用主查询</li>
 *   <li>层级检索未命中 → 降级到平铺检索</li>
 *   <li>向量搜索无结果 → 降级为 SQL 关键词搜索</li>
 *   <li>多路合并分数低 → 回退主查询结果</li>
 *   <li>Rerank 熔断 → 回退到向量相似度排序</li>
 * </ol>
 *
 * <h2>熔断保护</h2>
 * <ul>
 *   <li>{@code @CircuitBreaker(name = "dashscope-chat")} — LLM 调用熔断</li>
 *   <li>{@code @TimeLimiter(name = "dashscope-chat")} — LLM 调用超时</li>
 *   <li>Rerank 熔断在 {@link RerankHelper} 中配置</li>
 * </ul>
 *
 * @see RagRetrievalService 检索编排
 * @see QueryRewriteService 查询改写
 * @see RetrievalSubQueryService 子查询生成
 * @see ChatSessionService 会话管理
 * @see UserProfileService 用户画像（长期记忆）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Spring AI 聊天客户端，底层模型为 qwen-plus（DashScope），已接入对话记忆 Advisor */
    private final ChatClient deepchatClient;
    /** RAG 检索编排服务，协调层级/平铺/多路检索策略 */
    private final RagRetrievalService ragRetrievalService;
    /** 查询改写服务，结合对话历史将用户问题改写为自包含查询 */
    private final QueryRewriteService queryRewriteService;
    /** 子查询生成服务，从多角度生成补充检索查询以提高召回率 */
    private final RetrievalSubQueryService retrievalSubQueryService;
    /** 用户画像服务，管理 Redis 中的用户长期记忆 */
    private final UserProfileService userProfileService;
    /** 日期时间工具，注册为 LLM Tool Call，支持"现在几点"等实时问题 */
    private final DateTimeTools dateTimeTools;
    /** 会话管理服务，负责会话 CRUD、历史查询、权限校验 */
    private final ChatSessionService chatSessionService;

    // ────── 委托给 ChatSessionService 的会话方法 ──────

    /**
     * 创建新的聊天会话。
     * 委托给 {@link ChatSessionService}，创建一个空白会话并返回会话 ID。
     *
     * @param request 会话创建请求（含 userId、可选标题）
     * @return 会话 ID 和创建时间
     */
    public ApiResponse<SessionCreateResponse> createSession(SessionCreateRequest request) {
        return chatSessionService.createSession(request);
    }

    /**
     * 获取指定用户的所有会话列表（分页）。
     *
     * @param request 包含 userId、分页参数
     * @return 会话列表（含标题、创建时间、最后活跃时间等）
     */
    public ApiResponse<SessionListResponse> getUserSessions(SessionListRequest request) {
        return chatSessionService.getUserSessions(request);
    }

    /**
     * 删除指定会话及其全部聊天记录。
     * 删除时会异步触发用户画像提取（从对话历史中沉淀长期记忆）。
     *
     * @param request 包含 userId 和 sessionId
     * @return 删除结果
     */
    public ApiResponse<SessionDeleteResponse> deleteSession(SessionDeleteRequest request) {
        return chatSessionService.deleteSession(request);
    }

    /**
     * 手动触发用户画像提取。
     * 从用户最近的对话历史中提取职业、技术栈、偏好等特征，存入 Redis 长期记忆。
     *
     * @param request 包含 userId
     * @return 提取结果
     */
    public ApiResponse<String> extractProfile(SessionDeleteRequest request) {
        return chatSessionService.extractProfile(request);
    }

    /**
     * 获取指定会话的完整聊天历史。
     *
     * @param userId    当前用户 ID
     * @param sessionId 会话 ID
     * @return 消息列表（角色、内容、时间戳）
     */
    public ApiResponse<List<Map<String, Object>>> getHistory(String userId, String sessionId) {
        return chatSessionService.getHistory(userId, sessionId);
    }

    // ────── 聊天编排 ──────

    /**
     * 单轮对话（无会话上下文管理）。
     *
     * <h3>核心流程</h3>
     * <pre>
     * 用户消息 msg
     *   │
     *   ▼
     * ① 向量检索 — ragRetrievalService.retrieve(msg, userId)
     *   │           先尝试层级策略，未命中则降级平铺策略
     *   ▼
     * ② 构建系统提示词 — buildSingleTurnSystemPrompt(result)
     *   │                 命中：基础提示 + 回答约束 + 知识文本
     *   │                 未命中：仅基础提示
     *   ▼
     * ③ 调用 LLM — deepchatClient.prompt()
     *   │           使用 qwen-plus 模型，同步阻塞返回
     *   │           注入 CONVERSATION_ID 实现简易记忆
     *   ▼
     * 返回 AI 回答文本
     * </pre>
     *
     * <h3>保护机制</h3>
     * <ul>
     *   <li>{@code @CircuitBreaker} — DashScope 调用失败率达阈值时自动熔断</li>
     *   <li>{@code @TimeLimiter} — 超时保护，防止 LLM 长时间无响应</li>
     *   <li>熔断后走 {@link #chatFallback} 返回友好提示</li>
     * </ul>
     *
     * @param msg    用户提问内容（已由 Controller 校验：非空、≤4000 字符）
     * @param userId 当前登录用户 ID，用于向量检索的数据隔离和对话记忆关联
     * @return 异步结果，包含 LLM 生成的回答文本
     * @see #chatFallback 熔断降级方法
     * @see RagRetrievalService#retrieve(String, String) 单路径检索
     */
    @CircuitBreaker(name = "dashscope-chat", fallbackMethod = "chatFallback")
    @TimeLimiter(name = "dashscope-chat")
    public CompletableFuture<String> chat(String msg, String userId) {
        return CompletableFuture.supplyAsync(() -> {
            // ① 检索：从向量库召回相关文档片段（先层级策略，未命中降级平铺）
            RetrievalResult result = ragRetrievalService.retrieve(msg, userId);
            // ② 组装系统提示词：基础角色 + [可选]回答约束 + 知识文本
            String systemPrompt = buildSingleTurnSystemPrompt(result);

            // ③ 调用 LLM：注入对话记忆、日期工具，同步阻塞返回
            return deepchatClient.prompt()
                    .advisors(advisorSpec -> advisorSpec.param(CONVERSATION_ID, userId))
                    .tools(dateTimeTools)
                    .system(systemPrompt)
                    .user(msg)
                    .call()
                    .content();
        });
    }

    /**
     * 单轮对话的熔断降级方法。
     * 当 DashScope LLM 调用失败率超过阈值或超时时，Resilience4j 自动触发此方法。
     *
     * @param msg    原始用户消息（未使用，仅用于方法签名匹配）
     * @param userId 用户 ID（未使用，仅用于方法签名匹配）
     * @param t      触发降级的异常（超时、网络错误等）
     * @return 包含友好提示的已完成 Future
     */
    public CompletableFuture<String> chatFallback(String msg, String userId, Throwable t) {
        log.warn("chat 熔断降级: userId={}, error={}", userId, t.getMessage());
        return CompletableFuture.completedFuture("AI 服务暂时不可用，请稍后重试");
    }

    /**
     * 多轮对话问答（生产主流程）— 带会话管理、查询改写、多路检索、SSE 流式响应。
     *
     * <h3>核心流程（7 步）</h3>
     * <pre>
     * POST /ai/multi-turn/chat {sessionId, userId, message}
     *   │
     *   ▼
     * Step 1 — 会话校验
     *   chatSessionService.requireActiveSessionUser(sessionId)
     *   验证 sessionId 属于当前登录用户；userId 不一致则抛异常
     *   │
     *   ▼
     * Step 2 — 查询改写（Query Rewrite）
     *   queryRewriteService.rewrite(sessionId, originalQuery)
     *   读取最近 6 条历史消息，调用 LLM 将代词/省略补全为自包含查询
     *   例："它有哪些依赖？" → "RagUnitService 有哪些依赖注入？"
     *   失败时降级为原始查询
     *   │
     *   ▼
     * Step 3 — 子查询生成（Sub-Query Generation）
     *   retrievalSubQueryService.generateSubQueries(rewrittenQuery, originalQuery)
     *   从同义词、相关概念、属性等角度生成最多 4 条补充查询
     *   提高向量召回覆盖率，失败时降级为只用主查询
     *   │
     *   ▼
     * Step 4 — 多路检索（Multi-Path Recall）
     *   ragRetrievalService.retrieveWithMultiPathRecall(rewrittenQuery, retrievalQueries, userId)
     *   ┌─ 路径A：主查询 → HierarchicalRetrievalStrategy（摘要→章节→叶子三层下钻）
     *   │          未命中 → FlatRetrievalStrategy（直接搜叶子节点）
     *   └─ 路径B：子查询并行召回（线程池并发）→ 合并去重
     *   两路合并 → Rerank 精排 → 阈值过滤 → KnowledgeTextBuilder 构建知识文本
     *   │
     *   ▼
     * Step 5 — 系统提示词组装
     *   buildMultiTurnSystemPrompt(userId, result)
     *   拼装：用户画像（Redis 长期记忆）+ 回答约束 + 检索到的知识文本
     *   │
     *   ▼
     * Step 6 — 引文（Citations）构建
     *   从 HierarchyHit 列表提取：文件名、MinIO URL、文档标题、章节标题、
     *   分段索引、标签、内容片段、相关度分数（百分制）
     *   序列化为 JSON
     *   │
     *   ▼
     * Step 7 — SSE 流式响应
     *   ① event: citations  → 引文列表 JSON（前端立即展示来源卡片）
     *   ② event: message    → LLM 逐 token 流式返回（前端打字机效果）
     *   使用 Flux.concat 保证 citations 先于 message 到达前端
     * </pre>
     *
     * @param request 多轮对话请求，包含 sessionId、userId、message
     * @return SSE 事件流，前端通过 EventSource 消费：
     *         <ul>
     *           <li>{@code event: citations} — 引文 JSON 数组，每个元素含 sourceName/minioUrl/docTitle/sectionTitle/chunkIndex/label/text/score</li>
     *           <li>{@code event: message} — LLM 生成的文本片段，逐 token 推送</li>
     *           <li>{@code event: error} — 错误提示（仅降级时）</li>
     *         </ul>
     * @see QueryRewriteService#rewrite 查询改写
     * @see RetrievalSubQueryService#generateSubQueries 子查询生成
     * @see RagRetrievalService#retrieveWithMultiPathRecall 多路检索
     * @see #buildMultiTurnSystemPrompt 系统提示词组装
     * @see #multiTurnChatFallback 熔断降级
     */
    public Flux<ServerSentEvent<String>> multiTurnChat(MultiTurnChatRequest request) {
        // ① 会话校验：验证 sessionId 属于当前登录用户，防止越权访问
        String userId = chatSessionService.requireActiveSessionUser(request.getSessionId());
        if (request.getUserId() != null && !request.getUserId().equals(userId)) {
            throw new IllegalArgumentException("会话用户与当前登录用户不一致");
        }
        String originalQuery = request.getMessage();

        // ② 查询改写：结合最近 6 条历史，将代词/省略补全为自包含查询
        //    例："它有哪些依赖？" → "RagUnitService 有哪些依赖注入？"
        String rewrittenQuery = queryRewriteService.rewrite(request.getSessionId(), originalQuery);
        log.info("多轮对话检索 - 原始查询: '{}', 改写后: '{}'", originalQuery, rewrittenQuery);

        // ③ 子查询生成：从同义词、相关概念等角度生成最多 4 条补充查询，提高向量召回覆盖率
        List<String> retrievalQueries = retrievalSubQueryService.generateSubQueries(rewrittenQuery, originalQuery);

        // ④ 多路检索：路径A(主查询层级→平铺) + 路径B(子查询并行召回) → 合并去重 → Rerank 精排
        RetrievalResult result = ragRetrievalService.retrieveWithMultiPathRecall(
                rewrittenQuery,
                retrievalQueries,
                userId
        );

        // ⑤ 组装系统提示词：用户画像(长期记忆) + 回答约束 + 检索到的知识文本
        String systemPrompt = buildMultiTurnSystemPrompt(userId, result);

        // ⑥ 构建引文：从检索命中中提取文件名、URL、标题、章节、相关度分数等元数据
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

        // ⑦ SSE 流式响应：先推送 citations 事件(前端展示来源卡片)，再逐 token 推送 LLM 文本

        // 本系统使用两个 SSE 事件类型：
        //   1. event: citations — 一次性推送引文列表，前端立即展示来源卡片
        //   2. event: message   — 逐 token 推送 LLM 生成的文本，前端实现打字机效果
        //
        // 为什么先推 citations？
        //   - 用户更关心"答案来自哪里"，引文卡片可以快速展示参考来源
        //   - LLM 生成需要时间，先推 citations 可以让用户立即看到检索结果
        //   - 使用 Flux.concat 保证 citations 事件一定在 message 事件之前到达

        // ── Step 7.1: 构建 citations 事件 ──
        // ServerSentEvent 是 Spring WebFlux 提供的 SSE 事件对象
        //   - event("citations"): 事件类型，前端通过 eventType 判断处理逻辑
        //   - data(citationsJson): 事件数据，JSON 格式的引文列表
        //
        // citationsJson 格式示例：
        // [
        //   {
        //     "filename": "RagUnitService.java",
        //     "title": "依赖注入",
        //     "label": "分段 3",
        //     "text": "@Autowired private final RagUnitRepository...",
        //     "score": 85
        //   },
        //   ...
        // ]
        ServerSentEvent<String> citationsEvent = ServerSentEvent.<String>builder()
                .event("citations")
                .data(citationsJson)
                .build();

        // Flux.just() 创建一个只包含单个元素的 Flux
        // citationsFlux 只会发射一个 citationsEvent，然后完成
        Flux<ServerSentEvent<String>> citationsFlux = Flux.just(citationsEvent);

        // ── Step 7.2: 构建 LLM 文本流 ──
        // deepchatClient 是基于 qwen-plus 模型的 ChatClient
        // 调用链说明：
        //   .prompt()                    — 创建一个新的 Prompt 请求
        //   .advisors(...)               — 添加 Advisor，用于自动管理聊天记忆
        //     - CONVERSATION_ID: 会话ID，用于从 Redis 获取历史消息
        //     - MessageChatMemoryAdvisor 会自动将历史消息注入到 Prompt 中
        //   .tools(dateTimeTools)        — 注册工具函数（日期时间查询等）
        //   .system(systemPrompt)        — 设置系统提示词（包含用户画像、回答约束、知识文本）
        //   .user(originalQuery)         — 设置用户消息（原始问题，非改写后的查询）
        //   .stream()                    — 流式调用 LLM，返回 ChatResponse 的 Flux
        //   .content()                   — 提取文本内容，过滤掉元数据，只保留 token
        //   .map(token -> ...)           — 将每个 token 包装为 SSE 事件
        //
        // 流式响应的优势：
        //   1. 用户体验好：逐 token 显示，减少等待时间
        //   2. 首字节时间短：不需要等 LLM 生成完整回答
        //   3. 可中断：用户可以在生成过程中停止
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

        // ── Step 7.3: 合并 SSE 流 ──
        // Flux.concat() 按顺序合并多个 Flux：
        //   1. 先发射 citationsFlux 的所有元素（1个 citations 事件）
        //   2. 再发射 textFlux 的所有元素（N个 message 事件）
        //
        // 这保证了 citations 事件一定在 message 事件之前到达前端
        // 前端接收到 citations 事件后，立即展示来源卡片
        // 随后逐个接收 message 事件，实现打字机效果
        //
        // 返回类型：Flux<ServerSentEvent<String>>
        // Spring WebFlux 会自动将其转换为 SSE 响应格式：
        //   event:citations
        //   data:{"filename":"...", "title":"...", ...}
        //
        //   event:message
        //   data:你
        //
        //   event:message
        //   data:好
        //   ...
        return Flux.concat(citationsFlux, textFlux);
    }

    /**
     * 多轮对话的熔断降级方法。
     * 当 LLM 调用失败率超过阈值时，Resilience4j 自动触发此方法，
     * 返回一个 SSE error 事件告知前端服务暂时不可用。
     *
     * @param request 原始请求（未使用，仅用于方法签名匹配）
     * @param t       触发降级的异常
     * @return 仅包含一个 error 事件的 SSE 流
     */
    public Flux<ServerSentEvent<String>> multiTurnChatFallback(MultiTurnChatRequest request, Throwable t) {
        log.warn("multiTurnChat 熔断降级: sessionId={}, error={}", request.getSessionId(), t.getMessage());
        ServerSentEvent<String> errorEvent = ServerSentEvent.<String>builder()
                .event("error")
                .data("AI 服务暂时不可用，请稍后重试")
                .build();
        return Flux.just(errorEvent);
    }

    /**
     * 构建单轮对话的系统提示词。
     *
     * <p>根据检索结果是否命中知识库，组装不同级别的提示词：</p>
     * <ul>
     *   <li><strong>未命中</strong>：仅返回基础角色设定（智能问答系统 + 工具使用说明）</li>
     *   <li><strong>已命中</strong>：在基础设定之上追加回答约束和参考资料：
     *       <ol>
     *         <li>必须优先依据参考资料回答，不能矛盾</li>
     *         <li>参考资料中出现的事实不得回答"未提供"</li>
     *         <li>不足时只能说明"引用中只看到..."，不编造</li>
     *         <li>尽量使用引用中的原词和结构</li>
     *       </ol>
     *       后附 {@link RetrievalResult#getKnowledgeText()} 检索到的知识文本
     *   </li>
     * </ul>
     *
     * @param result 单路径检索结果
     * @return 完整的系统提示词字符串
     */
    private String buildSingleTurnSystemPrompt(RetrievalResult result) {
        String systemPrompt = "你是一个智能问答系统。\n"
                + "你可以使用系统提供的工具来获取实时信息。\n"
                + "当问题涉及当前时间、日期等实时数据时，请调用工具。";
        if (!result.isHit()) {
            return systemPrompt;
        }
        // 命中知识库：追加回答约束 + 检索到的知识文本，引导 LLM 基于参考资料回答
        return systemPrompt
                + "\n\n【回答约束】\n"
                + "1. 必须优先依据【参考资料】回答，不能与引用内容相矛盾。\n"
                + "2. 如果【参考资料】中出现了用户问题相关实体或事实，不得回答“知识库未出现”“未提供相关内容”。\n"
                + "3. 如果引用内容不足以完整回答，只能说明“引用中只看到...”并列出已看到的信息，不要编造缺失部分。\n"
                + "4. 回答时尽量使用引用中的原词和结构。\n\n【参考资料】\n"
                + result.getKnowledgeText();
    }

    /**
     * 构建多轮对话的系统提示词。
     *
     * <p>在基础角色设定之上，按顺序拼装两层信息：</p>
     * <ol>
     *   <li><strong>用户画像（长期记忆）</strong>：
     *       从 {@link UserProfileService} 读取 Redis 中的用户特征
     *       （职业、技术栈、偏好、兴趣等），指示 LLM 根据用户特征调整回答风格。
     *       画像不存在时不注入。</li>
     *   <li><strong>知识库参考 + 回答约束</strong>：
     *       <ul>
     *         <li>检索命中时：追加 4 条回答约束 + {@link RetrievalResult#getKnowledgeText()}</li>
     *         <li>未命中时：仅追加"请提供专业、准确的回答"</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * @param userId 当前用户 ID，用于查询用户画像
     * @param result 多路检索结果
     * @return 完整的系统提示词字符串
     * @see UserProfileService#getProfile 获取用户画像
     */
    private String buildMultiTurnSystemPrompt(String userId, RetrievalResult result) {
        // 基础角色设定
        StringBuilder systemPrompt = new StringBuilder("你是一个智能问答助手。");

        // 注入用户画像(长期记忆)：从 Redis 读取职业、技术栈、偏好等，引导 LLM 个性化回答
        String userProfile = userProfileService.getProfile(userId);
        if (userProfile != null) {
            systemPrompt.append("\n\n【用户背景与偏好（长期记忆）】\n")
                    .append(userProfile)
                    .append("\n请根据上述用户特征调整你的回答风格和内容。");
        }

        // 检索命中：追加回答约束 + 知识文本；未命中：仅要求专业回答
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
}

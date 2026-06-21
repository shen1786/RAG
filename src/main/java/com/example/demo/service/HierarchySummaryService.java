package com.example.demo.service;

import com.example.demo.model.RagUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.example.demo.Config.HierarchyConfig;

/**
 * 层级摘要生成服务，为 RAG 三层摘要树的 Section 和 Document 节点生成标题与摘要。
 * <p>
 * 调用链路：{@code summaryChatClient → DashScopeChatModel(qwen-plus) → DashScopeApi → RestClient}
 * <p>
 * 核心流程：
 * <ol>
 *   <li>输入校验 — 空内容直接返回兜底摘要</li>
 *   <li>冷却检查 — 之前超时过则冷却期内直接使用规则摘要</li>
 *   <li>输入截断 — 超过 {@value #MAX_INPUT_CHARS} 字符截断，防止单次调用超出模型上下文窗口</li>
 *   <li>构建 Prompt — 强约束模型只输出 JSON: {@code {"title":"...","summary":"..."}}</li>
 *   <li>提交到线程池 — 核心 2、最大 8 线程，队列容量 100</li>
 *   <li>Future.get(timeout) — 等待结果，超时则 cancel 中断</li>
 *   <li>解析 JSON — ObjectMapper 反序列化为 {@link SummaryPayload}</li>
 *   <li>空值补全 — title 或 summary 为空时用规则兜底</li>
 *   <li>异常处理 — 超时进入冷却期，其余异常直接返回兜底</li>
 * </ol>
 * <p>
 * 容错机制：
 * <ul>
 *   <li>超时控制：{@code Future.get(timeout) + cancel(true)}，超时中断 LLM 调用</li>
 *   <li>冷却期：超时触发冷却（{@code summaryFailureCooldownSeconds}），冷却期内所有请求直接走兜底</li>
 *   <li>兜底策略：title = filename - Section N，summary = 内容前 240 字符</li>
 *   <li>线程池拒绝策略：{@code CallerRunsPolicy}，队列满时由调用线程执行（不丢任务）</li>
 * </ul>
 */
@Service
@Slf4j
public class HierarchySummaryService {

    /** 摘要输入最大字符数，超过则截断（防止单次调用超出模型上下文窗口） */
    private static final int MAX_INPUT_CHARS = 8000;
    /** 摘要输出最大 token 数 */
    private static final int MAX_OUTPUT_TOKENS = 512;

    /** LLM 聊天客户端，使用 qwen-plus 模型，无 ChatMemory、无 Tools */
    private final ChatClient summaryChatClient;
    private final ObjectMapper objectMapper;
    /** 冷却截止时间戳（epoch ms），当前时间小于此值时表示处于冷却期 */
    private final AtomicLong summaryFallbackUntilEpochMs = new AtomicLong(0L);
    /** 单次 LLM 调用超时秒数 */
    private final int summaryTimeoutSeconds;
    /** 超时后冷却持续秒数 */
    private final int summaryFailureCooldownSeconds;
    /** 摘要生成专用线程池：核心 2、最大 8、队列 100、守护线程、CallerRunsPolicy */
    private final ExecutorService summaryExecutor = new ThreadPoolExecutor(
            2, 8, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            runnable -> {
                Thread thread = new Thread(runnable, "hierarchy-summary");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    /**
     * 构造注入摘要所需的依赖。
     *
     * @param summaryChatClient  摘要专用 LLM 客户端（qwen-plus，无记忆无工具）
     * @param objectMapper       JSON 解析器，用于反序列化 LLM 返回的 JSON
     * @param hierarchyConfig    层级配置，提供超时秒数和冷却秒数
     */
    public HierarchySummaryService(@Qualifier("summaryChatClient") ChatClient summaryChatClient,
                                   ObjectMapper objectMapper,
                                   HierarchyConfig hierarchyConfig) {
        this.summaryChatClient = summaryChatClient;
        this.objectMapper = objectMapper;
        this.summaryTimeoutSeconds = Math.max(1, hierarchyConfig.getSummaryTimeoutSeconds());
        this.summaryFailureCooldownSeconds = Math.max(1, hierarchyConfig.getSummaryFailureCooldownSeconds());
    }

    /**
     * 为一组连续叶子 chunk 生成小节级标题与摘要。
     * <p>
     * 将所有叶子节点的 content 拼接后提交 LLM，生成 section 级别的标题和摘要。
     * 若 LLM 调用失败或超时，返回兜底标题 "{filename} - Section {index+1}" 和内容前 240 字符。
     *
     * @param filename     原始文件名，用于兜底标题
     * @param sectionIndex 小节序号（0-based），用于兜底标题
     * @param leaves       该小节下的连续叶子节点列表
     * @return 包含 title 和 summary 的摘要载荷
     */
    public SummaryPayload summarizeSection(String filename, int sectionIndex, List<RagUnit> leaves) {
        // section summary 的输入是一组连续 chunk，适合生成局部主题标题。
        StringBuilder content = new StringBuilder();
        for (RagUnit leaf : leaves) {
            if (leaf.getContent() != null && !leaf.getContent().isBlank()) {
                content.append(leaf.getContent().trim()).append("\n\n");
            }
        }

        String fallbackTitle = filename + " - Section " + (sectionIndex + 1);
        return summarize("请为一组连续文档片段生成小节标题与摘要。", content.toString(), fallbackTitle);
    }

    /**
     * 为整篇文档生成文档级标题与摘要。
     * <p>
     * 输入为该文档下所有 section 摘要节点，将各 section 的 title 和 content 拼接后
     * 提交 LLM 生成文档级摘要。成本低于直接对全文摘要，且减少超长上下文带来的不稳定。
     *
     * @param filename         原始文件名，用作兜底标题
     * @param sectionSummaries 该文档下所有 section 摘要节点
     * @return 包含 title 和 summary 的摘要载荷
     */
    public SummaryPayload summarizeDocument(String filename, List<RagUnit> sectionSummaries) {
        // 文档级摘要建立在 section summary 之上，成本更低，也能减少超长上下文带来的不稳定。
        StringJoiner joiner = new StringJoiner("\n\n");
        for (int i = 0; i < sectionSummaries.size(); i++) {
            RagUnit section = sectionSummaries.get(i);
            String title = section.getTitle() == null || section.getTitle().isBlank()
                    ? "Section " + (i + 1)
                    : section.getTitle().trim();
            joiner.add("[" + title + "]\n" + section.getContent());
        }

        return summarize("请为整篇文档生成文档级标题与摘要。", joiner.toString(), filename);
    }

    /**
     * 核心摘要生成方法。
     * <p>
     * 流程：空值校验 → 冷却检查 → 输入截断(8000字符) → 构建JSON约束Prompt →
     * 提交线程池 → Future.get(timeout)等待 → 解析JSON → 空值补全。
     * <p>
     * 异常时进入冷却期并返回兜底摘要，不中断整条索引链路。
     *
     * @param taskInstruction 任务指令，如"请为一组连续文档片段生成小节标题与摘要"
     * @param content         待摘要的文本内容
     * @param fallbackTitle   LLM 失败时使用的兜底标题
     * @return 摘要载荷（title + summary）
     */
    private SummaryPayload summarize(String taskInstruction, String content, String fallbackTitle) {
        String normalizedContent = content == null ? "" : content.trim();
        if (normalizedContent.isBlank()) {
            return fallback(normalizedContent, fallbackTitle);
        }
        if (isSummaryFallbackCoolingDown()) {
            log.warn("层级摘要服务处于冷却期，直接使用兜底标题={}", fallbackTitle);
            return fallback(normalizedContent, fallbackTitle);
        }

        // 输入截断：防止单次调用超出模型上下文窗口
        if (normalizedContent.length() > MAX_INPUT_CHARS) {
            log.info("摘要输入超过 {} 字符，截断: 原始长度={}", MAX_INPUT_CHARS, normalizedContent.length());
            normalizedContent = normalizedContent.substring(0, MAX_INPUT_CHARS);
        }

        // 这里强约束模型只返回 JSON，避免自然语言说明影响解析。
        String prompt = """
                %s
                你必须只输出 JSON，结构如下：
                {"title":"...","summary":"..."}
                要求：
                1. title 简洁明确，15 个字以内优先。
                2. summary 聚焦事实与主题，不要编造信息。
                3. 不要输出 Markdown，不要输出额外解释。

                文本内容：
                %s
                """.formatted(taskInstruction, normalizedContent);

        try {
            Future<String> summaryFuture = summaryExecutor.submit(() ->
                    summaryChatClient.prompt()
                            .user(prompt)
                            .advisors(a -> a.param("maxTokens", MAX_OUTPUT_TOKENS))
                            .call()
                            .content()
            );

            String response;
            try {
                response = summaryFuture.get(summaryTimeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                summaryFuture.cancel(true);
                throw e;
            }

            SummaryPayload payload = objectMapper.readValue(cleanJson(response), SummaryPayload.class);
            if (payload == null || payload.isBlank()) {
                return fallback(normalizedContent, fallbackTitle);
            }
            if (payload.getTitle() == null || payload.getTitle().isBlank()) {
                payload.setTitle(fallbackTitle);
            }
            if (payload.getSummary() == null || payload.getSummary().isBlank()) {
                payload.setSummary(extractFallbackSummary(normalizedContent));
            }
            return payload;
        } catch (Exception e) {
            // 摘要失败时不中断整条索引链路，而是退回规则摘要兜底。
            markSummaryFallbackCooldown(e);
            log.warn("生成层级摘要失败，使用兜底标题={}, timeout={}s", fallbackTitle, summaryTimeoutSeconds, e);
            return fallback(normalizedContent, fallbackTitle);
        }
    }

    private boolean isSummaryFallbackCoolingDown() {
        return System.currentTimeMillis() < summaryFallbackUntilEpochMs.get();
    }

    private void markSummaryFallbackCooldown(Exception exception) {
        Throwable rootCause = unwrap(exception);
        if (!(rootCause instanceof TimeoutException)) {
            return;
        }
        long until = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(summaryFailureCooldownSeconds);
        summaryFallbackUntilEpochMs.set(until);
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @PreDestroy
    void shutdownSummaryExecutor() {
        summaryExecutor.shutdownNow();
    }

    private SummaryPayload fallback(String content, String fallbackTitle) {
        return new SummaryPayload(fallbackTitle, extractFallbackSummary(content));
    }

    private String extractFallbackSummary(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240) + "...";
    }

    private String cleanJson(String response) {
        if (response == null) {
            return "{}";
        }
        String cleaned = response.trim();
        // 模型偶尔会包 markdown code fence，这里先清掉再交给 ObjectMapper。
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```json", "")
                    .replaceFirst("^```", "")
                    .replaceFirst("```$", "")
                    .trim();
        }
        return cleaned;
    }

    /**
     * LLM 摘要返回的结构化载荷。
     * <p>
     * 对应 Prompt 要求模型输出的 JSON 格式 {@code {"title":"...","summary":"..."}}。
     */
    @Data
    @AllArgsConstructor
    public static class SummaryPayload {
        private String title;
        private String summary;

        public boolean isBlank() {
            return (title == null || title.isBlank()) && (summary == null || summary.isBlank());
        }
    }
}

package com.example.demo.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
public class RetrievalSubQueryService {

    private static final int MAX_SUB_QUERIES = 4;

    private static final String SUB_QUERY_SYSTEM_PROMPT = """
            你是一个RAG检索查询扩展助手。你的唯一任务是：
            根据给定的主查询，生成适合知识库检索的多个简洁子查询，用于提升召回覆盖率。

            规则：
            1. 每行只输出一个检索子查询，不要编号，不要解释。
            2. 子查询必须围绕同一主题，从不同角度、别名、属性、上下游关系展开。
            3. 避免和主查询完全重复，也不要生成空泛问题。
            4. 最多输出4行。
            5. 不要回答问题，只输出检索查询。
            """;

    private final ChatClient chatClient;

    public RetrievalSubQueryService(@Qualifier("deepchatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 生成检索子查询，用于多路召回提升向量检索覆盖率。
     *
     * @param rewrittenQuery 改写后的主查询（来自 QueryRewriteService）
     * @param originalQuery  用户原始输入
     * @return 去重后的查询列表（最多 1 主查询 + 4 子查询 = 5 条）
     */
    public List<String> generateSubQueries(String rewrittenQuery, String originalQuery) {
        // ① 初始化去重集合，保证主查询排在首位（LinkedHashSet 保持插入顺序）
        Set<String> queries = new LinkedHashSet<>();
        addIfPresent(queries, rewrittenQuery);   // 改写后的查询作为主查询
        addIfPresent(queries, originalQuery);     // 原始查询作为补充（可能包含 LLM 改写丢失的信息）

        // ② 主查询为空则直接返回（原因：没有核心查询，生成子查询无意义）
        if (rewrittenQuery == null || rewrittenQuery.isBlank()) {
            return new ArrayList<>(queries);
        }

        try {
            // ③ 构建 Prompt：同时提供改写后查询和原始问题（原因：原始问题可能包含改写时丢失的细节）
            String userPrompt = String.format("""
                    【主查询】
                    %s

                    【用户原始问题】
                    %s

                    请生成最多4个用于知识库召回的检索子查询：""",
                    rewrittenQuery,
                    originalQuery == null ? "" : originalQuery);

            // ④ 调用 LLM 生成子查询（无状态调用，不带 Memory，原因：这是纯粹的文本生成任务）
            String content = chatClient.prompt()
                    .system(SUB_QUERY_SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .content();

            // ⑤ LLM 返回空则直接返回已有查询（原因：降级兜底，保证至少有主查询）
            if (content == null || content.isBlank()) {
                return new ArrayList<>(queries);
            }

            // ⑥ 逐行解析子查询（原因：LLM 按规则每行输出一个子查询）
            for (String line : content.split("\\r?\\n")) {
                // 限制总数 5 条（1 主查询 + 4 子查询，原因：过多会增加检索耗时和 Token 消耗）
                if (queries.size() >= MAX_SUB_QUERIES + 1) {
                    break;
                }
                addIfPresent(queries, sanitize(line));  // 清理 LLM 输出的编号、引号等杂质
            }
        } catch (Exception e) {
            // ⑦ 异常降级（原因：子查询是增强环节，失败不影响主查询检索）
            log.warn("生成检索子查询失败，降级使用主查询与原始问题: {}", e.getMessage());
        }

        return new ArrayList<>(queries);
    }

    /**
     * 去重添加查询（原因：LinkedHashSet 自动去重，避免重复查询浪费检索资源）
     */
    private void addIfPresent(Set<String> queries, String query) {
        if (query == null) {
            return;
        }
        String normalized = sanitize(query);
        if (!normalized.isBlank()) {
            queries.add(normalized);
        }
    }

    /**
     * 清理 LLM 输出杂质：
     * - 去除前缀编号如 "1." "- " "* "（原因：LLM 有时会自作主张加编号）
     * - 去除首尾引号（原因：引号会影响向量检索的文本匹配）
     */
    private String sanitize(String query) {
        return query == null ? "" : query
                .trim()
                .replaceFirst("^[-*0-9.\\s]+", "")   // 去除列表编号前缀
                .replaceAll("^[\"']|[\"']$", "");      // 去除首尾引号
    }
}

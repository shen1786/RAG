package com.example.demo.service;

import com.alibaba.cloud.ai.memory.redis.RedisChatMemoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 查询改写服务
 * <p>
 * 解决多轮对话中用户使用代词、省略语导致向量检索失败的问题。
 * 在进行 RAG 检索之前，先结合历史对话上下文，将用户的简短问题改写为一句独立完整的查询语句。
 * <p>
 * 例如：
 *   历史：用户问"RagUnitService的作用？"，AI回答了...
 *   当前问题："它有哪些依赖？"
 *   改写后："RagUnitService有哪些依赖注入？"
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class QueryRewriteService {

    @Qualifier("redisChatMemoryRepository")
    private final RedisChatMemoryRepository chatMemoryRepository;

    @Qualifier("deepchatClient")
    private final ChatClient chatClient;

    /** 最多取最近几条历史消息用于改写（控制 Token 消耗） */
    private static final int MAX_HISTORY_MESSAGES = 6;

    /** 改写用的 System Prompt */
    private static final String REWRITE_SYSTEM_PROMPT = """
            你是一个查询改写助手。你的唯一任务是：根据对话历史，将用户的最新问题改写为一句独立、完整、不含代词的查询语句。

            规则：
            1. 仅输出改写后的一句话，不要有任何解释、前缀或多余文字。
            2. 将代词（它、这个、那个、他们等）替换为对话中的具体实体名称。
            3. 将省略的主语、宾语补充完整。
            4. 如果用户的问题本身已经是独立完整的，直接原样输出即可。
            5. 不要回答问题，不要添加任何额外信息。
            """;

    /**
     * 根据会话历史改写用户的当前查询
     * <p>
     * 如果没有历史记录（第一轮对话），则直接返回原始查询，不做改写。
     *
     * @param sessionId      会话 ID
     * @param currentQuery   用户当前输入的原始查询
     * @return 改写后的完整查询语句（用于向量检索）
     */
    public String rewrite(String sessionId, String currentQuery) {
        try {
            // ① 从 Redis 获取会话历史（数据来源：Spring AI 的 RedisChatMemoryRepository，按 sessionId 存储对话消息）
            List<Message> history = chatMemoryRepository.findByConversationId(sessionId);

            // ② 首轮对话直接返回（原因：没有历史上下文，代词/省略无从推断，改写无意义）
            if (history == null || history.isEmpty()) {
                log.debug("会话 {} 无历史记录，跳过查询改写", sessionId);
                return currentQuery;
            }

            // ③ 只取最近 6 条历史（原因：控制 LLM 输入 Token 数量，避免成本过高和上下文稀释）
            int startIdx = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
            List<Message> recentHistory = history.subList(startIdx, history.size());

            // ④ 组装历史对话文本
            StringBuilder historyText = new StringBuilder();
            for (Message msg : recentHistory) {
                String role = msg.getMessageType() == MessageType.USER ? "用户" : "助手";
                String text = msg.getText();
                // 助手回复超 200 字截断（原因：回复内容对改写价值有限，节省 Token）
                if (msg.getMessageType() == MessageType.ASSISTANT && text != null && text.length() > 200) {
                    text = text.substring(0, 200) + "...";
                }
                if (text != null && !text.isBlank()) {
                    historyText.append(role).append("：").append(text).append("\n");
                }
            }

            // ⑤ 历史全空白则跳过（原因：可能只有工具调用记录，无有效文本上下文）
            if (historyText.isEmpty()) {
                return currentQuery;
            }

            // ⑥ 构建改写 Prompt（原因：给 LLM 明确的上下文和任务指令，引导它做代词替换和省略补全）
            String userPrompt = String.format("""
                    【对话历史】
                    %s
                    【用户最新问题】
                    %s

                    请将上述最新问题改写为一句独立完整的查询语句：""",
                    historyText.toString().trim(),
                    currentQuery);

            // ⑦ 调用 LLM 改写（不带 Memory Advisor，原因：这是一次独立调用，不需要注入额外记忆，避免污染改写结果）
            String rewritten = chatClient.prompt()
                    .system(REWRITE_SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .content();

            // ⑧ 校验改写结果（原因：LLM 可能返回空或异常内容，需兜底）
            if (rewritten == null || rewritten.isBlank()) {
                log.warn("查询改写返回空结果，使用原始查询");
                return currentQuery;
            }

            // ⑨ 去除引号包裹（原因：LLM 有时会在输出外层加引号，影响后续向量检索匹配）
            rewritten = rewritten.trim().replaceAll("^[\"']|[\"']$", "");

            log.info("查询改写完成: '{}' → '{}'", currentQuery, rewritten);
            return rewritten;

        } catch (Exception e) {
            // ⑩ 异常降级返回原始查询（原因：改写是增强环节，失败不应阻断主流程）
            log.warn("查询改写异常，降级使用原始查询: {}", e.getMessage());
            return currentQuery;
        }
    }
}

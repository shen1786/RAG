package com.example.demo.service;

import com.example.demo.model.RagUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.StringJoiner;

@Service
@Slf4j
public class HierarchySummaryService {

    private final ChatClient summaryChatClient;
    private final ObjectMapper objectMapper;

    public HierarchySummaryService(@Qualifier("summaryChatClient") ChatClient summaryChatClient,
                                   ObjectMapper objectMapper) {
        this.summaryChatClient = summaryChatClient;
        this.objectMapper = objectMapper;
    }

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

    private SummaryPayload summarize(String taskInstruction, String content, String fallbackTitle) {
        String normalizedContent = content == null ? "" : content.trim();
        if (normalizedContent.isBlank()) {
            return fallback(normalizedContent, fallbackTitle);
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
            String response = summaryChatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

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
            log.warn("Failed to generate hierarchy summary, using fallback title={}", fallbackTitle, e);
            return fallback(normalizedContent, fallbackTitle);
        }
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

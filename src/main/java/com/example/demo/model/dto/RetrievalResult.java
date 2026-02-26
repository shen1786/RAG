package com.example.demo.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * RAG 检索结果
 * 封装向量检索 + Rerank 后的最终结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalResult {

    /** 检索到的文档列表（已排序） */
    private List<Document> documents;

    /** 是否命中知识库（最高分 >= 阈值） */
    private boolean hit;

    /** 拼接后的知识文本（用于直接注入 prompt） */
    private String knowledgeText;

    /** 原始向量检索召回数量（rerank 前） */
    private int candidateCount;

    /** 最终返回数量（rerank 后） */
    private int finalCount;

    /** 检索耗时（毫秒） */
    private long durationMs;

    /**
     * 创建一个空结果（未命中知识库）
     */
    public static RetrievalResult empty(long durationMs) {
        return RetrievalResult.builder()
                .documents(List.of())
                .hit(false)
                .knowledgeText("")
                .candidateCount(0)
                .finalCount(0)
                .durationMs(durationMs)
                .build();
    }
}

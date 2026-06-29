package com.example.demo.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.document.Document;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalResult {

    private List<Document> documents;
    private boolean hit;
    private RetrievalMode retrievalMode;
    private String knowledgeText;
    private int candidateCount;
    private int finalCount;
    private long durationMs;
    private List<HierarchyHit> hierarchyHits;

    /**
     * 构建空检索结果（未命中知识库时使用）。
     *
     * <p>使用场景：向量检索无结果、SQL 关键词降级也无结果、或检索异常时返回。</p>
     * <p>设计原因：</p>
     * <ul>
     *   <li>统一返回类型，避免调用方判空（AiService 直接调 result.isHit() 分支）</li>
     *   <li>保留耗时统计（durationMs），便于监控检索性能</li>
     *   <li>hit=false 告诉下游走通用问答流程，不注入知识文本</li>
     *   <li>空集合而非 null，避免 NPE（防御性编程）</li>
     * </ul>
     *
     * @param durationMs 本次检索耗时（毫秒），即使未命中也记录，用于性能监控
     * @return 命中标志为 false 的空结果对象
     */
    public static RetrievalResult empty(long durationMs) {
        return RetrievalResult.builder()
                .documents(List.of())           // 空文档列表，非 null
                .hit(false)                     // 标记未命中，下游走通用问答
                .retrievalMode(RetrievalMode.FLAT_FALLBACK)  // 降级模式标识
                .knowledgeText("")              // 空知识文本，不注入 Prompt
                .candidateCount(0)              // 候选数 0
                .finalCount(0)                  // 最终返回数 0
                .durationMs(durationMs)         // 保留耗时，用于监控
                .hierarchyHits(List.of())       // 空层级命中列表
                .build();
    }
}

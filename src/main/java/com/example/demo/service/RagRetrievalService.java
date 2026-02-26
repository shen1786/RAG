package com.example.demo.service;

import com.alibaba.cloud.ai.document.DocumentWithScore;
import com.alibaba.cloud.ai.model.RerankModel;
import com.alibaba.cloud.ai.model.RerankRequest;
import com.alibaba.cloud.ai.model.RerankResponse;
import com.example.demo.model.dto.RetrievalResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 智能检索服务
 * 封装「向量粗召回 → Rerank 精排」两阶段检索流程，供多处复用
 */
@Service
@Slf4j
public class RagRetrievalService {

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private RerankModel rerankModel;

    // ==================== 可配置参数（application.yaml） ====================

    /** 粗召回阶段返回的候选数量（Rerank 前） */
    @Value("${rag.retrieval.candidate-top-k:15}")
    private int candidateTopK;

    /** 粗召回阶段的最低相似度阈值（过滤明显无关的结果） */
    @Value("${rag.retrieval.similarity-threshold:0.3}")
    private double similarityThreshold;

    /** Rerank 后最终返回的文档数量 */
    @Value("${rag.retrieval.final-top-k:5}")
    private int finalTopK;

    /** 判定"命中知识库"的最低 Rerank 分数 */
    @Value("${rag.retrieval.hit-score-threshold:0.35}")
    private double hitScoreThreshold;

    // ==================== 核心方法 ====================

    /**
     * 执行两阶段检索：向量粗召回 + Rerank 精排（使用默认参数）
     *
     * @param query 用户查询文本
     * @return 检索结果
     */
    public RetrievalResult retrieve(String query) {
        return retrieve(query, finalTopK, hitScoreThreshold);
    }

    /**
     * 执行两阶段检索（可自定义参数）
     * <p>
     * 流程：
     * 1. 使用向量相似度从知识库中粗召回 candidateTopK 条候选文档
     * 2. 调用 DashScope Rerank 模型对候选文档进行精排
     * 3. 取精排后的 top finalTopK 条作为最终结果
     * 4. 根据最高分判断是否"命中"知识库
//     *
     * param query        用户查询文本
     * @param topK         最终返回的文档数量
     * @param hitThreshold 命中阈值（Rerank 分数）
     * @return 检索结果
     */
    public RetrievalResult retrieve(String query, int topK, double hitThreshold) {
        long startTime = System.currentTimeMillis();

        try {
            // ========== 第一阶段：向量粗召回 ==========
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(candidateTopK)
                    .similarityThreshold(similarityThreshold)
                    .build();
//
            List<Document> candidates = vectorStore.similaritySearch(searchRequest);
            log.info("向量粗召回完成: query='{}', 候选数={}", truncate(query, 50), candidates.size());

            if (candidates.isEmpty()) {
                long duration = System.currentTimeMillis() - startTime;
                log.info("未召回任何候选文档, 耗时={}ms", duration);
                return RetrievalResult.empty(duration);
            }

            // ========== 第二阶段：Rerank 精排 ==========
            RerankResult rerankResult = rerank(query, candidates, topK);

            // ========== 判断是否命中 ==========
            boolean hit = rerankResult.topScore != null && rerankResult.topScore >= hitThreshold;

            String knowledgeText = rerankResult.documents.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n\n---\n\n"));

            long duration = System.currentTimeMillis() - startTime;

            log.info("检索完成: hit={}, 候选数={}, 精排后={}, 最高分={}, 耗时={}ms",
                    hit,
                    candidates.size(),
                    rerankResult.documents.size(),
                    rerankResult.topScore != null ? String.format("%.4f", rerankResult.topScore) : "N/A",
                    duration);

            return RetrievalResult.builder()
                    .documents(rerankResult.documents)
                    .hit(hit)
                    .knowledgeText(knowledgeText)
                    .candidateCount(candidates.size())
                    .finalCount(rerankResult.documents.size())
                    .durationMs(duration)
                    .build();

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("检索过程发生异常, 耗时={}ms", duration, e);
            return RetrievalResult.empty(duration);
        }
    }

    /**
     * 仅执行向量检索（不做 Rerank）
     * 适用于对延迟要求极高、可接受精度稍低的场景
     *
     * @param query 用户查询文本
     * @param topK  返回数量
     * @return 检索结果
     */
    public RetrievalResult retrieveWithoutRerank(String query, int topK) {
        long startTime = System.currentTimeMillis();

        try {
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(similarityThreshold)
                    .build();

            List<Document> docs = vectorStore.similaritySearch(searchRequest);

            boolean hit = !docs.isEmpty()
                    && docs.get(0).getScore() != null
                    && docs.get(0).getScore() > 0.7;

            String knowledgeText = docs.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n\n---\n\n"));

            long duration = System.currentTimeMillis() - startTime;

            return RetrievalResult.builder()
                    .documents(docs)
                    .hit(hit)
                    .knowledgeText(knowledgeText)
                    .candidateCount(docs.size())
                    .finalCount(docs.size())
                    .durationMs(duration)
                    .build();

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("向量检索异常", e);
            return RetrievalResult.empty(duration);
        }
    }

    // ==================== 内部方法 ====================

    /**
     * Rerank 结果的内部封装（因为 Document 没有 setScore，需要单独传递分数）
     */
    private static class RerankResult {
        final List<Document> documents;
        final Double topScore;

        RerankResult(List<Document> documents, Double topScore) {
            this.documents = documents;
            this.topScore = topScore;
        }
    }

    /**
     * 调用 Rerank 模型对候选文档进行精排
     *
     * @param query      用户查询
     * @param candidates 候选文档列表
     * @param topK       取前几条
     * @return 精排后的文档列表和最高分
     */
    private RerankResult rerank(String query, List<Document> candidates, int topK) {
        try {
            // 构建 Rerank 请求
            RerankRequest request = new RerankRequest(query, candidates);

            // 调用 Rerank 模型
            RerankResponse response = rerankModel.call(request);

            // 提取重排后的文档，取 top K
            List<DocumentWithScore> scoredDocs = response.getResults();
            List<Document> result = new ArrayList<>();
            Double topScore = null;

            for (int i = 0; i < Math.min(topK, scoredDocs.size()); i++) {
                DocumentWithScore dws = scoredDocs.get(i);
                result.add(dws.getOutput());
                if (i == 0) {
                    topScore = dws.getScore();
                }
            }

            log.info("Rerank 精排完成: 输入={}, 输出={}, 最高分={}",
                    candidates.size(), result.size(),
                    topScore != null ? String.format("%.4f", topScore) : "N/A");

            return new RerankResult(result, topScore);

        } catch (Exception e) {
            log.warn("Rerank 调用失败，回退为向量检索原始排序: {}", e.getMessage());
            // 降级：返回原始排序的前 topK 条，使用向量相似度的 score
            List<Document> fallback = candidates.stream()
                    .limit(topK)
                    .collect(Collectors.toList());
            Double fallbackScore = (!fallback.isEmpty() && fallback.get(0).getScore() != null)
                    ? fallback.get(0).getScore() : null;
            return new RerankResult(fallback, fallbackScore);
        }
    }

    /**
     * 截断字符串用于日志输出
     */
    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}

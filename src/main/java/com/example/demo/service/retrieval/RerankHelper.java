package com.example.demo.service.retrieval;

import com.alibaba.cloud.ai.document.DocumentWithScore;
import com.alibaba.cloud.ai.model.RerankModel;
import com.alibaba.cloud.ai.model.RerankRequest;
import com.alibaba.cloud.ai.model.RerankResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Rerank 工具类，封装 rerank 模型调用和熔断降级逻辑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RerankHelper {

    private final RerankModel rerankModel;

    /**
     * Rerank 精排 — 使用 gte-rerank-v2 模型对候选文档进行语义重排序。
     *
     * <h3>原理</h3>
     * 向量搜索（ANN/KNN）只能捕捉词向量空间的近似相似度，而 Rerank 模型是专门训练的
     * 交叉编码器（Cross-Encoder），会逐对计算 query-document 的精确语义相关度分数，
     * 通常比向量相似度更准确。因此采用「向量粗排 + Rerank 精排」的两阶段检索架构。
     *
     * <h3>流程</h3>
     * <pre>
     * query + candidates (向量搜索的粗排结果)
     *   │
     *   ▼
     * 构建 RerankRequest (query + document 列表)
     *   │
     *   ▼
     * rerankModel.call() — 调用 DashScope gte-rerank-v2 模型
     *   │                 — 对每个候选文档计算与 query 的语义相关度分数 (0~1)
     *   ▼
     * RerankResponse.getResults() — 获取排序后的文档+分数列表
     *   │                         — 模型已按分数降序排列
     *   ▼
     * 取前 topK 个结果，构建 ScoredDocumentsResult
     *   │
     *   ▼
     * 返回: documents(排序后) + scoreById(ID→分数映射) + topScore(最高分)
     * </pre>
     *
     * <h3>熔断保护</h3>
     * 使用 Resilience4j @CircuitBreaker 注解，当 DashScope rerank 服务故障时自动熔断：
     * - 熔断条件：50% 失败率（滑动窗口）
     * - 降级策略：回退到原始向量相似度排序，取前 topK 条
     * - 降级方法：{@link #rerankFallback}
     *
     * @param query      查询文本（主查询）
     * @param candidates 候选文档列表（向量搜索的粗排结果）
     * @param topK       最终保留的文档数量
     * @return 精排结果：包含排序后的文档列表、ID→分数映射、最高分
     * @see #rerankFallback 熔断降级方法
     */
    @CircuitBreaker(name = "dashscope-rerank", fallbackMethod = "rerankFallback")
    public ScoredDocumentsResult rerank(String query, List<Document> candidates, int topK) {
        // ── Step 1: 构建 Rerank 请求 ──
        // RerankRequest 包含查询文本和候选文档列表
        // 模型会对每个候选文档计算与 query 的语义相关度分数
        RerankRequest request = new RerankRequest(query, candidates);

        // ── Step 2: 调用 DashScope gte-rerank-v2 模型 ──
        // 模型是交叉编码器（Cross-Encoder），会逐对计算 query-document 的精确语义相关度
        // 返回的 RerankResponse 包含排序后的文档+分数列表，已按分数降序排列
        RerankResponse response = rerankModel.call(request);

        // ── Step 3: 解析 rerank 结果 ──
        // scoredDocs 是按语义相关度分数降序排列的文档列表
        // 每个 DocumentWithScore 包含：output(文档) + score(相关度分数, 0~1)
        List<DocumentWithScore> scoredDocs = response.getResults();
        List<Document> result = new ArrayList<>();
        Map<String, Double> scoreById = new HashMap<>();
        Double topScore = null;

        // ── Step 4: 取前 topK 个结果 ──
        // 遍历排序后的文档列表，取前 topK 个作为最终结果
        // 构建 scoreById 映射，用于后续展示每个文档的相关度分数
        // 记录 topScore（最高分），用于阈值判断
        for (int i = 0; i < Math.min(topK, scoredDocs.size()); i++) {
            DocumentWithScore dws = scoredDocs.get(i);
            Document output = dws.getOutput();
            result.add(output);
            scoreById.put(output.getId(), dws.getScore());
            if (i == 0) {
                topScore = dws.getScore(); // 记录最高分
            }
        }

        // ── Step 5: 返回精排结果 ──
        // ScoredDocumentsResult 是 record 类型，包含：
        //   - documents: 排序后的文档列表（已取前 topK）
        //   - scoreById: 文档ID → 相关度分数的映射（用于引文展示）
        //   - topScore: 最高分（用于阈值判断）
        return new ScoredDocumentsResult(result, scoreById, topScore);
    }

    /**
     * Rerank 熔断降级方法 — 当 DashScope rerank 服务故障时自动调用。
     *
     * <h3>降级策略</h3>
     * 当 gte-rerank-v2 模型调用失败（网络超时、服务不可用、熔断触发等）时，
     * 回退到原始向量相似度排序，取前 topK 条文档作为结果。
     * 虽然排序精度不如 Rerank，但保证了服务的可用性。
     *
     * @param query      查询文本（未使用，保持接口一致性）
     * @param candidates 候选文档列表（按向量相似度排序）
     * @param topK       最终保留的文档数量
     * @param t          触发降级的异常
     * @return 降级后的精排结果（使用向量相似度分数）
     */
    public ScoredDocumentsResult rerankFallback(String query, List<Document> candidates, int topK, Throwable t) {
        // 记录熔断降级日志，便于监控和排查问题
        log.warn("rerank 熔断降级，回退为原始排序: error={}", t.getMessage());

        // ── 降级处理：直接使用向量相似度排序 ──
        // candidates 已经按向量相似度分数降序排列（由向量搜索返回）
        // 取前 topK 个文档作为降级结果
        List<Document> fallback = candidates.stream()
                .limit(topK)
                .collect(Collectors.toList());

        // ── 构建分数映射 ──
        // 使用向量相似度分数（Document.getScore()）作为降级分数
        // 注意：向量相似度分数通常在 0~1 之间，但与 Rerank 分数的分布可能不同
        Map<String, Double> scoreById = new HashMap<>();
        for (Document doc : fallback) {
            scoreById.put(doc.getId(), doc.getScore());
        }

        // ── 记录降级最高分 ──
        // 用于后续的阈值判断（topScore >= hitThreshold）
        Double fallbackScore = (!fallback.isEmpty() && fallback.get(0).getScore() != null)
                ? fallback.get(0).getScore() : null;

        // ── 返回降级结果 ──
        // 返回格式与正常 rerank 结果一致，确保调用方无需区分降级场景
        return new ScoredDocumentsResult(fallback, scoreById, fallbackScore);
    }

    public record ScoredDocumentsResult(List<Document> documents, Map<String, Double> scoreById, Double topScore) {
    }
}

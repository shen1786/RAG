package com.example.demo.service;

import com.example.demo.model.dto.RetrievalResult;
import com.example.demo.repository.RagUnitQueryRepository;
import com.example.demo.service.retrieval.FlatRetrievalStrategy;
import com.example.demo.service.retrieval.HierarchicalRetrievalStrategy;
import com.example.demo.service.retrieval.KnowledgeTextBuilder;
import com.example.demo.service.retrieval.RerankHelper;
import com.example.demo.service.retrieval.UserFilterBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * RAG 检索编排服务 — 协调多路检索策略、候选收集、重排和降级控制。
 *
 * <h2>职责</h2>
 * 作为检索链路的<strong>核心调度器</strong>，本身不实现具体检索算法，而是：
 * <ul>
 *   <li>选择并编排检索策略（层级 → 平铺降级）</li>
 *   <li>协调多路并行召回（主查询 + 子查询并发）</li>
 *   <li>候选合并、去重、重排、阈值过滤</li>
 *   <li>触发降级：层级→平铺、向量→关键词、rerank→向量相似度</li>
 *   <li>构建最终知识文本和层级命中信息</li>
 * </ul>
 *
 * <h2>提供的三种检索模式</h2>
 * <pre>
 * ┌───────────────────────────────────────────────────────────────────┐
 * │ ① retrieve() — 单路径检索                                        │
 * │   主查询 → 层级策略(摘要→章节→叶子) → 未命中则平铺策略             │
 * │   用于：单轮对话、作为多路检索的主路径                              │
 * ├───────────────────────────────────────────────────────────────────┤
 * │ ② retrieveWithMultiPathRecall() — 多路召回（生产主流程）           │
 * │   主查询走 ① + 子查询并行召回 → 合并去重 → rerank → 阈值过滤       │
 * │   用于：多轮对话（AiService.multiTurnChat）                        │
 * ├───────────────────────────────────────────────────────────────────┤
 * │ ③ retrieveWithoutRerank() — 无重排快速检索                        │
 * │   直接在叶子向量库搜索，不做 rerank                                │
 * │   用于：对延迟敏感的场景                                          │
 * └───────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>多路召回核心流程（retrieveWithMultiPathRecall）</h2>
 * <pre>
 * 主查询 + 子查询列表
 *   │
 *   ▼
 * ① normalizeQueries() — 去重、去空、trim
 *   │
 *   ├──→ ② retrieve(主查询) — 路径A：层级→平铺
 *   │
 *   ├──→ ③ collectHybridCandidates(全部查询) — 路径B：并行召回
 *   │      ├─ collectFlatCandidates(): 线程池并发搜索 leafVectorStore
 *   │      │   每路取 candidateTopK(15) 候选，按文档ID去重
 *   │      └─ 向量无结果时降级 collectKeywordCandidates(): SQL 关键词搜索
 *   │
 *   ▼
 * ④ rerankHelper.rerank(主查询, 合并候选, topK) — 精排
 *   │
 *   ▼
 * ⑤ 阈值判断：topScore >= hitThreshold ?
 *   │   ├─ 是 → 使用多路结果
 *   │   └─ 否且主查询命中 → 回退主查询结果
 *   ▼
 * ⑥ knowledgeTextBuilder — 扩展上下文(前后邻居) + 格式化知识文本
 *   ▼
 * 返回 RetrievalResult
 * </pre>
 *
 * <h2>降级链路</h2>
 * <ol>
 *   <li>层级检索返回 null → 降级到平铺检索</li>
 *   <li>向量搜索无结果 → 降级到 SQL 关键词搜索</li>
 *   <li>多路 rerank 分数不达标 → 回退主查询结果</li>
 *   <li>Rerank 熔断（在 {@link RerankHelper} 中）→ 回退向量相似度排序</li>
 *   <li>任何异常 → 返回 {@link RetrievalResult#empty}</li>
 * </ol>
 *
 * @see HierarchicalRetrievalStrategy 层级检索（摘要→章节→叶子三层下钻）
 * @see FlatRetrievalStrategy 平铺检索（直接搜叶子节点）
 * @see RerankHelper 重排（gte-rerank-v2 模型）
 * @see KnowledgeTextBuilder 知识文本构建（扩展上下文窗口）
 */
@Service
@Slf4j
public class RagRetrievalService {

    /** 叶子节点向量库（Redis），存储细粒度文档分段的 embedding，用于平铺检索和子查询并行召回 */
    private final VectorStore leafVectorStore;
    /** 层级检索策略：摘要→章节→叶子三层下钻，使用 summaryVectorStore */
    private final HierarchicalRetrievalStrategy hierarchicalStrategy;
    /** 平铺检索策略：直接在 leafVectorStore 搜索，作为层级策略的降级方案 */
    private final FlatRetrievalStrategy flatStrategy;
    /** 重排助手：调用 gte-rerank-v2 模型对候选文档做语义精排 */
    private final RerankHelper rerankHelper;
    /** 知识文本构建器：扩展上下文窗口（前后邻居）并格式化为 Prompt 可用的文本 */
    private final KnowledgeTextBuilder knowledgeTextBuilder;
    /** RAG 单元查询仓库：提供按 ID 查询、关键词搜索等数据访问方法 */
    private final RagUnitQueryRepository ragUnitQueryRepository;
    /** RAG 单元服务：提供构建向量元数据等辅助方法 */
    private final RagUnitService ragUnitService;
    /** 用户过滤器构建器：为 Redis 向量搜索构建 per-user 的 filterExpression，实现数据隔离 */
    private final UserFilterBuilder userFilterBuilder;
    /** 子查询并行召回的线程池（使用 MVC 任务执行器） */
    private final Executor retrievalTaskExecutor;

    /** 每路子查询从向量库取的候选数量，默认 15 */
    @Value("${rag.retrieval.candidate-top-k:15}")
    private int candidateTopK;

    /** 向量相似度最低阈值，低于此分数的候选会被过滤，默认 0.3 */
    @Value("${rag.retrieval.similarity-threshold:0.3}")
    private double similarityThreshold;

    /** rerank 后保留的最终结果数量，默认 5 */
    @Value("${rag.retrieval.final-top-k:5}")
    private int finalTopK;

    /** rerank 分数最低命中阈值，低于此分数视为未命中，默认 0.35 */
    @Value("${rag.retrieval.hit-score-threshold:0.35}")
    private double hitScoreThreshold;

    public RagRetrievalService(@Qualifier("leafVectorStore") VectorStore leafVectorStore,
                               HierarchicalRetrievalStrategy hierarchicalStrategy,
                               FlatRetrievalStrategy flatStrategy,
                               RerankHelper rerankHelper,
                               KnowledgeTextBuilder knowledgeTextBuilder,
                               RagUnitQueryRepository ragUnitQueryRepository,
                               RagUnitService ragUnitService,
                               UserFilterBuilder userFilterBuilder,
                               @Qualifier("mvcTaskExecutor") Executor retrievalTaskExecutor) {
        this.leafVectorStore = leafVectorStore;
        this.hierarchicalStrategy = hierarchicalStrategy;
        this.flatStrategy = flatStrategy;
        this.rerankHelper = rerankHelper;
        this.knowledgeTextBuilder = knowledgeTextBuilder;
        this.ragUnitQueryRepository = ragUnitQueryRepository;
        this.ragUnitService = ragUnitService;
        this.userFilterBuilder = userFilterBuilder;
        this.retrievalTaskExecutor = retrievalTaskExecutor;
    }

    // ────── 公开 API ──────

    /**
     * 单路径检索（无用户隔离，使用默认参数）。
     *
     * @param query 用户查询文本
     * @return 检索结果（含文档列表、知识文本、命中标志）
     * @see #retrieve(String, String, int, double) 完整参数版本
     */
    public RetrievalResult retrieve(String query) {
        return retrieve(query, null, finalTopK, hitScoreThreshold);
    }

    /**
     * 单路径检索（带用户隔离，使用默认参数）。
     *
     * @param query  用户查询文本
     * @param userId 用户 ID，用于 per-user 向量过滤
     * @return 检索结果
     */
    public RetrievalResult retrieve(String query, String userId) {
        return retrieve(query, userId, finalTopK, hitScoreThreshold);
    }

    /**
     * 单路径检索（无用户隔离，自定义 topK 和阈值）。
     *
     * @param query        用户查询文本
     * @param topK         最终返回的文档数量
     * @param hitThreshold 命中阈值（暂未使用，保留用于扩展）
     * @return 检索结果
     */
    public RetrievalResult retrieve(String query, int topK, double hitThreshold) {
        return retrieve(query, null, topK, hitScoreThreshold);
    }

    /**
     * 单路径检索（完整参数版本）— 层级优先，平铺降级。
     *
     * <h3>流程</h3>
     * <pre>
     * query
     *   │
     *   ▼
     * hierarchicalStrategy.retrieve()  — 摘要→章节→叶子三层下钻
     *   │
     *   ├─ 返回非 null → 直接返回层级结果
     *   └─ 返回 null（未命中）→ 降级
     *         │
     *         ▼
     *       flatStrategy.retrieve()    — 直接在叶子向量库搜索
     *         │
         *         ▼
     *       返回平铺结果
     * </pre>
     *
     * @param query        用户查询文本
     * @param userId       用户 ID（null 表示不做用户隔离）
     * @param topK         最终返回的文档数量
     * @param hitThreshold rerank 分数最低命中阈值
     * @return 检索结果；异常时返回 {@link RetrievalResult#empty}
     * @see HierarchicalRetrievalStrategy#retrieve 层级策略
     * @see FlatRetrievalStrategy#retrieve 平铺策略
     */
    public RetrievalResult retrieve(String query, String userId, int topK, double hitThreshold) {
        // 记录检索开始时间，用于计算总耗时
        long startTime = System.currentTimeMillis();
        try {
            // ── 路径1: 层级检索策略 (HierarchicalRetrievalStrategy) ──
            // 采用三层摘要树逐层下钻：DOC_SUMMARY → SECTION_SUMMARY → LEAF
            // 1. 在 summaryVectorStore 搜索候选摘要节点
            // 2. 将命中的文档摘要展开为章节摘要，做 Rerank 筛选
            // 3. 将命中的章节摘要展开为叶子节点，再次 Rerank 得到最终结果
            // 4. 最终叶子分数必须达到 hitThreshold，否则返回 null 触发降级
            RetrievalResult hierarchical = hierarchicalStrategy.retrieve(query, userId, topK, hitThreshold, startTime);

            // 如果层级检索命中，直接返回结果
            if (hierarchical != null) {
                return hierarchical;
            }

            // ── 路径2: 平铺检索策略 (FlatRetrievalStrategy) — 降级方案 ──
            // 当层级检索未命中时，降级到直接在 leafVectorStore 搜索
            // 1. 在叶子向量库进行向量搜索 (topK=15)
            // 2. 对候选文档进行 Rerank 精排
            // 3. 判断 topScore >= hitThreshold
            // 4. 构建知识文本和 HierarchyHit 列表
            return flatStrategy.retrieve(query, userId, topK, hitThreshold, startTime);
        } catch (Exception e) {
            // 异常降级：记录错误日志，返回空结果，确保检索流程不会崩溃
            long duration = System.currentTimeMillis() - startTime;
            log.error("检索过程发生异常, 耗时={}ms", duration, e);
            return RetrievalResult.empty(duration);
        }
    }

    /**
     * 多路召回检索（使用默认 topK 和阈值）。
     *
     * @param primaryQuery  主查询（已改写后的自包含查询）
     * @param recallQueries 子查询列表（由 RetrievalSubQueryService 生成的多角度补充查询）
     * @param userId        用户 ID，用于 per-user 向量过滤
     * @return 检索结果，含 rerank 后的文档、知识文本、层级命中信息
     * @see #retrieveWithMultiPathRecall(String, List, String, int, double) 完整参数版本
     */
    public RetrievalResult retrieveWithMultiPathRecall(String primaryQuery, List<String> recallQueries, String userId) {
        return retrieveWithMultiPathRecall(primaryQuery, recallQueries, userId, finalTopK, hitScoreThreshold);
    }

    /**
     * 多路召回检索（完整参数版本）— 生产环境主流程，由 {@link AiService#multiTurnChat} 调用。
     *
     * <h3>核心流程</h3>
     * <pre>
     * primaryQuery + recallQueries（子查询列表）
     *   │
     *   ▼
     * ① normalizeQueries — 主查询+子查询去重、去空、trim
     *   │
     *   ├──────────────────────────────────────────────────┐
     *   ▼                                                  ▼
     * ② retrieve(主查询) — 路径A               ③ collectHybridCandidates(全部查询) — 路径B
     *   层级→平铺                                  并行向量搜索 leafVectorStore
     *   返回 primaryResult                         每路取 candidateTopK(15)
     *                                              按文档ID去重
     *                                              向量无结果 → 降级SQL关键词搜索
     *   │                                                  │
     *   └──────────────────────┬───────────────────────────┘
     *                          ▼
     * ④ rerankHelper.rerank(主查询, 合并候选, topK)
     *   │
     *   ▼
     * ⑤ 阈值判断
     *   ├─ topScore >= hitThreshold → 使用多路结果
     *   └─ 未达标 且 primaryResult.isHit() → 回退主查询结果
     *   │
     *   ▼
     * ⑥ knowledgeTextBuilder.buildExpandedKnowledgeText()
     *   展开上下文窗口（前1后2邻居），格式化知识文本
     *   │
     *   ▼
     * ⑦ 构建 HierarchyHit 列表（含文件名、MinIO URL、分段索引、分数等）
     *   │
     *   ▼
     * 返回 RetrievalResult
     * </pre>
     *
     * @param primaryQuery  主查询（已改写后的自包含查询）
     * @param recallQueries 子查询列表（多角度补充查询，用于提高召回覆盖率）
     * @param userId        用户 ID，用于 per-user 向量过滤
     * @param topK          rerank 后保留的最终文档数量
     * @param hitThreshold  rerank 分数最低命中阈值
     * @return 检索结果；异常时返回 {@link RetrievalResult#empty}
     * @see #retrieve(String, String, int, double) 单路径检索（路径A）
     * @see #collectHybridCandidates 并行候选收集（路径B）
     * @see RerankHelper#rerank 重排
     * @see KnowledgeTextBuilder#buildExpandedKnowledgeText 知识文本构建
     */
    public RetrievalResult retrieveWithMultiPathRecall(String primaryQuery,
                                                       List<String> recallQueries,
                                                       String userId,
                                                       int topK,
                                                       double hitThreshold) {
        // ── Step 0: 记录开始时间，用于计算总耗时 ──
        long startTime = System.currentTimeMillis();

        // ── Step 1: 查询归一化 ──
        // 将主查询和子查询列表合并，去重、去空、trim，生成最终的查询集合
        // 使用 LinkedHashSet 保持插入顺序并自动去重
        List<String> normalizedQueries = normalizeQueries(primaryQuery, recallQueries);

        try {
            // ── Step 1.1: 空查询保护 ──
            // 如果所有查询都为空或空白，直接返回空结果，避免无意义的检索操作
            if (normalizedQueries.isEmpty()) {
                return RetrievalResult.empty(System.currentTimeMillis() - startTime);
            }

            // ── Step 2: 路径A - 主查询单路径检索 ──
            // 使用第一个查询（即主查询）进行单路径检索：层级策略(摘要→章节→叶子) → 平铺降级
            // 这是检索的主路径，优先使用层级检索获取更精准的结果
            RetrievalResult primaryResult = retrieve(normalizedQueries.get(0), userId, topK, hitThreshold);

            // ── Step 2.1: 单查询快速返回 ──
            // 如果只有一个查询（没有子查询），直接返回主查询结果，无需多路合并
            if (normalizedQueries.size() == 1) {
                return primaryResult;
            }

            // ── Step 3: 路径B - 多路并行召回 ──
            // 并发执行所有查询的向量搜索，每路取 candidateTopK(15) 个候选
            // 合并后按文档ID去重，避免重复文档影响 rerank 结果
            // 如果向量搜索无结果，会自动降级到 SQL 关键词搜索
            List<Document> mergedCandidates = collectHybridCandidates(normalizedQueries, userId);

            // ── Step 3.1: 候选为空的降级处理 ──
            if (mergedCandidates.isEmpty()) {
                long duration = System.currentTimeMillis() - startTime;
                log.info("多路召回未召回任何候选文档, queries={}, 耗时={}ms", normalizedQueries.size(), duration);
                // 如果主查询有结果，回退到主查询结果
                if (!primaryResult.getDocuments().isEmpty()) {
                    return primaryResult;
                }
                // 否则返回空结果
                return RetrievalResult.empty(duration);
            }

            // ── Step 4: Rerank 精排 ──
            // 使用 gte-rerank-v2 模型对合并后的候选文档进行语义重排序
            // 以主查询作为查询文本，rerank 模型会计算每个候选文档与查询的语义相关度分数
            // 返回排序后的文档列表、分数映射和最高分
            RerankHelper.ScoredDocumentsResult rerankResult = rerankHelper.rerank(normalizedQueries.get(0), mergedCandidates, topK);

            // ── Step 5: 阈值判断与降级决策 ──
            // 判断 rerank 后的最高分是否达到命中阈值 (hitThreshold)
            // 如果未达到阈值，说明多路召回的结果质量不够高
            boolean hit = rerankResult.topScore() != null && rerankResult.topScore() >= hitThreshold;

            // ── Step 5.1: 分数不达标时的降级策略 ──
            // 如果多路结果未达标，但主查询结果已命中，回退到主查询结果
            // 这保证了即使多路召回效果不佳，仍能提供主查询的高质量结果
            if (!hit && primaryResult.isHit()) {
                log.info("多路召回结果未达命中阈值，回退到主查询检索结果: primaryQuery={}", normalizedQueries.get(0));
                return primaryResult;
            }

            // ── Step 5.2: 记录检索完成日志 ──
            long duration = System.currentTimeMillis() - startTime;
            log.info("多路召回完成: queries={}, mergedCandidates={}, final={}, topScore={}, 耗时={}ms",
                    normalizedQueries.size(), mergedCandidates.size(), rerankResult.documents().size(),
                    rerankResult.topScore() != null ? String.format("%.4f", rerankResult.topScore()) : "N/A", duration);

            // ── Step 6: 构建知识文本 ──
            // 从 rerank 后的文档中提取叶子节点ID，查询完整的 RagUnit 实体
            // 使用 KnowledgeTextBuilder 扩展上下文窗口（前1后2个邻居），构建格式化的知识文本
            // 知识文本会注入到 LLM 的 System Prompt 中，作为回答的参考依据
            List<String> leafIds = knowledgeTextBuilder.extractIds(rerankResult.documents());
            List<com.example.demo.model.RagUnit> leafUnits = ragUnitQueryRepository.selectByIds(leafIds);
            String knowledgeText = knowledgeTextBuilder.buildExpandedKnowledgeText(rerankResult.documents(), leafUnits);

            // ── Step 7: 构建层级命中信息 ──
            // 将 RagUnit 列表转换为 Map，便于快速查找
            // 遍历 rerank 结果，构建 HierarchyHit 列表，包含：
            //   - sourceId: 原始文件ID，用于追溯文档来源
            //   - leafUnitId: 叶子节点ID，用于定位具体分段
            //   - leafChunkIndex: 分段索引，用于标识文档中的位置
            //   - leafScore: Rerank 分数，用于展示相关度
            //   - content: 文本内容片段，用于前端展示
            //   - filename: 文件名，用于引文展示
            //   - minioUrl: MinIO 文件URL，用于PDF预览
            Map<String, com.example.demo.model.RagUnit> leafUnitMap = RagUnitQueryRepository.toUnitMap(leafUnits);
            List<com.example.demo.model.dto.HierarchyHit> hierarchyHits = new ArrayList<>();
            for (Document doc : rerankResult.documents()) {
                com.example.demo.model.RagUnit leafUnit = leafUnitMap.get(doc.getId());
                // 跳过未找到对应 RagUnit 的文档（理论上不应发生）
                if (leafUnit == null) continue;
                hierarchyHits.add(com.example.demo.model.dto.HierarchyHit.builder()
                        .sourceId(leafUnit.getSourceId())      // 原始文件ID
                        .leafUnitId(leafUnit.getId())           // 叶子节点ID
                        .leafChunkIndex(leafUnit.getChunkIndex()) // 分段索引
                        .leafScore(rerankResult.scoreById().get(leafUnit.getId())) // Rerank 分数
                        .content(leafUnit.getContent())         // 文本内容
                        .filename(leafUnit.getFilename())       // 文件名
                        .minioUrl(leafUnit.getMinioUrl())       // MinIO URL
                        .build());
            }

            // ── Step 8: 构建并返回最终结果 ──
            // 封装 RetrievalResult，包含：
            //   - documents: rerank 后的文档列表（已排序）
            //   - hit: 是否命中知识库（基于阈值判断）
            //   - retrievalMode: 检索模式（此处为平铺降级，因为多路召回本质上是平铺检索）
            //   - knowledgeText: 格式化的知识文本（注入 Prompt）
            //   - candidateCount: 候选文档总数（rerank 前）
            //   - finalCount: 最终保留数（rerank 后）
            //   - durationMs: 检索总耗时
            //   - hierarchyHits: 层级命中信息列表（用于引文展示）
            return com.example.demo.model.dto.RetrievalResult.builder()
                    .documents(rerankResult.documents())        // rerank 后的文档列表
                    .hit(hit)                                   // 是否命中
                    .retrievalMode(com.example.demo.model.dto.RetrievalMode.FLAT_FALLBACK) // 检索模式
                    .knowledgeText(knowledgeText)               // 知识文本
                    .candidateCount(mergedCandidates.size())    // 候选数
                    .finalCount(rerankResult.documents().size()) // 最终数
                    .durationMs(duration)                       // 耗时
                    .hierarchyHits(hierarchyHits)               // 层级命中
                    .build();
        } catch (Exception e) {
            // ── 异常降级处理 ──
            // 捕获所有异常，记录错误日志，返回空结果
            // 确保检索过程中的任何异常都不会导致整个问答流程崩溃
            long duration = System.currentTimeMillis() - startTime;
            log.error("多路召回过程发生异常, 耗时={}ms", duration, e);
            return RetrievalResult.empty(duration);
        }
    }

    /**
     * 无重排的快速向量检索 — 直接在叶子向量库搜索，跳过 rerank 步骤。
     *
     * <p>适用于对延迟敏感、不需要精排的场景。命中判断基于向量相似度分数 > 0.7。</p>
     *
     * @param query 用户查询文本
     * @param topK  返回的文档数量
     * @return 检索结果（hierarchyHits 为空列表，因为没有层级下钻）
     */
    public RetrievalResult retrieveWithoutRerank(String query, int topK) {
        long startTime = System.currentTimeMillis();
        try {
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(similarityThreshold)
                    .build();

            List<Document> docs = leafVectorStore.similaritySearch(searchRequest);
            boolean hit = !docs.isEmpty()
                    && docs.get(0).getScore() != null
                    && docs.get(0).getScore() > 0.7;

            List<com.example.demo.model.RagUnit> leafUnits = ragUnitQueryRepository.selectByIds(knowledgeTextBuilder.extractIds(docs));
            String knowledgeText = knowledgeTextBuilder.buildExpandedKnowledgeText(docs, leafUnits);
            long duration = System.currentTimeMillis() - startTime;

            return RetrievalResult.builder()
                    .documents(docs)
                    .hit(hit)
                    .retrievalMode(com.example.demo.model.dto.RetrievalMode.FLAT_FALLBACK)
                    .knowledgeText(knowledgeText)
                    .candidateCount(docs.size())
                    .finalCount(docs.size())
                    .durationMs(duration)
                    .hierarchyHits(List.of())
                    .build();
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("向量检索异常", e);
            return RetrievalResult.empty(duration);
        }
    }

    // ────── 多路召回内部方法 ──────

    /**
     * 混合候选收集 — 先尝试向量召回，失败则降级到关键词召回。
     *
     * <h3>降级逻辑</h3>
     * <pre>
     * queries (主查询 + 子查询列表)
     *   │
     *   ▼
     * collectFlatCandidates() — 向量召回
     *   │
     *   ├─ 有结果 → 直接返回向量候选
     *   └─ 无结果 → 降级
     *         │
     *         ▼
     *       collectKeywordCandidates() — SQL 关键词召回
     *         │
     *         ▼
     *       返回关键词候选
     * </pre>
     *
     * @param queries 查询列表（归一化后的主查询+子查询）
     * @param userId  用户 ID，用于数据隔离
     * @return 合并去重后的候选文档列表
     */
    private List<Document> collectHybridCandidates(List<String> queries, String userId) {
        // ── 优先尝试向量召回 ──
        // 并发执行所有查询的向量搜索，每路取 candidateTopK(15) 个候选，按文档ID去重
        List<Document> vectorCandidates = collectFlatCandidates(queries, userId);
        if (!vectorCandidates.isEmpty()) {
            return vectorCandidates;
        }

        // ── 降级到关键词召回 ──
        // 当向量召回无结果时（可能是 embedding 服务异常或向量库为空），
        // 使用 SQL LIKE 关键词搜索作为降级方案
        List<Document> keywordCandidates = collectKeywordCandidates(queries, userId);
        if (!keywordCandidates.isEmpty()) {
            log.info("向量召回为空，已回退到关键词召回: queries={}, candidates={}", queries.size(), keywordCandidates.size());
        }
        return keywordCandidates;
    }

    /**
     * 并行向量召回 — 使用线程池并发执行多个查询的向量搜索。
     *
     * <h3>流程</h3>
     * <pre>
     * queries (主查询 + 子查询列表)
     *   │
     *   ▼
     * 对每个查询创建 CompletableFuture，提交到 retrievalTaskExecutor 线程池
     *   │
     *   ├─ query1 → searchLeafCandidates() → List<Document> (15个候选)
     *   ├─ query2 → searchLeafCandidates() → List<Document> (15个候选)
     *   ├─ query3 → searchLeafCandidates() → List<Document> (15个候选)
     *   └─ ...
     *   │
     *   ▼
     * CompletableFuture.join() 等待所有任务完成
     *   │
     *   ▼
     * 按文档ID去重（LinkedHashMap 保持插入顺序）
     *   │
     *   ▼
     * 返回合并去重后的候选列表
     * </pre>
     *
     * @param queries 查询列表
     * @param userId  用户 ID
     * @return 合并去重后的候选文档列表
     */
    private List<Document> collectFlatCandidates(List<String> queries, String userId) {
        // ── Step 1: 并发提交所有查询任务 ──
        // 为每个查询创建一个 CompletableFuture，使用 supplyAsync 异步执行
        // retrievalTaskExecutor 是 MVC 任务执行器，复用 Web 服务器线程池
        // exceptionally 捕获单个查询的异常，返回空列表，不影响其他查询
        List<CompletableFuture<List<Document>>> futures = queries.stream()
                .map(query -> CompletableFuture.supplyAsync(() -> searchLeafCandidates(query, userId), retrievalTaskExecutor)
                        .exceptionally(ex -> {
                            log.warn("子查询召回失败，跳过该路查询: query={}, error={}", query, ex.getMessage());
                            return List.of();
                        }))
                .toList();

        // ── Step 2: 等待所有任务完成并合并结果 ──
        // futures.stream().map(CompletableFuture::join) 会阻塞等待每个任务完成
        // join() 不会抛出受检异常，因为我们已经在 exceptionally 中处理了
        Map<String, Document> deduped = new LinkedHashMap<>();
        for (List<Document> candidates : futures.stream().map(CompletableFuture::join).toList()) {
            for (Document candidate : candidates) {
                // ── Step 3: 按文档ID去重 ──
                // 使用文档ID作为去重键，如果ID为空则使用文本内容
                // LinkedHashMap 保持插入顺序，确保第一次出现的文档被保留
                String dedupeKey = candidate.getId() != null ? candidate.getId() : candidate.getText();
                if (dedupeKey == null || dedupeKey.isBlank()) {
                    continue;
                }
                // putIfAbsent 确保同一个ID只保留第一个出现的文档
                deduped.putIfAbsent(dedupeKey, candidate);
            }
        }
        return new ArrayList<>(deduped.values());
    }

    /**
     * 单次向量搜索 — 在叶子向量库中搜索与查询最相似的文档。
     *
     * @param query  查询文本
     * @param userId 用户 ID（用于构建 per-user 过滤表达式）
     * @return 候选文档列表（最多 candidateTopK 个）
     */
    private List<Document> searchLeafCandidates(String query, String userId) {
        // ── 构建向量搜索请求 ──
        // query: 查询文本，会被 embedding 模型转换为向量
        // topK: 每路查询返回的候选数量，默认 15
        // similarityThreshold: 向量相似度最低阈值，默认 0.3，低于此分数的文档会被过滤
        // filterExpression: per-user 过滤表达式，格式为 "user_id == 'xxx'"
        //                   实现用户数据隔离，确保用户只能搜索到自己的文档
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(candidateTopK)
                .similarityThreshold(similarityThreshold)
                .filterExpression(userFilterBuilder.build(userId))
                .build();

        // ── 执行向量搜索 ──
        // leafVectorStore 是 Redis VectorStore，存储细粒度文档分段(叶子节点)的 embedding
        // similaritySearch 会：
        //   1. 将 query 文本转换为向量（通过 DashScope embedding 模型）
        //   2. 在 Redis 中执行 KNN 搜索，找到最相似的 topK 个文档
        //   3. 应用 filterExpression 过滤非当前用户的文档
        //   4. 返回带分数的文档列表
        return leafVectorStore.similaritySearch(searchRequest);
    }

    /**
     * SQL 关键词召回 — 当向量召回无结果时的降级方案。
     *
     * <h3>适用场景</h3>
     * <ul>
     *   <li>DashScope embedding 服务不可用</li>
     *   <li>向量索引为空（文档未完成向量化）</li>
     *   <li>向量搜索返回空结果（查询文本无法有效 embedding）</li>
     * </ul>
     *
     * <h3>实现原理</h3>
     * <p>使用 MySQL LIKE 查询进行关键词匹配，虽然精度不如向量搜索，
     * 但能保证在向量服务故障时仍能提供基本的检索能力。</p>
     *
     * <h3>关键词预处理</h3>
     * <p>调用 {@link #normalizeKeywordQuery} 对查询文本进行清洗：</p>
     * <ul>
     *   <li>去除常见问句前缀：「请问」「介绍一下」「什么是」等</li>
     *   <li>去除常见问句后缀：「是什么」「什么意思」「吗」「呢」等</li>
     *   <li>去除标点符号，保留核心关键词</li>
     * </ul>
     *
     * @param queries 查询列表（归一化后的主查询+子查询）
     * @param userId  用户 ID，用于数据隔离
     * @return 关键词匹配的候选文档列表
     */
    private List<Document> collectKeywordCandidates(List<String> queries, String userId) {
        // 使用 LinkedHashMap 保持插入顺序，按文档ID去重
        Map<String, Document> deduped = new LinkedHashMap<>();

        for (String query : queries) {
            // ── Step 1: 关键词预处理 ──
            // 去除问句前缀/后缀和标点符号，提取核心关键词
            // 例如："请问RagUnitService有哪些依赖注入？" → "RagUnitService有哪些依赖注入"
            String keyword = normalizeKeywordQuery(query);
            if (keyword.isBlank()) {
                continue;
            }

            // ── Step 2: SQL 关键词搜索 ──
            // 使用 MySQL LIKE '%keyword%' 查询匹配内容包含关键词的叶子节点
            // searchLeafUnitsByKeyword 会：
            //   1. 按 userId 过滤，确保数据隔离
            //   2. 使用 LIKE 模糊匹配 content 字段
            //   3. 返回最多 candidateTopK 个结果
            for (com.example.demo.model.RagUnit unit : ragUnitQueryRepository.searchLeafUnitsByKeyword(keyword, userId, candidateTopK)) {
                if (unit.getId() == null || unit.getContent() == null || unit.getContent().isBlank()) {
                    continue;
                }

                // ── Step 3: 转换为 Document 对象 ──
                // RagUnit → Document 转换，保持与向量搜索结果格式一致
                // 包含：id(文档ID) + text(内容) + metadata(元数据)
                // metadata 由 ragUnitService.buildVectorMetadata 构建，包含：
                //   - source_id, source_type, unit_id, user_id, node_type, parent_id
                //   - filename, title, tree_level, child_count, chunk_index 等
                deduped.putIfAbsent(unit.getId(), new Document(
                        unit.getId(),
                        unit.getContent(),
                        ragUnitService.buildVectorMetadata(unit, unit.getFilename())
                ));
            }
        }
        return new ArrayList<>(deduped.values());
    }

    private List<String> normalizeQueries(String primaryQuery, List<String> recallQueries) {
        Set<String> normalized = new LinkedHashSet<>();
        if (primaryQuery != null && !primaryQuery.isBlank()) {
            normalized.add(primaryQuery.trim());
        }
        if (recallQueries != null) {
            recallQueries.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(query -> !query.isBlank())
                    .forEach(normalized::add);
        }
        return new ArrayList<>(normalized);
    }

    /**
     * 关键词预处理 — 从问句中提取核心关键词，用于 SQL LIKE 搜索。
     *
     * <h3>处理步骤</h3>
     * <pre>
     * 原始查询: "请问RagUnitService有哪些依赖注入？"
     *   │
     *   ▼ Step 1: 去除常见问句前缀
     * "RagUnitService有哪些依赖注入？"
     *   │
     *   ▼ Step 2: 去除常见问句后缀
     * "RagUnitService有哪些依赖注入"
     *   │
     *   ▼ Step 3: 去除标点符号
     * "RagUnitService有哪些依赖注入"
     *   │
     *   ▼ Step 4: 合并多余空格
     * "RagUnitService有哪些依赖注入"
     * </pre>
     *
     * <h3>为什么需要预处理？</h3>
     * <ul>
     *   <li>SQL LIKE '%keyword%' 搜索要求关键词尽量精确</li>
     *   <li>问句前缀/后缀和标点符号会干扰搜索结果</li>
     *   <li>提取核心关键词可以提高匹配精度和召回率</li>
     * </ul>
     *
     * @param query 原始查询文本
     * @return 预处理后的关键词，如果无法提取有效关键词则返回空字符串
     */
    private String normalizeKeywordQuery(String query) {
        if (query == null) {
            return "";
        }
        String normalized = query.trim();

        // ── Step 1: 去除常见问句前缀 ──
        // 匹配并移除问句开头的礼貌用语和引导词
        // 例如："请问"、"请介绍一下"、"什么是" 等
        // 这些前缀不影响核心语义，但会干扰关键词匹配
        normalized = normalized.replaceAll("^(请问|请介绍一下|请介绍|介绍一下|请解释一下|请解释|解释一下|请说明一下|请说明|说明一下|什么是)", "");

        // ── Step 2: 去除常见问句后缀 ──
        // 匹配并移除问句结尾的疑问词和语气词
        // 例如："是什么"、"什么意思"、"吗"、"呢" 等
        // 这些后缀不影响核心语义，但会干扰关键词匹配
        normalized = normalized.replaceAll("(是什么|是啥|什么意思|含义是什么|定义是什么|的定义|的含义|吗|呢)$", "");

        // ── Step 3: 去除标点符号 ──
        // 将所有中文和英文标点符号替换为空格
        // 保留字母、数字、中文字符等有效内容
        // 这样可以避免标点符号干扰 LIKE 查询
        normalized = normalized.replaceAll("[？?。！!，,；;：:\"\"''（）()【】\\[\\]]", " ");

        // ── Step 4: 合并多余空格 ──
        // 将多个连续空格合并为单个空格，并去除首尾空格
        // 确保最终的关键词格式整洁
        normalized = normalized.replaceAll("\\s+", " ").trim();

        return normalized;
    }
}

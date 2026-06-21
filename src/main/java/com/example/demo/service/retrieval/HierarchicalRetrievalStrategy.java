package com.example.demo.service.retrieval;

import com.example.demo.Config.HierarchyConfig;
import com.example.demo.model.RagNodeType;
import com.example.demo.model.RagUnit;
import com.example.demo.model.dto.HierarchyHit;
import com.example.demo.model.dto.RetrievalMode;
import com.example.demo.model.dto.RetrievalResult;
import com.example.demo.repository.RagUnitQueryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 层次检索策略实现，基于三层摘要树（文档摘要 → 章节摘要 → 叶子节点）逐层下钻检索。
 *
 * <h3>核心流程</h3>
 * <ol>
 *   <li><b>摘要召回</b> — 用 query 在 summaryVectorStore 中召回候选摘要节点（文档级 + 章节级）</li>
 *   <li><b>Section 展开</b> — 将文档摘要展开为其下所有章节摘要，连同直接命中的章节摘要一起做 Rerank，筛选出与 query 最相关的章节</li>
 *   <li><b>叶子下钻</b> — 取筛选后章节的叶子子节点，再次 Rerank 得到最终命中的细粒度片段</li>
 *   <li><b>阈值校验</b> — 最终叶子最高分必须达到 hitThreshold，否则整体回退</li>
 * </ol>
 *
 * <p>任一阶段拿不到有效结果时返回 {@code null}，由编排层 {@link RagRetrievalService } 回退到
 * {@link FlatRetrievalStrategy} 平铺检索。</p>
 *
 * @see FlatRetrievalStrategy
 * @see RagRetrievalService
 */
@Slf4j
@Component
public class HierarchicalRetrievalStrategy implements RetrievalStrategy {

    private final VectorStore summaryVectorStore;
    private final RagUnitQueryRepository ragUnitQueryRepository;
    private final RerankHelper rerankHelper;
    private final KnowledgeTextBuilder knowledgeTextBuilder;
    private final HierarchyConfig hierarchyConfig;
    private final UserFilterBuilder userFilterBuilder;

    @Value("${rag.retrieval.similarity-threshold:0.3}")
    private double similarityThreshold;

    public HierarchicalRetrievalStrategy(
            @Qualifier("summaryVectorStore") VectorStore summaryVectorStore,
            RagUnitQueryRepository ragUnitQueryRepository,
            RerankHelper rerankHelper,
            KnowledgeTextBuilder knowledgeTextBuilder,
            HierarchyConfig hierarchyConfig,
            UserFilterBuilder userFilterBuilder) {
        this.summaryVectorStore = summaryVectorStore;
        this.ragUnitQueryRepository = ragUnitQueryRepository;
        this.rerankHelper = rerankHelper;
        this.knowledgeTextBuilder = knowledgeTextBuilder;
        this.hierarchyConfig = hierarchyConfig;
        this.userFilterBuilder = userFilterBuilder;
    }

    /**
     * 执行层次检索主流程：摘要召回 → Section 展开与 Rerank → 叶子下钻与 Rerank → 构建命中路径。
     *
     * @param query         用户查询文本
     * @param userId        当前用户 ID，用于过滤该用户可见的知识片段
     * @param topK          最终返回的最大结果数
     * @param hitThreshold  叶子节点 Rerank 最低分数阈值，低于此值视为未命中
     * @param startTime     检索起始时间戳（毫秒），用于计算耗时
     * @return 检索结果，包含命中的叶子文档、层级命中路径等；若任一阶段无有效结果则返回 {@code null}
     */
    @Override
    public RetrievalResult retrieve(String query, String userId, int topK, double hitThreshold, long startTime) {
        // 第一步：从摘要向量库召回候选节点
        SearchRequest summarySearch = SearchRequest.builder()
                .query(query)
                .topK(hierarchyConfig.getSummaryCandidateTopK())
                .similarityThreshold(similarityThreshold)
                .filterExpression(userFilterBuilder.build(userId))
                .build();

        List<Document> summaryCandidates = summaryVectorStore.similaritySearch(summarySearch);
        if (summaryCandidates.isEmpty()) {
            log.info("层次检索未召回摘要节点，回退到叶子平铺检索");
            return null;
        }

        List<RagUnit> summaryUnits = ragUnitQueryRepository.selectByIds(knowledgeTextBuilder.extractIds(summaryCandidates));
        if (summaryUnits.isEmpty()) {
            log.info("层次检索摘要候选无法映射到节点，回退到叶子平铺检索");
            return null;
        }

        // 第二步：展开 section 节点
        Map<String, RagUnit> summaryUnitMap = RagUnitQueryRepository.toUnitMap(summaryUnits);
        List<RagUnit> expandedSections = expandSectionCandidates(summaryCandidates, summaryUnitMap);
        if (expandedSections.isEmpty()) {
            log.info("层次检索无法展开中层节点，回退到叶子平铺检索");
            return null;
        }

        List<Document> sectionDocs = knowledgeTextBuilder.buildDocuments(expandedSections);
        RerankHelper.ScoredDocumentsResult sectionRerank = rerankHelper.rerank(query, sectionDocs, hierarchyConfig.getMidRerankTopK());
        if (sectionRerank.documents().isEmpty()) {
            log.info("层次检索中层 rerank 为空，回退到叶子平铺检索");
            return null;
        }

        // 第三步：下钻到叶子节点
        List<String> sectionIds = knowledgeTextBuilder.extractIds(sectionRerank.documents());
        List<RagUnit> leafUnits = ragUnitQueryRepository.selectChildrenByParentIds(userId, sectionIds, RagNodeType.LEAF);
        if (leafUnits.isEmpty()) {
            log.info("层次检索中层下钻后没有叶子节点，回退到叶子平铺检索");
            return null;
        }

        List<Document> leafDocs = knowledgeTextBuilder.buildDocuments(leafUnits);
        RerankHelper.ScoredDocumentsResult leafRerank = rerankHelper.rerank(
                query, leafDocs, Math.min(topK, hierarchyConfig.getLeafRerankTopK()));
        if (leafRerank.documents().isEmpty()) {
            log.info("层次检索叶子 rerank 为空，回退到叶子平铺检索");
            return null;
        }

        if (leafRerank.topScore() == null || leafRerank.topScore() < hitThreshold) {
            log.info("层次检索叶子最高分低于阈值，回退到叶子平铺检索: score={}", leafRerank.topScore());
            return null;
        }

        // 第四步：构建层级命中路径，回溯叶子 → 章节 → 文档的完整层级关系
        Map<String, RagUnit> leafUnitMap = knowledgeTextBuilder.selectUnitsAsMap(knowledgeTextBuilder.extractIds(leafRerank.documents()));
        Map<String, RagUnit> sectionUnitMap = new HashMap<>(RagUnitQueryRepository.toUnitMap(expandedSections));

        // 从展开的 section 中收集其父文档 ID，用于回溯文档级标题
        Set<String> docIds = new LinkedHashSet<>();
        for (RagUnit section : expandedSections) {
            if (section.getParentId() != null) {
                docIds.add(section.getParentId());
            }
        }
        Map<String, RagUnit> docUnitMap = knowledgeTextBuilder.selectUnitsAsMap(new ArrayList<>(docIds));

        List<HierarchyHit> hierarchyHits = buildHierarchyHits(
                leafRerank.documents(), leafUnitMap, sectionUnitMap, docUnitMap,
                sectionRerank.scoreById(), leafRerank.scoreById());

        // 构建最终结果：拼接展开后的知识文本（含上下文补充），组装 RetrievalResult
        long duration = System.currentTimeMillis() - startTime;
        String knowledgeText = knowledgeTextBuilder.buildExpandedKnowledgeText(leafRerank.documents(), ragUnitQueryRepository.selectByIds(knowledgeTextBuilder.extractIds(leafRerank.documents())));

        log.info("层次检索完成: summaryCandidates={}, expandedSections={}, finalLeaves={}, topScore={}, 耗时={}ms",
                summaryCandidates.size(), expandedSections.size(), leafRerank.documents().size(),
                leafRerank.topScore() != null ? String.format("%.4f", leafRerank.topScore()) : "N/A", duration);

        return RetrievalResult.builder()
                .documents(leafRerank.documents())
                .hit(true)
                .retrievalMode(RetrievalMode.HIERARCHICAL)
                .knowledgeText(knowledgeText)
                .candidateCount(summaryCandidates.size())
                .finalCount(leafRerank.documents().size())
                .durationMs(duration)
                .hierarchyHits(hierarchyHits)
                .build();
    }

    /**
     * 将摘要向量库召回的候选节点展开为 Section 级节点列表。
     * <p>
     * 处理两种情况：
     * <ul>
     *   <li>候选本身是 SECTION_SUMMARY — 直接纳入结果</li>
     *   <li>候选是 DOC_SUMMARY — 查询其下所有 SECTION_SUMMARY 子节点并纳入结果</li>
     * </ul>
     * 最终按 ID 去重后返回。
     *
     * @param summaryCandidates 摘要向量库召回的原始文档列表
     * @param summaryUnitMap    候选 ID → RagUnit 映射，用于快速查找节点类型
     * @return 去重后的 Section 级 RagUnit 列表；若无有效 Section 则返回空列表
     */
    private List<RagUnit> expandSectionCandidates(List<Document> summaryCandidates, Map<String, RagUnit> summaryUnitMap) {
        Set<String> sectionIds = new LinkedHashSet<>();
        Set<String> docIds = new LinkedHashSet<>();

        // 按节点类型分流：直接命中的 section 和需要展开的文档摘要
        for (Document candidate : summaryCandidates) {
            RagUnit unit = summaryUnitMap.get(candidate.getId());
            if (unit == null || unit.getNodeType() == null) {
                continue;
            }
            if (unit.getNodeType() == RagNodeType.SECTION_SUMMARY) {
                sectionIds.add(unit.getId());
            } else if (unit.getNodeType() == RagNodeType.DOC_SUMMARY) {
                docIds.add(unit.getId());
            }
        }

        // 分别查询直接命中的 section 和文档摘要下的 section 子节点
        List<RagUnit> sections = new ArrayList<>();
        if (!sectionIds.isEmpty()) {
            sections.addAll(ragUnitQueryRepository.selectByIds(new ArrayList<>(sectionIds)));
        }
        if (!docIds.isEmpty()) {
            sections.addAll(ragUnitQueryRepository.selectChildrenByParentIds(null, new ArrayList<>(docIds), RagNodeType.SECTION_SUMMARY));
        }

        // 按 ID 去重，保留首次出现的顺序
        Map<String, RagUnit> deduped = new java.util.LinkedHashMap<>();
        for (RagUnit section : sections) {
            deduped.put(section.getId(), section);
        }
        return new ArrayList<>(deduped.values());
    }

    /**
     * 为最终命中的叶子节点构建完整的层级命中路径（叶子 → 章节 → 文档）。
     * <p>
     * 每个 {@link HierarchyHit} 记录了一片叶子节点的完整溯源信息：所属章节标题、
     * 所属文档标题，以及各层级的 Rerank 分数，便于前端展示命中来源。
     *
     * @param finalDocs      最终 Rerank 后命中的叶子 Document 列表
     * @param leafUnitMap     叶子 ID → RagUnit 映射
     * @param sectionUnitMap  Section ID → RagUnit 映射
     * @param docUnitMap      文档 ID → RagUnit 映射
     * @param sectionScores   Section Rerank 分数映射（sectionId → score）
     * @param leafScores      叶子 Rerank 分数映射（leafId → score）
     * @return 层级命中路径列表，与 finalDocs 顺序一致
     */
    private List<HierarchyHit> buildHierarchyHits(List<Document> finalDocs,
                                                  Map<String, RagUnit> leafUnitMap,
                                                  Map<String, RagUnit> sectionUnitMap,
                                                  Map<String, RagUnit> docUnitMap,
                                                  Map<String, Double> sectionScores,
                                                  Map<String, Double> leafScores) {
        List<HierarchyHit> hits = new ArrayList<>();
        for (Document doc : finalDocs) {
            // 定位叶子节点
            RagUnit leafUnit = leafUnitMap.get(doc.getId());
            if (leafUnit == null) {
                continue;
            }

            // 沿 parent_id 向上回溯：叶子 → 章节摘要 → 文档摘要
            RagUnit sectionUnit = leafUnit.getParentId() != null
                    ? sectionUnitMap.get(leafUnit.getParentId())
                    : null;
            RagUnit docUnit = sectionUnit != null && sectionUnit.getParentId() != null
                    ? docUnitMap.get(sectionUnit.getParentId())
                    : null;

            // 组装层级命中记录：包含完整溯源路径 + 各层 Rerank 分数
            hits.add(HierarchyHit.builder()
                    .sourceId(leafUnit.getSourceId())
                    .docNodeId(docUnit != null ? docUnit.getId() : null)
                    .docTitle(docUnit != null ? docUnit.getTitle() : null)
                    .sectionNodeId(sectionUnit != null ? sectionUnit.getId() : null)
                    .sectionTitle(sectionUnit != null ? sectionUnit.getTitle() : null)
                    .leafUnitId(leafUnit.getId())
                    .leafChunkIndex(leafUnit.getChunkIndex())
                    .summaryScore(sectionUnit != null ? sectionScores.get(sectionUnit.getId()) : null)
                    .leafScore(leafScores.get(leafUnit.getId()))
                    .content(leafUnit.getContent())
                    .filename(leafUnit.getFilename())
                    .minioUrl(leafUnit.getMinioUrl())
                    .build());
        }
        return hits;
    }
}

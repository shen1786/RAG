package com.example.demo.service.retrieval;

import com.example.demo.Config.HierarchyConfig;
import com.example.demo.model.RagNodeType;
import com.example.demo.model.RagUnit;
import com.example.demo.model.dto.HierarchyHit;
import com.example.demo.model.dto.RetrievalMode;
import com.example.demo.model.dto.RetrievalResult;
import com.example.demo.repository.RagUnitQueryRepository;
import com.example.demo.service.RagRetrievalService;
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
        // ━━━ 第一步：摘要召回 ━━━
        // 先在摘要库里粗筛一圈（就像看书先看目录，找到相关章节再细看）
        // 摘要 = 整个文档/章节的一句话概括，用少量节点覆盖大量内容，快速缩小范围
        SearchRequest summarySearch = SearchRequest.builder()
                .query(query)
                .topK(hierarchyConfig.getSummaryCandidateTopK())        // 最多召回几个摘要（多了浪费，少了漏掉）
                .similarityThreshold(similarityThreshold)                // 太不相关的不要（就像目录和问题完全对不上就不看了）
                .filterExpression(userFilterBuilder.build(userId))       // 只看自己有权限的文档（别人私密文档不能搜到）
                .build();

        List<Document> summaryCandidates = summaryVectorStore.similaritySearch(summarySearch);
        // 连摘要都没命中 → 说明知识库里没有相关内容，返回 null 让上层降级到平铺检索（换个方式再找找）
        if (summaryCandidates.isEmpty()) {
            log.info("层次检索未召回摘要节点，回退到叶子平铺检索");
            return null;
        }

        // 向量库里只存了 ID 和文本，完整信息（标题、类型等）在 MySQL 里，需要补查
        List<RagUnit> summaryUnits = ragUnitQueryRepository.selectByIds(knowledgeTextBuilder.extractIds(summaryCandidates));
        if (summaryUnits.isEmpty()) {
            log.info("层次检索摘要候选无法映射到节点，回退到叶子平铺检索");
            return null;
        }

        // ━━━ 第二步：Section 展开与 Rerank ━━━
        // 找到了相关文档摘要，现在要展开看看里面哪些章节和问题相关
        // 就像：先找到"第三章 数据库设计"相关，再细看是哪一节讲了用户问的内容
        Map<String, RagUnit> summaryUnitMap = RagUnitQueryRepository.toUnitMap(summaryUnits);
        List<RagUnit> expandedSections = expandSectionCandidates(summaryCandidates, summaryUnitMap);
        if (expandedSections.isEmpty()) {
            log.info("层次检索无法展开中层节点，回退到叶子平铺检索");
            return null;
        }

        // 对章节做精排（粗筛靠向量相似度，精排用 AI 交叉编码器重新打分，更准）
        // 就像：目录找到了 10 个可能相关的章节，精排帮你挑出最相关的 3 个
        List<Document> sectionDocs = knowledgeTextBuilder.buildDocuments(expandedSections);
        RerankHelper.ScoredDocumentsResult sectionRerank = rerankHelper.rerank(query, sectionDocs, hierarchyConfig.getMidRerankTopK());
        if (sectionRerank.documents().isEmpty()) {
            log.info("层次检索中层 rerank 为空，回退到叶子平铺检索");
            return null;
        }

        // ━━━ 第三步：叶子下钻与 Rerank ━━━
        // 找到了相关章节，现在要取出里面的具体文本段落（叶子节点）
        // 就像：找到了"3.2 节 用户表设计"，现在要读里面的具体内容
        List<String> sectionIds = knowledgeTextBuilder.extractIds(sectionRerank.documents());
        List<RagUnit> leafUnits = ragUnitQueryRepository.selectChildrenByParentIds(userId, sectionIds, RagNodeType.LEAF);
        if (leafUnits.isEmpty()) {
            log.info("层次检索中层下钻后没有叶子节点，回退到叶子平铺检索");
            return null;
        }

        // 对叶子段落再次精排，选出最相关的 topK 个（一个章节可能有 20 段，只取最相关的 5 段）
        List<Document> leafDocs = knowledgeTextBuilder.buildDocuments(leafUnits);
        RerankHelper.ScoredDocumentsResult leafRerank = rerankHelper.rerank(
                query, leafDocs, Math.min(topK, hierarchyConfig.getLeafRerankTopK()));
        if (leafRerank.documents().isEmpty()) {
            log.info("层次检索叶子 rerank 为空，回退到叶子平铺检索");
            return null;
        }

        // 最终关卡：最高分必须达标（防止返回一堆不相关的内容，答非所问比不答更糟）
        if (leafRerank.topScore() == null || leafRerank.topScore() < hitThreshold) {
            log.info("层次检索叶子最高分低于阈值，回退到叶子平铺检索: score={}", leafRerank.topScore());
            return null;
        }

        // ━━━ 第四步：构建层级命中路径 ━━━
        // 回溯每个叶子段落属于哪个章节、哪个文档（前端要展示"来自《XX手册》第3章"）
        Map<String, RagUnit> leafUnitMap = knowledgeTextBuilder.selectUnitsAsMap(knowledgeTextBuilder.extractIds(leafRerank.documents()));
        Map<String, RagUnit> sectionUnitMap = new HashMap<>(RagUnitQueryRepository.toUnitMap(expandedSections));

        // 收集文档 ID，用于查文档标题
        Set<String> docIds = new LinkedHashSet<>();
        for (RagUnit section : expandedSections) {
            if (section.getParentId() != null) {
                docIds.add(section.getParentId());
            }
        }
        Map<String, RagUnit> docUnitMap = knowledgeTextBuilder.selectUnitsAsMap(new ArrayList<>(docIds));

        // 组装溯源信息：叶子 → 章节 → 文档（完整链路）
        List<HierarchyHit> hierarchyHits = buildHierarchyHits(
                leafRerank.documents(), leafUnitMap, sectionUnitMap, docUnitMap,
                sectionRerank.scoreById(), leafRerank.scoreById());

        // ━━━ 第五步：组装最终结果 ━━━
        // 把命中的段落拼成一段参考资料文本，后面要塞进 LLM 的 Prompt 里
        long duration = System.currentTimeMillis() - startTime;
        String knowledgeText = knowledgeTextBuilder.buildExpandedKnowledgeText(leafRerank.documents(), ragUnitQueryRepository.selectByIds(knowledgeTextBuilder.extractIds(leafRerank.documents())));

        log.info("层次检索完成: summaryCandidates={}, expandedSections={}, finalLeaves={}, topScore={}, 耗时={}ms",
                summaryCandidates.size(), expandedSections.size(), leafRerank.documents().size(),
                leafRerank.topScore() != null ? String.format("%.4f", leafRerank.topScore()) : "N/A", duration);

        return RetrievalResult.builder()
                .documents(leafRerank.documents())       // 最终命中的叶子段落
                .hit(true)                               // 标记命中了（告诉下游走知识问答流程）
                .retrievalMode(RetrievalMode.HIERARCHICAL) // 检索模式（排查问题时有用）
                .knowledgeText(knowledgeText)            // 拼好的参考资料，塞进 Prompt 让 LLM 参考
                .candidateCount(summaryCandidates.size()) // 一开始召回了几个摘要（监控用）
                .finalCount(leafRerank.documents().size()) // 最终返回了几个段落
                .durationMs(duration)                    // 整个检索花了多久（监控用）
                .hierarchyHits(hierarchyHits)            // 完整溯源路径，前端展示"来自哪个文档哪个章节"
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
    /**
     * 将摘要向量库召回的候选节点展开为 Section 级节点列表。
     *
     * <p>处理两种情况：</p>
     * <ul>
     *   <li>候选本身是 SECTION_SUMMARY — 直接纳入结果（已经是最细粒度的摘要）</li>
     *   <li>候选是 DOC_SUMMARY — 查询其下所有 SECTION_SUMMARY 子节点（原因：文档摘要命中说明整体相关，需要展开到章节级）</li>
     * </ul>
     *
     * @param summaryCandidates 摘要向量库召回的原始文档列表
     * @param summaryUnitMap    候选 ID → RagUnit 映射，用于快速查找节点类型
     * @return 去重后的 Section 级 RagUnit 列表；若无有效 Section 则返回空列表
     */
    /**
     * 把粗筛召回的摘要节点展开成章节列表。
     *
     * <p>打个比方：粗筛找到了"第三章"和"整本手册"两个结果，
     * 这个方法就是把"整本手册"展开成它下面的所有章节，和"第三章"合并去重。</p>
     *
     * @param summaryCandidates 粗筛召回的摘要列表（可能是文档级或章节级）
     * @param summaryUnitMap    ID → 实体映射（用来查节点类型）
     * @return 去重后的章节列表
     */
    private List<RagUnit> expandSectionCandidates(List<Document> summaryCandidates, Map<String, RagUnit> summaryUnitMap) {
        Set<String> sectionIds = new LinkedHashSet<>();
        Set<String> docIds = new LinkedHashSet<>();

        // ① 看看粗筛召回的是什么类型
        //    - 章节摘要（SECTION_SUMMARY）：已经是章节了，直接收下
        //    - 文档摘要（DOC_SUMMARY）：是整本书的概括，需要展开看看里面有哪些章节
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

        // ② 分别查库
        //    直接命中的章节 → 直接查出来
        //    文档摘要 → 查它下面所有的章节子节点
        List<RagUnit> sections = new ArrayList<>();
        if (!sectionIds.isEmpty()) {
            sections.addAll(ragUnitQueryRepository.selectByIds(new ArrayList<>(sectionIds)));
        }
        if (!docIds.isEmpty()) {
            sections.addAll(ragUnitQueryRepository.selectChildrenByParentIds(null, new ArrayList<>(docIds), RagNodeType.SECTION_SUMMARY));
        }

        // ③ 去重（可能直接命中的章节 和 文档展开的章节 有重复，比如"第三章"既被直接命中又被展开出来）
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
    /**
     * 为命中的段落构建溯源信息：这段话来自哪个文档、哪个章节。
     *
     * <p>前端拿到这个信息后，会展示"来源：《用户手册》第3章 用户表设计"这样的卡片。</p>
     *
     * @param finalDocs      最终命中的叶子段落
     * @param leafUnitMap     段落 ID → 实体映射
     * @param sectionUnitMap  章节 ID → 实体映射
     * @param docUnitMap      文档 ID → 实体映射
     * @param sectionScores   章节的精排分数（用来展示相关度）
     * @param leafScores      段落的精排分数（用来展示相关度）
     * @return 溯源信息列表，和 finalDocs 一一对应
     */
    private List<HierarchyHit> buildHierarchyHits(List<Document> finalDocs,
                                                  Map<String, RagUnit> leafUnitMap,
                                                  Map<String, RagUnit> sectionUnitMap,
                                                  Map<String, RagUnit> docUnitMap,
                                                  Map<String, Double> sectionScores,
                                                  Map<String, Double> leafScores) {
        List<HierarchyHit> hits = new ArrayList<>();
        for (Document doc : finalDocs) {
            // ① 找到这个段落的完整信息
            RagUnit leafUnit = leafUnitMap.get(doc.getId());
            if (leafUnit == null) {
                continue;
            }

            // ② 沿着 parent_id 往上找：段落 → 章节 → 文档（就像从一句话找到它在哪个章节、哪本书）
            //    树结构：文档摘要 → 章节摘要 → 叶子段落
            RagUnit sectionUnit = leafUnit.getParentId() != null
                    ? sectionUnitMap.get(leafUnit.getParentId())    // 段落的爸爸是章节
                    : null;
            RagUnit docUnit = sectionUnit != null && sectionUnit.getParentId() != null
                    ? docUnitMap.get(sectionUnit.getParentId())     // 章节的爸爸是文档
                    : null;

            // ③ 组装溯源记录（前端展示来源卡片用的）
            hits.add(HierarchyHit.builder()
                    .sourceId(leafUnit.getSourceId())                                          // 原始文件 ID（用来下载文件）
                    .docNodeId(docUnit != null ? docUnit.getId() : null)                        // 文档节点 ID
                    .docTitle(docUnit != null ? docUnit.getTitle() : null)                      // 文档标题，比如"用户手册.pdf"
                    .sectionNodeId(sectionUnit != null ? sectionUnit.getId() : null)            // 章节节点 ID
                    .sectionTitle(sectionUnit != null ? sectionUnit.getTitle() : null)          // 章节标题，比如"3.2 用户表设计"
                    .leafUnitId(leafUnit.getId())                                               // 段落 ID
                    .leafChunkIndex(leafUnit.getChunkIndex())                                   // 这是文档里的第几段
                    .summaryScore(sectionUnit != null ? sectionScores.get(sectionUnit.getId()) : null)  // 章节相关度分数
                    .leafScore(leafScores.get(leafUnit.getId()))                                // 段落相关度分数（前端显示百分比）
                    .content(leafUnit.getContent())                                             // 段落原文内容
                    .filename(leafUnit.getFilename())                                           // 文件名
                    .minioUrl(leafUnit.getMinioUrl())                                           // 文件下载链接
                    .build());
        }
        return hits;
    }
}

package com.example.demo.service;

import com.alibaba.cloud.ai.document.DocumentWithScore;
import com.alibaba.cloud.ai.model.RerankModel;
import com.alibaba.cloud.ai.model.RerankRequest;
import com.alibaba.cloud.ai.model.RerankResponse;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.demo.Config.HierarchyConfig;
import com.example.demo.mapper.RagUnitMapper;
import com.example.demo.model.RagNodeType;
import com.example.demo.model.RagUnit;
import com.example.demo.model.dto.HierarchyHit;
import com.example.demo.model.dto.RetrievalMode;
import com.example.demo.model.dto.RetrievalResult;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Service
@Slf4j
public class RagRetrievalService {

    private static final int CONTEXT_NEIGHBOR_BEFORE = 1;
    private static final int CONTEXT_NEIGHBOR_AFTER = 2;

    private final VectorStore leafVectorStore;
    private final VectorStore summaryVectorStore;
    private final RerankModel rerankModel;
    private final RagUnitMapper ragUnitMapper;
    private final RagUnitService ragUnitService;
    private final HierarchyConfig hierarchyConfig;
    private final Executor retrievalTaskExecutor;

    @Value("${rag.retrieval.candidate-top-k:15}")
    private int candidateTopK;

    @Value("${rag.retrieval.similarity-threshold:0.3}")
    private double similarityThreshold;

    @Value("${rag.retrieval.final-top-k:5}")
    private int finalTopK;

    @Value("${rag.retrieval.hit-score-threshold:0.35}")
    private double hitScoreThreshold;

    public RagRetrievalService(@Qualifier("leafVectorStore") VectorStore leafVectorStore,
                               @Qualifier("summaryVectorStore") VectorStore summaryVectorStore,
                               RerankModel rerankModel,
                               RagUnitMapper ragUnitMapper,
                               RagUnitService ragUnitService,
                               HierarchyConfig hierarchyConfig,
                               @Qualifier("mvcTaskExecutor") Executor retrievalTaskExecutor) {
        this.leafVectorStore = leafVectorStore;
        this.summaryVectorStore = summaryVectorStore;
        this.rerankModel = rerankModel;
        this.ragUnitMapper = ragUnitMapper;
        this.ragUnitService = ragUnitService;
        this.hierarchyConfig = hierarchyConfig;
        this.retrievalTaskExecutor = retrievalTaskExecutor;
    }

    public RetrievalResult retrieve(String query) {
        return retrieve(query, null, finalTopK, hitScoreThreshold);
    }

    public RetrievalResult retrieve(String query, String userId) {
        return retrieve(query, userId, finalTopK, hitScoreThreshold);
    }

    public RetrievalResult retrieve(String query, int topK, double hitThreshold) {
        return retrieve(query, null, topK, hitScoreThreshold);
    }

    public RetrievalResult retrieve(String query, String userId, int topK, double hitThreshold) {
        long startTime = System.currentTimeMillis();

        try {
            // 优先尝试层次检索；任一阶段拿不到有效结果时，再平滑回退到平铺检索。
            RetrievalResult hierarchical = retrieveHierarchically(query, userId, topK, hitThreshold, startTime);
            if (hierarchical != null) {
                return hierarchical;
            }
            return retrieveFlat(query, userId, topK, hitThreshold, startTime, true);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("检索过程发生异常, 耗时={}ms", duration, e);
            return RetrievalResult.empty(duration);
        }
    }

    public RetrievalResult retrieveWithMultiPathRecall(String primaryQuery, List<String> recallQueries, String userId) {
        return retrieveWithMultiPathRecall(primaryQuery, recallQueries, userId, finalTopK, hitScoreThreshold);
    }

    public RetrievalResult retrieveWithMultiPathRecall(String primaryQuery,
                                                       List<String> recallQueries,
                                                       String userId,
                                                       int topK,
                                                       double hitThreshold) {
        long startTime = System.currentTimeMillis();
        List<String> normalizedQueries = normalizeQueries(primaryQuery, recallQueries);

        try {
            if (normalizedQueries.isEmpty()) {
                return RetrievalResult.empty(System.currentTimeMillis() - startTime);
            }

            RetrievalResult primaryResult = retrieve(normalizedQueries.get(0), userId, topK, hitThreshold);
            if (normalizedQueries.size() == 1) {
                return primaryResult;
            }

            List<Document> mergedCandidates = collectHybridCandidates(normalizedQueries, userId);
            if (mergedCandidates.isEmpty()) {
                long duration = System.currentTimeMillis() - startTime;
                log.info("多路召回未召回任何候选文档, queries={}, 耗时={}ms", normalizedQueries.size(), duration);
                if (!primaryResult.getDocuments().isEmpty()) {
                    return primaryResult;
                }
                return RetrievalResult.empty(duration);
            }

            ScoredDocumentsResult rerankResult = rerank(normalizedQueries.get(0), mergedCandidates, topK);
            boolean hit = rerankResult.topScore != null && rerankResult.topScore >= hitThreshold;
            if (!hit && primaryResult.isHit()) {
                log.info("多路召回结果未达命中阈值，回退到主查询检索结果: primaryQuery={}", normalizedQueries.get(0));
                return primaryResult;
            }
            long duration = System.currentTimeMillis() - startTime;
            log.info("多路召回完成: queries={}, mergedCandidates={}, final={}, topScore={}, 耗时={}ms",
                    normalizedQueries.size(),
                    mergedCandidates.size(),
                    rerankResult.documents.size(),
                    rerankResult.topScore != null ? String.format("%.4f", rerankResult.topScore) : "N/A",
                    duration);

            List<String> leafIds = extractIds(rerankResult.documents);
            List<RagUnit> leafUnits = selectUnitsByIds(leafIds);
            String knowledgeText = buildExpandedKnowledgeText(rerankResult.documents, leafUnits);
            Map<String, RagUnit> leafUnitMap = toUnitMap(leafUnits);
            List<HierarchyHit> hierarchyHits = new ArrayList<>();
            for (Document doc : rerankResult.documents) {
                RagUnit leafUnit = leafUnitMap.get(doc.getId());
                if (leafUnit == null) continue;
                hierarchyHits.add(HierarchyHit.builder()
                        .sourceId(leafUnit.getSourceId())
                        .leafUnitId(leafUnit.getId())
                        .leafChunkIndex(leafUnit.getChunkIndex())
                        .leafScore(rerankResult.scoreById.get(leafUnit.getId()))
                        .content(leafUnit.getContent())
                        .filename(leafUnit.getFilename())
                        .minioUrl(leafUnit.getMinioUrl())
                        .build());
            }

            return RetrievalResult.builder()
                    .documents(rerankResult.documents)
                    .hit(hit)
                    .retrievalMode(RetrievalMode.FLAT_FALLBACK)
                    .knowledgeText(knowledgeText)
                    .candidateCount(mergedCandidates.size())
                    .finalCount(rerankResult.documents.size())
                    .durationMs(duration)
                    .hierarchyHits(hierarchyHits)
                    .build();
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("多路召回过程发生异常, 耗时={}ms", duration, e);
            return RetrievalResult.empty(duration);
        }
    }

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

            List<RagUnit> leafUnits = selectUnitsByIds(extractIds(docs));
            String knowledgeText = buildExpandedKnowledgeText(docs, leafUnits);

            long duration = System.currentTimeMillis() - startTime;

            return RetrievalResult.builder()
                    .documents(docs)
                    .hit(hit)
                    .retrievalMode(RetrievalMode.FLAT_FALLBACK)
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

    private RetrievalResult retrieveHierarchically(String query, String userId, int topK, double hitThreshold, long startTime) {
        // 第一步先从摘要向量库召回候选节点，缩小后续展开范围。
        SearchRequest summarySearch = SearchRequest.builder()
                .query(query)
                .topK(hierarchyConfig.getSummaryCandidateTopK())
                .similarityThreshold(similarityThreshold)
                .filterExpression(buildUserFilterExpression(userId))
                .build();

        List<Document> summaryCandidates = summaryVectorStore.similaritySearch(summarySearch);
        if (summaryCandidates.isEmpty()) {
            log.info("层次检索未召回摘要节点，回退到叶子平铺检索");
            return null;
        }

        List<RagUnit> summaryUnits = selectUnitsByIds(extractIds(summaryCandidates));
        if (summaryUnits.isEmpty()) {
            log.info("层次检索摘要候选无法映射到节点，回退到叶子平铺检索");
            return null;
        }

        // 第二步把命中的摘要节点映射回真实树节点，再决定展开 section 还是 doc 的子节点。
        Map<String, RagUnit> summaryUnitMap = toUnitMap(summaryUnits);
        List<RagUnit> expandedSections = expandSectionCandidates(summaryCandidates, summaryUnitMap);
        if (expandedSections.isEmpty()) {
            log.info("层次检索无法展开中层节点，回退到叶子平铺检索");
            return null;
        }

        List<Document> sectionDocs = buildDocuments(expandedSections);
        ScoredDocumentsResult sectionRerank = rerank(query, sectionDocs, hierarchyConfig.getMidRerankTopK());
        if (sectionRerank.documents.isEmpty()) {
            log.info("层次检索中层 rerank 为空，回退到叶子平铺检索");
            return null;
        }

        List<String> sectionIds = extractIds(sectionRerank.documents);
        // 第三步只下钻命中的 section，避免全量叶子 rerank。
        List<RagUnit> leafUnits = selectChildrenByParentIds(userId, sectionIds, RagNodeType.LEAF);
        if (leafUnits.isEmpty()) {
            log.info("层次检索中层下钻后没有叶子节点，回退到叶子平铺检索");
            return null;
        }

        List<Document> leafDocs = buildDocuments(leafUnits);
        ScoredDocumentsResult leafRerank = rerank(query, leafDocs, Math.min(topK, hierarchyConfig.getLeafRerankTopK()));
        if (leafRerank.documents.isEmpty()) {
            log.info("层次检索叶子 rerank 为空，回退到叶子平铺检索");
            return null;
        }

        if (leafRerank.topScore == null || leafRerank.topScore < hitThreshold) {
            log.info("层次检索叶子最高分低于阈值，回退到叶子平铺检索: score={}", leafRerank.topScore);
            return null;
        }

        List<RagUnit> finalLeafUnits = selectUnitsByIds(extractIds(leafRerank.documents));
        Map<String, RagUnit> leafUnitMap = toUnitMap(finalLeafUnits);
        Map<String, RagUnit> sectionUnitMap = new HashMap<>(toUnitMap(expandedSections));

        Set<String> docIds = new LinkedHashSet<>();
        for (RagUnit section : expandedSections) {
            if (section.getParentId() != null) {
                docIds.add(section.getParentId());
            }
        }
        Map<String, RagUnit> docUnitMap = toUnitMap(selectUnitsByIds(new ArrayList<>(docIds)));

        // 返回层级命中路径，方便上层展示“命中了哪篇文档/哪一节/哪一块叶子”。
        List<HierarchyHit> hierarchyHits = buildHierarchyHits(
                leafRerank.documents,
                leafUnitMap,
                sectionUnitMap,
                docUnitMap,
                sectionRerank.scoreById,
                leafRerank.scoreById
        );

        long duration = System.currentTimeMillis() - startTime;
        String knowledgeText = buildExpandedKnowledgeText(leafRerank.documents, finalLeafUnits);

        log.info("层次检索完成: summaryCandidates={}, expandedSections={}, finalLeaves={}, topScore={}, 耗时={}ms",
                summaryCandidates.size(),
                expandedSections.size(),
                leafRerank.documents.size(),
                leafRerank.topScore != null ? String.format("%.4f", leafRerank.topScore) : "N/A",
                duration);

        return RetrievalResult.builder()
                .documents(leafRerank.documents)
                .hit(true)
                .retrievalMode(RetrievalMode.HIERARCHICAL)
                .knowledgeText(knowledgeText)
                .candidateCount(summaryCandidates.size())
                .finalCount(leafRerank.documents.size())
                .durationMs(duration)
                .hierarchyHits(hierarchyHits)
                .build();
    }

    private RetrievalResult retrieveFlat(String query,
                                         String userId,
                                         int topK,
                                         double hitThreshold,
                                         long startTime,
                                         boolean fromFallback) {
        // 平铺模式只查叶子库，既可以作为兜底，也可以作为关闭层次检索时的默认路径。
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(candidateTopK)
                .similarityThreshold(similarityThreshold)
                .filterExpression(buildUserFilterExpression(userId))
                .build();

        List<Document> candidates = leafVectorStore.similaritySearch(searchRequest);
        if (candidates.isEmpty()) {
            long duration = System.currentTimeMillis() - startTime;
            log.info("未召回任何叶子候选文档, 耗时={}ms", duration);
            return RetrievalResult.empty(duration);
        }

        ScoredDocumentsResult rerankResult = rerank(query, candidates, topK);
        boolean hit = rerankResult.topScore != null && rerankResult.topScore >= hitThreshold;
        long duration = System.currentTimeMillis() - startTime;
        log.info("平铺检索完成: hit={}, fallback={}, candidates={}, final={}, topScore={}, 耗时={}ms",
                hit,
                fromFallback,
                candidates.size(),
                rerankResult.documents.size(),
                rerankResult.topScore != null ? String.format("%.4f", rerankResult.topScore) : "N/A",
                duration);

        List<String> leafIds = extractIds(rerankResult.documents);
        List<RagUnit> leafUnits = selectUnitsByIds(leafIds);
        String knowledgeText = buildExpandedKnowledgeText(rerankResult.documents, leafUnits);
        Map<String, RagUnit> leafUnitMap = toUnitMap(leafUnits);
        List<HierarchyHit> hierarchyHits = new ArrayList<>();
        for (Document doc : rerankResult.documents) {
            RagUnit leafUnit = leafUnitMap.get(doc.getId());
            if (leafUnit == null) continue;
            hierarchyHits.add(HierarchyHit.builder()
                    .sourceId(leafUnit.getSourceId())
                    .leafUnitId(leafUnit.getId())
                    .leafChunkIndex(leafUnit.getChunkIndex())
                    .leafScore(rerankResult.scoreById.get(leafUnit.getId()))
                    .content(leafUnit.getContent())
                    .filename(leafUnit.getFilename())
                    .minioUrl(leafUnit.getMinioUrl())
                    .build());
        }

        return RetrievalResult.builder()
                .documents(rerankResult.documents)
                .hit(hit)
                .retrievalMode(RetrievalMode.FLAT_FALLBACK)
                .knowledgeText(knowledgeText)
                .candidateCount(candidates.size())
                .finalCount(rerankResult.documents.size())
                .durationMs(duration)
                .hierarchyHits(hierarchyHits)
                .build();
    }

    private List<Document> collectFlatCandidates(List<String> queries, String userId) {
        List<CompletableFuture<List<Document>>> futures = queries.stream()
                .map(query -> CompletableFuture.supplyAsync(() -> searchLeafCandidates(query, userId), retrievalTaskExecutor)
                        .exceptionally(ex -> {
                            log.warn("子查询召回失败，跳过该路查询: query={}, error={}", query, ex.getMessage());
                            return List.of();
                        }))
                .toList();

        Map<String, Document> deduped = new LinkedHashMap<>();
        for (List<Document> candidates : futures.stream().map(CompletableFuture::join).toList()) {
            for (Document candidate : candidates) {
                String dedupeKey = candidate.getId() != null ? candidate.getId() : candidate.getText();
                if (dedupeKey == null || dedupeKey.isBlank()) {
                    continue;
                }
                deduped.putIfAbsent(dedupeKey, candidate);
            }
        }
        return new ArrayList<>(deduped.values());
    }

    private String buildExpandedKnowledgeText(List<Document> rankedDocs, List<RagUnit> matchedLeafUnits) {
        Map<String, RagUnit> matchedLeafMap = toUnitMap(matchedLeafUnits);
        Map<String, RagUnit> expandedUnits = new LinkedHashMap<>();

        for (Document doc : rankedDocs) {
            RagUnit matchedLeaf = matchedLeafMap.get(doc.getId());
            if (matchedLeaf == null) {
                continue;
            }
            List<RagUnit> contextLeaves = selectNeighborLeaves(matchedLeaf);
            if (contextLeaves.isEmpty()) {
                expandedUnits.putIfAbsent(matchedLeaf.getId(), matchedLeaf);
                continue;
            }
            for (RagUnit contextLeaf : contextLeaves) {
                if (contextLeaf.getId() != null) {
                    expandedUnits.putIfAbsent(contextLeaf.getId(), contextLeaf);
                }
            }
        }

        if (expandedUnits.isEmpty()) {
            return rankedDocs.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n\n---\n\n"));
        }

        Map<String, RagUnit> parentSections = selectParentSections(expandedUnits.values());
        return expandedUnits.values().stream()
                .map(unit -> formatKnowledgeUnit(unit, findParentSection(unit, parentSections)))
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    private RagUnit findParentSection(RagUnit unit, Map<String, RagUnit> parentSections) {
        if (unit.getParentId() == null || unit.getParentId().isBlank()) {
            return null;
        }
        return parentSections.get(unit.getParentId());
    }

    private List<RagUnit> selectNeighborLeaves(RagUnit leaf) {
        if (leaf.getSourceId() == null || leaf.getChunkIndex() == null) {
            return List.of(leaf);
        }

        int start = Math.max(0, leaf.getChunkIndex() - CONTEXT_NEIGHBOR_BEFORE);
        int end = leaf.getChunkIndex() + CONTEXT_NEIGHBOR_AFTER;
        QueryWrapper<RagUnit> wrapper = new QueryWrapper<>();
        wrapper.eq("source_id", leaf.getSourceId())
                .eq(leaf.getUserId() != null && !leaf.getUserId().isBlank(), "user_id", leaf.getUserId())
                .and(group -> group
                        .eq("node_type", RagNodeType.LEAF.name())
                        .or()
                        .isNull("node_type"))
                .between("chunk_index", start, end)
                .orderByAsc("chunk_index");

        List<RagUnit> neighbors = ragUnitMapper.selectList(wrapper);
        if (neighbors == null || neighbors.isEmpty()) {
            return List.of(leaf);
        }
        return neighbors;
    }

    private Map<String, RagUnit> selectParentSections(Collection<RagUnit> units) {
        Set<String> parentIds = new LinkedHashSet<>();
        for (RagUnit unit : units) {
            if (unit.getParentId() != null && !unit.getParentId().isBlank()) {
                parentIds.add(unit.getParentId());
            }
        }
        if (parentIds.isEmpty()) {
            return Map.of();
        }
        return toUnitMap(selectUnitsByIds(new ArrayList<>(parentIds)));
    }

    private String formatKnowledgeUnit(RagUnit unit, RagUnit parentSection) {
        StringBuilder builder = new StringBuilder();
        if (unit.getFilename() != null && !unit.getFilename().isBlank()) {
            builder.append("【文档】").append(unit.getFilename()).append('\n');
        }
        if (parentSection != null && parentSection.getTitle() != null && !parentSection.getTitle().isBlank()) {
            builder.append("【章节】").append(parentSection.getTitle()).append('\n');
        }
        if (unit.getTitle() != null && !unit.getTitle().isBlank()) {
            builder.append("【标题】").append(unit.getTitle()).append('\n');
        }
        if (unit.getChunkIndex() != null) {
            builder.append("【分段】").append(unit.getChunkIndex() + 1).append('\n');
        }
        if (unit.getContent() != null) {
            builder.append(unit.getContent());
        }
        return builder.toString().trim();
    }

    private List<Document> collectHybridCandidates(List<String> queries, String userId) {
        List<Document> vectorCandidates = collectFlatCandidates(queries, userId);
        if (!vectorCandidates.isEmpty()) {
            return vectorCandidates;
        }

        List<Document> keywordCandidates = collectKeywordCandidates(queries, userId);
        if (!keywordCandidates.isEmpty()) {
            log.info("向量召回为空，已回退到关键词召回: queries={}, candidates={}", queries.size(), keywordCandidates.size());
        }
        return keywordCandidates;
    }

    private List<Document> searchLeafCandidates(String query, String userId) {
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(candidateTopK)
                .similarityThreshold(similarityThreshold)
                .filterExpression(buildUserFilterExpression(userId))
                .build();
        return leafVectorStore.similaritySearch(searchRequest);
    }

    private List<Document> collectKeywordCandidates(List<String> queries, String userId) {
        Map<String, Document> deduped = new LinkedHashMap<>();
        for (String query : queries) {
            String keyword = normalizeKeywordQuery(query);
            if (keyword.isBlank()) {
                continue;
            }
            for (RagUnit unit : searchLeafUnitsByKeyword(keyword, userId)) {
                if (unit.getId() == null || unit.getContent() == null || unit.getContent().isBlank()) {
                    continue;
                }
                deduped.putIfAbsent(unit.getId(), new Document(
                        unit.getId(),
                        unit.getContent(),
                        ragUnitService.buildVectorMetadata(unit, unit.getFilename())
                ));
            }
        }
        return new ArrayList<>(deduped.values());
    }

    private List<RagUnit> searchLeafUnitsByKeyword(String keyword, String userId) {
        QueryWrapper<RagUnit> wrapper = new QueryWrapper<>();
        wrapper.and(group -> group
                        .eq("node_type", RagNodeType.LEAF.name())
                        .or()
                        .isNull("node_type"))
                .eq(userId != null && !userId.isBlank(), "user_id", userId)
                .and(group -> group
                        .like("title", keyword)
                        .or()
                        .like("content", keyword)
                        .or()
                        .like("filename", keyword))
                .orderByAsc("chunk_index")
                .last("LIMIT " + Math.max(candidateTopK, 1));
        return ragUnitMapper.selectList(wrapper);
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

    private String normalizeKeywordQuery(String query) {
        if (query == null) {
            return "";
        }
        String normalized = query.trim();
        normalized = normalized.replaceAll("^(请问|请介绍一下|请介绍|介绍一下|请解释一下|请解释|解释一下|请说明一下|请说明|说明一下|什么是)", "");
        normalized = normalized.replaceAll("(是什么|是啥|什么意思|含义是什么|定义是什么|的定义|的含义|吗|呢)$", "");
        normalized = normalized.replaceAll("[？?。！!，,；;：:\"“”‘’（）()【】\\[\\]]", " ");
        normalized = normalized.replaceAll("\\s+", " ").trim();
        return normalized;
    }

    private List<RagUnit> expandSectionCandidates(List<Document> summaryCandidates, Map<String, RagUnit> summaryUnitMap) {
        Set<String> sectionIds = new LinkedHashSet<>();
        Set<String> docIds = new LinkedHashSet<>();

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

        // 命中 doc summary 时，需要展开它下面的 section；命中 section summary 时可直接进入下一轮。
        List<RagUnit> sections = new ArrayList<>();
        if (!sectionIds.isEmpty()) {
            sections.addAll(selectUnitsByIds(new ArrayList<>(sectionIds)));
        }
        if (!docIds.isEmpty()) {
            sections.addAll(selectChildrenByParentIds(null, new ArrayList<>(docIds), RagNodeType.SECTION_SUMMARY));
        }

        Map<String, RagUnit> deduped = new LinkedHashMap<>();
        for (RagUnit section : sections) {
            deduped.put(section.getId(), section);
        }
        return new ArrayList<>(deduped.values());
    }

    private List<Document> buildDocuments(List<RagUnit> units) {
        List<Document> documents = new ArrayList<>();
        for (RagUnit unit : units) {
            if (unit.getContent() == null || unit.getContent().isBlank()) {
                continue;
            }
            // 这里重新组装 Document，是为了把数据库节点恢复成可供 rerank 的统一输入。
            documents.add(new Document(unit.getId(), unit.getContent(),
                    ragUnitService.buildVectorMetadata(unit, unit.getFilename())));
        }
        return documents;
    }

    private List<HierarchyHit> buildHierarchyHits(List<Document> finalDocs,
                                                  Map<String, RagUnit> leafUnitMap,
                                                  Map<String, RagUnit> sectionUnitMap,
                                                  Map<String, RagUnit> docUnitMap,
                                                  Map<String, Double> sectionScores,
                                                  Map<String, Double> leafScores) {
        List<HierarchyHit> hits = new ArrayList<>();
        for (Document doc : finalDocs) {
            RagUnit leafUnit = leafUnitMap.get(doc.getId());
            if (leafUnit == null) {
                continue;
            }

            RagUnit sectionUnit = leafUnit.getParentId() != null
                    ? sectionUnitMap.get(leafUnit.getParentId())
                    : null;
            RagUnit docUnit = sectionUnit != null && sectionUnit.getParentId() != null
                    ? docUnitMap.get(sectionUnit.getParentId())
                    : null;

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

    private List<RagUnit> selectUnitsByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ragUnitMapper.selectBatchIds(ids);
    }

    private List<RagUnit> selectChildrenByParentIds(String userId, Collection<String> parentIds, RagNodeType nodeType) {
        if (parentIds == null || parentIds.isEmpty()) {
            return List.of();
        }
        QueryWrapper<RagUnit> wrapper = new QueryWrapper<>();
        wrapper.in("parent_id", parentIds)
                .eq("node_type", nodeType.name())
                .eq(userId != null && !userId.isBlank(), "user_id", userId)
                .orderByAsc("ordinal")
                .orderByAsc("chunk_index");
        return ragUnitMapper.selectList(wrapper);
    }

    private Map<String, RagUnit> toUnitMap(List<RagUnit> units) {
        if (units == null || units.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, RagUnit> map = new HashMap<>();
        for (RagUnit unit : units) {
            map.put(unit.getId(), unit);
        }
        return map;
    }

    private List<String> extractIds(List<Document> documents) {
        List<String> ids = new ArrayList<>();
        for (Document document : documents) {
            if (document.getId() != null) {
                ids.add(document.getId());
            }
        }
        return ids;
    }

    @CircuitBreaker(name = "dashscope-rerank", fallbackMethod = "rerankFallback")
    public ScoredDocumentsResult rerank(String query, List<Document> candidates, int topK) {
        try {
            RerankRequest request = new RerankRequest(query, candidates);
            RerankResponse response = rerankModel.call(request);

            List<DocumentWithScore> scoredDocs = response.getResults();
            List<Document> result = new ArrayList<>();
            Map<String, Double> scoreById = new HashMap<>();
            Double topScore = null;

            for (int i = 0; i < Math.min(topK, scoredDocs.size()); i++) {
                DocumentWithScore dws = scoredDocs.get(i);
                Document output = dws.getOutput();
                result.add(output);
                scoreById.put(output.getId(), dws.getScore());
                if (i == 0) {
                    topScore = dws.getScore();
                }
            }

            return new ScoredDocumentsResult(result, scoreById, topScore);
        } catch (Exception e) {
            log.warn("Rerank 调用失败，回退为原始排序: {}", e.getMessage());
            List<Document> fallback = candidates.stream()
                    .limit(topK)
                    .collect(Collectors.toList());
            Map<String, Double> scoreById = new HashMap<>();
            for (Document doc : fallback) {
                scoreById.put(doc.getId(), doc.getScore());
            }
            Double fallbackScore = (!fallback.isEmpty() && fallback.get(0).getScore() != null)
                    ? fallback.get(0).getScore() : null;
            return new ScoredDocumentsResult(fallback, scoreById, fallbackScore);
        }
    }

    public ScoredDocumentsResult rerankFallback(String query, List<Document> candidates, int topK, Throwable t) {
        log.warn("rerank 熔断降级，回退为原始排序: error={}", t.getMessage());
        List<Document> fallback = candidates.stream()
                .limit(topK)
                .collect(Collectors.toList());
        Map<String, Double> scoreById = new HashMap<>();
        for (Document doc : fallback) {
            scoreById.put(doc.getId(), doc.getScore());
        }
        Double fallbackScore = (!fallback.isEmpty() && fallback.get(0).getScore() != null)
                ? fallback.get(0).getScore() : null;
        return new ScoredDocumentsResult(fallback, scoreById, fallbackScore);
    }

    static class ScoredDocumentsResult {
        private final List<Document> documents;
        private final Map<String, Double> scoreById;
        private final Double topScore;

        ScoredDocumentsResult(List<Document> documents, Map<String, Double> scoreById, Double topScore) {
            this.documents = documents;
            this.scoreById = scoreById;
            this.topScore = topScore;
        }
    }

    private String buildUserFilterExpression(String userId) {
        if (userId == null || userId.isBlank()) {
            return "";
        }
        return "user_id == '" + escapeRedisTagValue(userId) + "'";
    }

    private String escapeRedisTagValue(String rawValue) {
        StringBuilder escaped = new StringBuilder(rawValue.length());
        for (int i = 0; i < rawValue.length(); i++) {
            char current = rawValue.charAt(i);
            if (Character.isLetterOrDigit(current) || current == '_') {
                escaped.append(current);
                continue;
            }
            escaped.append('\\').append(current);
        }
        return escaped.toString();
    }
}

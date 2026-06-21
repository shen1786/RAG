package com.example.demo.service;

import com.example.demo.Config.HierarchyConfig;
import com.example.demo.model.RagNodeType;
import com.example.demo.model.RagUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 层级索引服务 —— 把扁平的叶子切块组装成三层摘要树（叶子 → 章节摘要 → 文档摘要）。
 *
 * <p><b>通俗解释：</b>想象你在整理一本教材。你不会把每一页单独扔进书架，
 * 而是先做章节目录，再做全书大纲。这个类就是"自动做目录和大纲"的服务。</p>
 *
 * <p><b>三层树结构：</b></p>
 * <pre>
 *                    ┌─────────────────────┐
 *                    │   DOC_SUMMARY (L2)  │  ← 文档摘要（整篇文档的浓缩）
 *                    │   "本文介绍了..."    │
 *                    └────────┬────────────┘
 *               ┌─────────────┼─────────────┐
 *        ┌──────┴──────┐ ┌───┴────┐  ┌──────┴──────┐
 *        │SECTION (L1) │ │SECTION │  │SECTION (L1) │  ← 章节摘要（每几段的概括）
 *        │"第一章讲了.."│ │"第二章.."│  │"第三章.."   │
 *        └──┬──┬──┬────┘ └──┬──┬──┘  └──┬──┬──┬────┘
 *         ┌─┘  │  └─┐     ┌─┘  └─┐    ┌─┘  │  └─┐
 *        LEAF LEAF LEAF  LEAF  LEAF  LEAF LEAF LEAF   ← 叶子节点（原始文本切块）
 * </pre>
 *
 * <p><b>为什么要分层：</b>检索时可以"先粗后细"——先在文档摘要层判断哪篇文档相关，
 * 再在章节摘要层定位到哪个章节，最后在叶子层找到具体段落。
 * 比直接在几千个叶子切块里暴力搜索，召回质量高很多。</p>
 *
 * @see HierarchySummaryService 负责调用大模型生成摘要
 * @see HierarchyConfig 控制是否启用层级索引、每组叶子数量等参数
 */
@Service
@Slf4j
public class HierarchicalIndexingService {

    private final HierarchyConfig hierarchyConfig;
    private final HierarchySummaryService hierarchySummaryService;

    /**
     * 构造注入。
     *
     * @param hierarchyConfig          层级索引的配置（是否启用、每组叶子数等）
     * @param hierarchySummaryService  摘要生成服务（调用大模型总结文本）
     */
    public HierarchicalIndexingService(HierarchyConfig hierarchyConfig,
                                       HierarchySummaryService hierarchySummaryService) {
        this.hierarchyConfig = hierarchyConfig;
        this.hierarchySummaryService = hierarchySummaryService;
    }

    /**
     * 构建层级摘要树的核心方法 —— 把扁平的叶子切块变成三层树结构。
     *
     * <p><b>通俗解释：</b>你给我一堆散页（叶子切块），我帮你装订成一本书，
     * 自动加上章节目录和全书大纲。</p>
     *
     * <p><b>处理流程：</b></p>
     * <ol>
     *   <li>规范化叶子节点 — 补齐 ID、sourceId、nodeType 等字段</li>
     *   <li>如果层级索引未启用，直接返回扁平的叶子列表（退化为普通模式）</li>
     *   <li>每 N 个叶子分为一组，调用大模型生成章节摘要（section nodes）</li>
     *   <li>把所有章节摘要再送给大模型，生成整篇文档的摘要（document node）</li>
     *   <li>建立父子关系：叶子 → 章节 → 文档，组装成完整树</li>
     * </ol>
     *
     * @param sourceId   文档的唯一标识（UUID）
     * @param filename   原始文件名（用于生成摘要时的上下文提示）
     * @param leafUnits  MediaProcessor 解析出的叶子切块列表
     * @return 完整的节点列表（叶子 + 章节摘要 + 文档摘要），可直接批量写入数据库
     */
    public List<RagUnit> buildHierarchy(String sourceId, String filename, List<RagUnit> leafUnits) {
        // ---- 第 1 步：规范化叶子节点 ----
        // processor 只负责切内容，树结构字段（ID、nodeType、treeLevel 等）在这里统一补齐
        List<RagUnit> normalizedLeaves = normalizeLeaves(sourceId, leafUnits);

        // ---- 第 2 步：判断是否启用层级索引 ----
        // 如果配置关闭了层级索引，直接返回扁平的叶子列表，不构建摘要树
        if (!hierarchyConfig.isEnabled() || normalizedLeaves.isEmpty()) {
            return normalizedLeaves;
        }

        // ---- 第 3 步：构建章节摘要节点（第一层摘要） ----
        // 每 N 个叶子分为一组，调用大模型生成该组的标题 + 摘要
        List<RagUnit> sectionNodes = buildSectionNodes(sourceId, filename, normalizedLeaves);

        // ---- 第 4 步：构建文档摘要节点（第二层摘要） ----
        // 把所有章节摘要再送给大模型，生成整篇文档的标题 + 摘要
        RagUnit docNode = buildDocumentNode(sourceId, filename, sectionNodes);

        // ---- 第 5 步：建立章节 → 文档的父子关系 ----
        // 所有章节节点挂到文档根节点下面，便于后续按 parentId 向上/向下回溯
        for (RagUnit sectionNode : sectionNodes) {
            sectionNode.setParentId(docNode.getId());
        }

        // ---- 第 6 步：组装所有节点返回 ----
        List<RagUnit> allNodes = new ArrayList<>(normalizedLeaves.size() + sectionNodes.size() + 1);
        allNodes.addAll(normalizedLeaves);   // 叶子层（L0）
        allNodes.addAll(sectionNodes);       // 章节摘要层（L1）
        allNodes.add(docNode);               // 文档摘要层（L2）
        return allNodes;
    }

    /**
     * 统计列表中叶子节点的数量。
     *
     * <p><b>用途：</b>处理成功后，需要记录"这个文档切了多少块"，写入 document_file 表。</p>
     */
    public int countLeafNodes(List<RagUnit> units) {
        int count = 0;
        for (RagUnit unit : units) {
            if (unit.getNodeType() == RagNodeType.LEAF) {
                count++;
            }
        }
        return count;
    }

    /**
     * 规范化叶子节点 —— 补齐 MediaProcessor 没有设置的树结构字段。
     *
     * <p><b>为什么要规范化：</b>MediaProcessor 只负责"切内容"（填 content、filename 等），
     * 树结构相关的字段（id、nodeType、treeLevel、ordinal）在这里统一补齐，
     * 保证后续构建摘要树时数据一致。</p>
     */
    private List<RagUnit> normalizeLeaves(String sourceId, List<RagUnit> leafUnits) {
        List<RagUnit> normalizedLeaves = new ArrayList<>();
        if (leafUnits == null) {
            return normalizedLeaves;
        }

        for (int i = 0; i < leafUnits.size(); i++) {
            RagUnit leaf = leafUnits.get(i);
            // processor 只负责切内容；树结构相关字段统一在这里补齐
            leaf.setId(UUID.randomUUID().toString());
            leaf.setSourceId(sourceId);
            leaf.setNodeType(RagNodeType.LEAF);   // 标记为叶子节点
            leaf.setTreeLevel(0);                  // 叶子在第 0 层
            leaf.setOrdinal(i);                    // 在兄弟节点中的排序
            leaf.setChildCount(0);                 // 叶子没有子节点
            leaf.setParentId(null);                // 父节点后面由 buildSectionNodes 回填
            if (leaf.getChunkIndex() == null) {
                leaf.setChunkIndex(i);
            }
            normalizedLeaves.add(leaf);
        }
        return normalizedLeaves;
    }

    /**
     * 构建章节摘要节点 —— 把叶子按固定窗口分组，每组调用大模型生成摘要。
     *
     * <p><b>通俗解释：</b>比如有 20 个叶子切块，每 5 个一组，就会生成 4 个章节摘要节点。
     * 每个章节摘要就是"这 5 段话在讲什么"的一句话概括。</p>
     *
     * <p><b>当前策略：</b>按固定窗口大小分组（由 hierarchyConfig.midChildCount 控制）。
     * 后续可升级为语义分组（按内容相似度动态划分）。</p>
     */
    private List<RagUnit> buildSectionNodes(String sourceId, String filename, List<RagUnit> leaves) {
        List<RagUnit> sectionNodes = new ArrayList<>();
        // 每组叶子数量，由配置决定（默认值在 HierarchyConfig 中）
        int groupSize = Math.max(1, hierarchyConfig.getMidChildCount());

        // 按固定窗口滑动，每组生成一个章节摘要
        for (int start = 0, sectionIndex = 0; start < leaves.size(); start += groupSize, sectionIndex++) {
            int end = Math.min(start + groupSize, leaves.size());
            List<RagUnit> group = leaves.subList(start, end);

            // 调用大模型，生成这一组的标题 + 摘要文本
            HierarchySummaryService.SummaryPayload payload =
                    hierarchySummaryService.summarizeSection(filename, sectionIndex, group);

            // 创建章节摘要节点
            RagUnit sectionNode = new RagUnit();
            sectionNode.setId(UUID.randomUUID().toString());
            sectionNode.setSourceId(sourceId);
            sectionNode.setSourceType(group.get(0).getSourceType());
            sectionNode.setNodeType(RagNodeType.SECTION_SUMMARY);  // 标记为章节摘要
            sectionNode.setTreeLevel(1);           // 章节摘要在第 1 层
            sectionNode.setOrdinal(sectionIndex);  // 第几个章节
            sectionNode.setTitle(payload.getTitle());
            sectionNode.setContent(payload.getSummary());
            sectionNode.setChildCount(group.size());  // 包含多少个叶子
            sectionNode.setChunkIndex(null);           // 摘要节点没有 chunkIndex

            // 叶子节点回填父节点 ID，检索命中章节时可以直接展开到叶子层
            for (RagUnit leaf : group) {
                leaf.setParentId(sectionNode.getId());
            }

            sectionNodes.add(sectionNode);
        }

        return sectionNodes;
    }

    /**
     * 构建文档摘要节点 —— 把所有章节摘要再送给大模型，生成整篇文档的概括。
     *
     * <p><b>通俗解释：</b>章节摘要相当于"每章的读后感"，文档摘要相当于"整本书的读后感"。
     * 它是树的根节点，检索时先匹配这里，判断哪篇文档最相关。</p>
     *
     * <p><b>为什么不直接用原文：</b>整篇原文可能很长，直接送给模型会超 token 限制。
     * 所以只消费章节摘要（已经压缩过的），既省 token 又保留了核心信息。</p>
     */
    private RagUnit buildDocumentNode(String sourceId, String filename, List<RagUnit> sectionNodes) {
        // 根摘要只消费 section summary，避免再次把整篇原文直接送给模型
        HierarchySummaryService.SummaryPayload payload =
                hierarchySummaryService.summarizeDocument(filename, sectionNodes);

        RagUnit docNode = new RagUnit();
        docNode.setId(UUID.randomUUID().toString());
        docNode.setSourceId(sourceId);
        docNode.setSourceType(sectionNodes.get(0).getSourceType());
        docNode.setNodeType(RagNodeType.DOC_SUMMARY);  // 标记为文档摘要
        docNode.setTreeLevel(2);           // 文档摘要在第 2 层（最顶层）
        docNode.setOrdinal(0);             // 文档摘要只有一个，序号为 0
        docNode.setTitle(payload.getTitle());
        docNode.setContent(payload.getSummary());
        docNode.setChildCount(sectionNodes.size());  // 包含多少个章节摘要
        docNode.setChunkIndex(null);
        docNode.setParentId(null);         // 根节点没有父节点
        return docNode;
    }
}

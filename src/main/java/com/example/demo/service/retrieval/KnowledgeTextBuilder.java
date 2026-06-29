package com.example.demo.service.retrieval;

import com.example.demo.model.RagUnit;
import com.example.demo.repository.RagUnitQueryRepository;
import com.example.demo.service.RagUnitService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识文本构建器，负责将检索命中的文档和节点格式化为可注入 System Prompt 的文本。
 * <p>
 * 从 RagRetrievalService 中抽取的共享工具方法。
 */
@Component
@RequiredArgsConstructor
public class KnowledgeTextBuilder {

    private static final int CONTEXT_NEIGHBOR_BEFORE = 1;
    private static final int CONTEXT_NEIGHBOR_AFTER = 2;

    private final RagUnitQueryRepository ragUnitQueryRepository;
    private final RagUnitService ragUnitService;

    /**
     * 扩展命中的叶子节点上下文（前后各 N 个邻居），并格式化为知识文本。
     *
     * <h3>为什么需要上下文扩展？</h3>
     * <p>向量搜索命中的叶子节点可能只是一个段落或句子，缺乏上下文信息。
     * 通过获取该叶子节点的前后邻居，可以提供更完整的语境，帮助 LLM 更好地理解和回答问题。</p>
     *
     * <h3>上下文窗口配置</h3>
     * <ul>
     *   <li>CONTEXT_NEIGHBOR_BEFORE = 1：获取前 1 个邻居</li>
     *   <li>CONTEXT_NEIGHBOR_AFTER = 2：获取后 2 个邻居</li>
     * </ul>
     * <p>为什么后邻居多于前邻居？因为用户阅读习惯是从上到下，后文通常能提供更多补充信息。</p>
     *
     * <h3>流程</h3>
     * <pre>
     * rankedDocs (rerank 后的文档列表) + matchedLeafUnits (对应的 RagUnit 实体)
     *   │
     *   ▼
     * 将 matchedLeafUnits 转为 Map，便于快速查找
     *   │
     *   ▼
     * 遍历 rankedDocs，为每个命中的叶子节点获取前后邻居
     *   │
     *   ├─ 查询 selectNeighborLeaves(leaf, before=1, after=2)
     *   │   返回: [前1个邻居, 命中节点, 后2个邻居] 共 4 个节点
     *   │
     *   ▼
     * 将所有节点（命中节点+邻居）放入 expandedUnits Map（按ID去重）
     *   │
     *   ▼
     * 查询每个节点的父章节节点（用于展示【章节】标签）
     *   │
     *   ▼
     * 格式化为知识文本，每个节点格式：
     *   【文档】文件名
     *   【章节】章节标题
     *   【标题】节点标题
     *   【分段】分段索引
     *   正文内容
     *   │
     *   ▼
     * 多个节点之间用 "\n\n---\n\n" 分隔
     * </pre>
     *
     * @param rankedDocs        rerank 后的文档列表（按相关度排序）
     * @param matchedLeafUnits  对应的 RagUnit 实体列表
     * @return 格式化的知识文本，用于注入 LLM 的 System Prompt
     */
    public String buildExpandedKnowledgeText(List<Document> rankedDocs, List<RagUnit> matchedLeafUnits) {
        // ── Step 1: 构建叶子节点 Map ──
        // 将 RagUnit 列表转为 Map<ID, RagUnit>，便于根据文档ID快速查找对应的实体
        Map<String, RagUnit> matchedLeafMap = RagUnitQueryRepository.toUnitMap(matchedLeafUnits);

        // ── Step 2: 扩展上下文窗口 ──
        // expandedUnits 使用 LinkedHashMap 保持插入顺序，确保最终文本按相关度排序
        // 同时使用 putIfAbsent 去重，避免同一个节点被重复添加
        Map<String, RagUnit> expandedUnits = new LinkedHashMap<>();

        for (Document doc : rankedDocs) {
            // 查找文档ID对应的 RagUnit 实体
            RagUnit matchedLeaf = matchedLeafMap.get(doc.getId());
            if (matchedLeaf == null) {
                continue;
            }

            // ── 查询前后邻居 ──
            // selectNeighborLeaves 会返回：
            //   - CONTEXT_NEIGHBOR_BEFORE (1) 个前邻居
            //   - 命中的叶子节点本身
            //   - CONTEXT_NEIGHBOR_AFTER (2) 个后邻居
            // 总共最多 4 个节点
            // 邻居查询基于 (sourceId, chunkIndex) 的连续性，确保是同一文档的相邻分段
            List<RagUnit> contextLeaves = ragUnitQueryRepository.selectNeighborLeaves(
                    matchedLeaf, CONTEXT_NEIGHBOR_BEFORE, CONTEXT_NEIGHBOR_AFTER);

            if (contextLeaves.isEmpty()) {
                // 如果查询邻居失败（可能是单段文档），至少保留命中节点本身
                expandedUnits.putIfAbsent(matchedLeaf.getId(), matchedLeaf);
                continue;
            }

            // ── 将邻居节点加入扩展集合 ──
            // putIfAbsent 确保同一个节点不会被重复添加
            // 这样即使多个命中的叶子节点有重叠的邻居，也不会出现重复内容
            for (RagUnit contextLeaf : contextLeaves) {
                if (contextLeaf.getId() != null) {
                    expandedUnits.putIfAbsent(contextLeaf.getId(), contextLeaf);
                }
            }
        }

        // ── Step 3: 空结果降级 ──
        // 如果扩展后的节点集合为空（理论上不应发生），直接使用原始文档文本
        // 使用 "\n\n---\n\n" 分隔多个文档，便于 LLM 区分不同来源
        if (expandedUnits.isEmpty()) {
            return rankedDocs.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n\n---\n\n"));
        }

        // ── Step 4: 查询父章节节点 ──
        // 为每个叶子节点查询其父节点（SECTION_SUMMARY 或 DOC_SUMMARY）
        // 用于在知识文本中展示【章节】标签，帮助 LLM 理解文档结构
        Map<String, RagUnit> parentSections = ragUnitQueryRepository.selectParentSections(expandedUnits.values());

        // ── Step 5: 格式化知识文本 ──
        // 遍历所有扩展节点，格式化为结构化的知识文本
        // 每个节点包含：【文档】【章节】【标题】【分段】标签 + 正文内容
        // 多个节点之间用 "\n\n---\n\n" 分隔
        return expandedUnits.values().stream()
                .map(unit -> formatKnowledgeUnit(unit, findParentSection(unit, parentSections)))
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    /**
     * 将 RagUnit 列表转为 Document 列表（供 rerank 使用）。
     *
     * <h3>转换逻辑</h3>
     * <pre>
     * RagUnit (数据库实体)
     *   │
     *   ▼
     * 过滤掉 content 为空的记录
     *   │
     *   ▼
     * 转换为 Spring AI Document 对象：
     *   - id: RagUnit.id (UUID)
     *   - text: RagUnit.content (文本内容)
     *   - metadata: ragUnitService.buildVectorMetadata() 构建的元数据
     * </pre>
     *
     * <h3>元数据字段</h3>
     * <p>buildVectorMetadata 会构建以下元数据：</p>
     * <ul>
     *   <li>Tag 类型: source_id, source_type, unit_id, user_id, node_type, parent_id</li>
     *   <li>Text 类型: filename, title</li>
     *   <li>Numeric 类型: tree_level, child_count, chunk_index, start_time, end_time</li>
     * </ul>
     *
     * @param units RagUnit 列表
     * @return Document 列表，可直接用于 rerank 调用
     */
    public List<Document> buildDocuments(List<RagUnit> units) {
        List<Document> documents = new ArrayList<>();
        for (RagUnit unit : units) {
            // 跳过内容为空的记录，避免无效文档参与检索
            if (unit.getContent() == null || unit.getContent().isBlank()) {
                continue;
            }
            // 构建 Spring AI Document 对象
            // id: 用于后续根据文档ID查询 RagUnit 实体
            // text: 用于 rerank 模型计算语义相关度
            // metadata: 用于向量搜索时的过滤和排序
            documents.add(new Document(unit.getId(), unit.getContent(),
                    ragUnitService.buildVectorMetadata(unit, unit.getFilename())));
        }
        return documents;
    }

    /**
     * 从 Document 列表提取 ID。
     *
     * <h3>用途</h3>
     * <p>在 rerank 之后，需要根据文档ID查询完整的 RagUnit 实体，
     * 用于构建 HierarchyHit 列表和扩展上下文窗口。</p>
     *
     * @param documents Document 列表
     * @return 文档ID列表
     */
    public List<String> extractIds(List<Document> documents) {
        List<String> ids = new ArrayList<>();
        for (Document document : documents) {
            if (document.getId() != null) {
                ids.add(document.getId());
            }
        }
        return ids;
    }

    /**
     * 批量按 ID 查询 RagUnit 并转为 Map。
     *
     * <h3>用途</h3>
     * <p>在构建 HierarchyHit 列表时，需要根据文档ID快速查找对应的 RagUnit 实体，
     * 获取 sourceId、filename、minioUrl 等字段用于引文展示。</p>
     *
     * @param ids 文档ID列表
     * @return Map<ID, RagUnit>，便于 O(1) 复杂度查找
     */
    public Map<String, RagUnit> selectUnitsAsMap(List<String> ids) {
        return RagUnitQueryRepository.toUnitMap(ragUnitQueryRepository.selectByIds(ids));
    }

    /**
     * 查找叶子节点的父章节节点。
     *
     * <h3>层级结构</h3>
     * <pre>
     * DOC_SUMMARY (文档摘要)
     *   │
     *   ├─ SECTION_SUMMARY (章节摘要)  ← 父节点
     *   │    │
     *   │    ├─ LEAF (叶子节点)  ← 当前节点
     *   │    ├─ LEAF
     *   │    └─ LEAF
     *   │
     *   └─ SECTION_SUMMARY
     *        ├─ LEAF
     *        └─ LEAF
     * </pre>
     *
     * @param unit           当前叶子节点
     * @param parentSections 父章节节点 Map (ID → RagUnit)
     * @return 父章节节点，如果 parentId 为空或不存在则返回 null
     */
    private RagUnit findParentSection(RagUnit unit, Map<String, RagUnit> parentSections) {
        // 如果叶子节点没有 parentId（理论上不应发生），返回 null
        if (unit.getParentId() == null || unit.getParentId().isBlank()) {
            return null;
        }
        // 从 Map 中查找父节点，如果不存在（已被删除或数据不一致）返回 null
        return parentSections.get(unit.getParentId());
    }

    /**
     * 格式化单个知识单元为结构化文本。
     *
     * <h3>输出格式</h3>
     * <pre>
     * 【文档】RagUnitService.java
     * 【章节】核心服务
     * 【标题】依赖注入
     * 【分段】3
     * @Autowired
     * private final RagUnitRepository ragUnitRepository;
     * ...
     * </pre>
     *
     * <h3>格式说明</h3>
     * <ul>
     *   <li>【文档】— 原始文件名，用于引文展示和 PDF 预览定位</li>
     *   <li>【章节】— 父章节标题，帮助 LLM 理解文档结构</li>
     *   <li>【标题】— 当前节点标题，用于定位具体段落</li>
     *   <li>【分段】— 分段索引（从1开始），用于精确引用</li>
     *   <li>正文内容 — 实际的文档内容</li>
     * </ul>
     *
     * @param unit          当前叶子节点
     * @param parentSection 父章节节点（可为 null）
     * @return 格式化的知识单元文本
     */
    private String formatKnowledgeUnit(RagUnit unit, RagUnit parentSection) {
        StringBuilder builder = new StringBuilder();

        // ── 【文档】标签 ──
        // 原始文件名，用于前端展示和 PDF 预览时定位文件
        if (unit.getFilename() != null && !unit.getFilename().isBlank()) {
            builder.append("【文档】").append(unit.getFilename()).append('\n');
        }

        // ── 【章节】标签 ──
        // 父章节标题，帮助 LLM 理解文档的层级结构
        // 例如：在回答关于"依赖注入"的问题时，知道它属于"核心服务"章节会更有帮助
        if (parentSection != null && parentSection.getTitle() != null && !parentSection.getTitle().isBlank()) {
            builder.append("【章节】").append(parentSection.getTitle()).append('\n');
        }

        // ── 【标题】标签 ──
        // 当前节点的标题，通常是段落的小标题或主题
        if (unit.getTitle() != null && !unit.getTitle().isBlank()) {
            builder.append("【标题】").append(unit.getTitle()).append('\n');
        }

        // ── 【分段】标签 ──
        // 分段索引（从1开始，而非0），用于精确引用和前端展示
        // 例如：用户问"第3段说了什么"，LLM 可以根据分段索引定位
        if (unit.getChunkIndex() != null) {
            builder.append("【分段】").append(unit.getChunkIndex() + 1).append('\n');
        }

        // ── 正文内容 ──
        // 实际的文档内容，LLM 会基于这些内容生成回答
        if (unit.getContent() != null) {
            builder.append(unit.getContent());
        }

        // 去除首尾空白，确保格式整洁
        return builder.toString().trim();
    }
}

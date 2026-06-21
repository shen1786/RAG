package com.example.demo.service;

import com.example.demo.model.RagNodeType;
import com.example.demo.model.RagUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 向量写入服务 —— 负责把 RagUnit 写入或删除 Redis 向量索引。
 *
 * <h3>背景：为什么需要这个类？</h3>
 * <p>系统有<strong>两个 Redis 向量索引</strong>（即"双索引"）：</p>
 * <ul>
 *   <li><b>leafVectorStore（叶子索引）</b> — 存放文档拆分后的细粒度片段（如一个段落），用于精确匹配</li>
 *   <li><b>summaryVectorStore（摘要索引）</b> — 存放章节摘要和文档摘要，用于层次检索时先粗筛再下钻</li>
 * </ul>
 * <p>本类就是统一管理这两个索引的写入和删除，避免 RagUnitService 自己处理分批、重试等细节。</p>
 *
 * <h3>整体写入流程</h3>
 * <pre>
 * 调用方（RagUnitService）
 *       │
 *       ▼
 * addUnitsToVectorStores(units, filename)
 *       │
 *       ├─ 遍历每个 RagUnit，按 nodeType 分流：
 *       │    ├─ LEAF / null  → leafDocuments 列表
 *       │    └─ 其他（摘要） → summaryDocuments 列表
 *       │
 *       ├─ batchAdd(leafVectorStore, leafDocuments)
 *       └─ batchAdd(summaryVectorStore, summaryDocuments)
 *              │
 *              ▼
 *         按 10 条一批切分 → addBatchWithRetry()
 *              │
 *              ├─ 整批写入成功 → 结束
 *              ├─ 失败 → 重试最多 3 次（线性退避 200ms×attempt）
 *              └─ 3 次都失败 → 降级为逐条写入（每条仍重试 3 次）
 * </pre>
 *
 * @see RagUnitService
 * @see com.example.demo.Config.VectorStoreConfig
 */
@Slf4j
@Service
public class VectorStoreWriteService {

    private static final int VECTOR_BATCH_SIZE = 10;
    private static final int VECTOR_ADD_MAX_RETRIES = 3;
    private static final long VECTOR_ADD_RETRY_DELAY_MS = 200L;

    private final VectorStore leafVectorStore;
    private final VectorStore summaryVectorStore;
    private final RagUnitService ragUnitService;

    public VectorStoreWriteService(
            @Qualifier("leafVectorStore") VectorStore leafVectorStore,
            @Qualifier("summaryVectorStore") VectorStore summaryVectorStore,
            @Lazy RagUnitService ragUnitService) {
        this.leafVectorStore = leafVectorStore;
        this.summaryVectorStore = summaryVectorStore;
        this.ragUnitService = ragUnitService;
    }

    /**
     * 将一批 RagUnit 写入对应的向量索引。
     *
     * <p><b>做了什么：</b>遍历 units，先跳过内容为空的，然后把每个 unit 转成向量 Document
     * （ID + 内容 + 元数据），根据节点类型放进"叶子队列"或"摘要队列"，最后分别批量写入。</p>
     *
     * <p><b>分流规则：</b></p>
     * <ul>
     *   <li>nodeType 是 {@code LEAF} 或 {@code null}（未标记的一律当叶子）→ 写入 leafVectorStore</li>
     *   <li>nodeType 是 {@code DOC_SUMMARY} / {@code SECTION_SUMMARY} → 写入 summaryVectorStore</li>
     * </ul>
     *
     * @param units    待写入的 RagUnit 列表（通常由 {@link HierarchicalIndexingService} 生成）
     * @param filename 原始文件名，会作为元数据随向量一起存储，检索时用于展示来源
     */
    public void addUnitsToVectorStores(List<RagUnit> units, String filename) {
        List<Document> leafDocuments = new ArrayList<>();
        List<Document> summaryDocuments = new ArrayList<>();

        // 遍历每个 RagUnit，转成向量 Document 并按类型分流
        for (RagUnit unit : units) {
            // 跳过没有实际内容的节点（理论上不应出现，防御性处理）
            if (unit.getContent() == null || unit.getContent().isBlank()) {
                continue;
            }

            // 构建向量元数据（包含 filename、nodeType、chunkIndex 等，检索时用于展示）
            Map<String, Object> metadata = ragUnitService.buildVectorMetadata(unit, filename);
            Document document = new Document(unit.getId(), unit.getContent(), metadata);

            // 分流：叶子节点 → leafDocuments，摘要节点 → summaryDocuments
            if (unit.getNodeType() == RagNodeType.LEAF || unit.getNodeType() == null) {
                leafDocuments.add(document);
            } else {
                summaryDocuments.add(document);
            }
        }

        // 分别批量写入两个索引（各自独立，互不影响）
        if (!leafDocuments.isEmpty()) {
            batchAdd(leafVectorStore, leafDocuments);
        }
        if (!summaryDocuments.isEmpty()) {
            batchAdd(summaryVectorStore, summaryDocuments);
        }
    }

    /**
     * 从两个向量索引中删除指定 ID 的文档。
     *
     * <p><b>为什么两个索引都删？</b>因为我们不确定这个 ID 存在于哪个索引里，
     * 干脆两个都删一遍，不存在的 ID 会被 Redis 静默忽略，不会有副作用。</p>
     *
     * @param ids 待删除的文档 ID 列表；为 null 或空时直接返回
     */
    public void deleteFromVectorStores(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        leafVectorStore.delete(ids);
        summaryVectorStore.delete(ids);
    }

    /**
     * 将文档列表按 10 条一组分批写入向量索引。
     * 每批独立重试，某批失败不影响其他批次。
     */
    private void batchAdd(VectorStore vectorStore, List<Document> documents) {
        for (int i = 0; i < documents.size(); i += VECTOR_BATCH_SIZE) {
            List<Document> batch = documents.subList(i, Math.min(i + VECTOR_BATCH_SIZE, documents.size()));
            addBatchWithRetry(vectorStore, batch);
        }
    }

    /**
     * 带重试的批量写入，失败后降级为逐条写入。
     *
     * <p><b>容错策略：</b></p>
     * <ol>
     *   <li>先尝试整批写入，最多重试 3 次（间隔递增：200ms、400ms、600ms）</li>
     *   <li>如果整批 3 次都失败且 batch 只有 1 条，直接抛异常（无需降级）</li>
     *   <li>如果 batch 有多条，降级为逐条写入 —— 可能是某条数据有问题导致整批失败，
     *       逐条写入可以跳过坏数据，把能写的写进去</li>
     *   <li>最终只抛出确实失败的 documentId，方便排查</li>
     * </ol>
     */
    private void addBatchWithRetry(VectorStore vectorStore, List<Document> batch) {
        RuntimeException batchFailure = null;

        // 阶段一：整批重试
        for (int attempt = 1; attempt <= VECTOR_ADD_MAX_RETRIES; attempt++) {
            try {
                vectorStore.add(batch);
                return; // 整批成功，直接返回
            } catch (RuntimeException e) {
                batchFailure = e;
                log.warn("向量批量写入失败，准备重试: attempt={}/{}, size={}, firstDocumentId={}",
                        attempt, VECTOR_ADD_MAX_RETRIES, batch.size(), batch.get(0).getId(), e);
                sleepBeforeRetry(attempt);
            }
        }

        // 只有 1 条且失败了，没有降级的意义，直接抛
        if (batch.size() == 1) {
            throw new RuntimeException("向量写入失败，documentId=" + batch.get(0).getId(), batchFailure);
        }

        // 阶段二：降级为逐条写入
        log.warn("向量批量写入持续失败，降级为逐条写入: size={}, firstDocumentId={}",
                batch.size(), batch.get(0).getId(), batchFailure);

        List<String> failedDocumentIds = new ArrayList<>();
        RuntimeException singleFailure = null;
        for (Document document : batch) {
            try {
                addSingleWithRetry(vectorStore, document);
            } catch (RuntimeException e) {
                failedDocumentIds.add(document.getId());
                singleFailure = e;
            }
        }

        // 汇总失败的 ID 一起抛出，方便定位问题数据
        if (!failedDocumentIds.isEmpty()) {
            throw new RuntimeException("向量写入失败，documentIds=" + String.join(",", failedDocumentIds), singleFailure);
        }
    }

    /**
     * 单条文档写入，最多重试 3 次，全部失败则抛出异常。
     * 由 {@link #addBatchWithRetry} 在整批失败后调用，用于逐条降级写入。
     */
    private void addSingleWithRetry(VectorStore vectorStore, Document document) {
        RuntimeException lastFailure = null;

        for (int attempt = 1; attempt <= VECTOR_ADD_MAX_RETRIES; attempt++) {
            try {
                vectorStore.add(List.of(document));
                return;
            } catch (RuntimeException e) {
                lastFailure = e;
                log.warn("向量单条写入失败，准备重试: attempt={}/{}, documentId={}",
                        attempt, VECTOR_ADD_MAX_RETRIES, document.getId(), e);
                sleepBeforeRetry(attempt);
            }
        }

        throw new RuntimeException("向量写入失败，documentId=" + document.getId(), lastFailure);
    }

    /**
     * 重试前等待，延迟 = 200ms × 第几次重试（第1次等200ms，第2次等400ms，第3次等600ms）。
     * 线性递增是为了给 Redis 一个恢复窗口，同时不会等太久。
     */
    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(VECTOR_ADD_RETRY_DELAY_MS * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("向量写入重试被中断", e);
        }
    }
}

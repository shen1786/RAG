package com.example.demo.service;

import com.example.demo.model.RagNodeType;
import com.example.demo.model.RagUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 向量写入服务，封装 leaf/summary 双索引的批量写入和重试逻辑。
 * <p>
 * 从 RagUnitService 中抽取，使 RagUnitService 专注于文档编排和数据管理。
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
            RagUnitService ragUnitService) {
        this.leafVectorStore = leafVectorStore;
        this.summaryVectorStore = summaryVectorStore;
        this.ragUnitService = ragUnitService;
    }

    /**
     * 将 RagUnit 列表按节点类型分流写入叶子/摘要向量索引。
     */
    public void addUnitsToVectorStores(List<RagUnit> units, String filename) {
        List<Document> leafDocuments = new ArrayList<>();
        List<Document> summaryDocuments = new ArrayList<>();

        for (RagUnit unit : units) {
            if (unit.getContent() == null || unit.getContent().isBlank()) {
                continue;
            }

            Map<String, Object> metadata = ragUnitService.buildVectorMetadata(unit, filename);
            Document document = new Document(unit.getId(), unit.getContent(), metadata);

            if (unit.getNodeType() == RagNodeType.LEAF || unit.getNodeType() == null) {
                leafDocuments.add(document);
            } else {
                summaryDocuments.add(document);
            }
        }

        if (!leafDocuments.isEmpty()) {
            batchAdd(leafVectorStore, leafDocuments);
        }
        if (!summaryDocuments.isEmpty()) {
            batchAdd(summaryVectorStore, summaryDocuments);
        }
    }

    /**
     * 从两个向量索引中删除指定 ID 的文档。
     */
    public void deleteFromVectorStores(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        leafVectorStore.delete(ids);
        summaryVectorStore.delete(ids);
    }

    private void batchAdd(VectorStore vectorStore, List<Document> documents) {
        for (int i = 0; i < documents.size(); i += VECTOR_BATCH_SIZE) {
            List<Document> batch = documents.subList(i, Math.min(i + VECTOR_BATCH_SIZE, documents.size()));
            addBatchWithRetry(vectorStore, batch);
        }
    }

    private void addBatchWithRetry(VectorStore vectorStore, List<Document> batch) {
        RuntimeException batchFailure = null;

        for (int attempt = 1; attempt <= VECTOR_ADD_MAX_RETRIES; attempt++) {
            try {
                vectorStore.add(batch);
                return;
            } catch (RuntimeException e) {
                batchFailure = e;
                log.warn("向量批量写入失败，准备重试: attempt={}/{}, size={}, firstDocumentId={}",
                        attempt, VECTOR_ADD_MAX_RETRIES, batch.size(), batch.get(0).getId(), e);
                sleepBeforeRetry(attempt);
            }
        }

        if (batch.size() == 1) {
            throw new RuntimeException("向量写入失败，documentId=" + batch.get(0).getId(), batchFailure);
        }

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

        if (!failedDocumentIds.isEmpty()) {
            throw new RuntimeException("向量写入失败，documentIds=" + String.join(",", failedDocumentIds), singleFailure);
        }
    }

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

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(VECTOR_ADD_RETRY_DELAY_MS * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("向量写入重试被中断", e);
        }
    }
}

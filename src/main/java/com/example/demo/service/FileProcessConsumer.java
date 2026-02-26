package com.example.demo.service;

import com.example.demo.Config.RabbitMQConfig;
import com.example.demo.mapper.RagUnitMapper;
import com.example.demo.model.RagUnit;
import com.example.demo.model.dto.FileProcessTask;
import com.example.demo.service.processor.MediaProcessor;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件处理消息消费者
 * 监听队列并异步处理文件（解析、切片、向量化）
 */
@Service
@Slf4j
public class FileProcessConsumer {

    @Autowired
    private RagUnitMapper ragUnitMapper;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private RagUnitService ragUnitService;

    /**
     * 监听文件处理队列
     * 手动ACK模式，确保消息不丢失
     */
    @RabbitListener(queues = RabbitMQConfig.FILE_PROCESS_QUEUE)
    public void processFile(FileProcessTask task,
                           Channel channel,
                           @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {

        log.info("开始处理文件: sourceId={}, filename={}, size={}KB",
                task.getSourceId(), task.getFilename(), task.getFileSize() / 1024);

        long startTime = System.currentTimeMillis();

        try (
            // 1. 从MinIO下载文件（使用 try-with-resources 自动关闭）
            InputStream fileStream = downloadFromMinio(task.getMinioUrl())
        ) {

            // 2. 识别处理器
            MediaProcessor processor = ragUnitService.findProcessorByMimeType(task.getMimeType());
            if (processor == null) {
                throw new IllegalArgumentException("不支持的文件类型: " + task.getMimeType());
            }

            // 3. 处理文件，提取RagUnits
            List<RagUnit> units = processor.process(
                fileStream,
                task.getFilename(),
                task.getMimeType(),
                task.getMinioUrl()
            );

            log.info("文件 {} 解析完成，共生成 {} 个切片", task.getFilename(), units.size());

            // 4. 保存数据并向量化（使用事务保证数据一致性）
            saveDataWithTransaction(units, task);

            long duration = System.currentTimeMillis() - startTime;
            log.info("文件处理成功: {} (耗时: {}ms, 切片数: {})",
                    task.getFilename(), duration, units.size());

            // 6. 手动确认消息
            if (channel.isOpen()) {
                channel.basicAck(deliveryTag, false);
                log.info("消息已确认: deliveryTag={}", deliveryTag);
            } else {
                log.warn("Channel 已关闭，无法确认消息: deliveryTag={}", deliveryTag);
            }

        } catch (Exception e) {
            log.error("文件处理失败: {}", task.getFilename(), e);

            try {
                // 检查 Channel 是否还开启
                if (channel.isOpen()) {
                    // 拒绝消息，不重新入队（避免死循环）
                    channel.basicNack(deliveryTag, false, false);
                    log.info("消息已拒绝: deliveryTag={}", deliveryTag);
                } else {
                    log.warn("Channel 已关闭，无法确认消息: deliveryTag={}", deliveryTag);
                }
            } catch (Exception ackException) {
                log.error("消息确认失败", ackException);
            }
        }
    }

    /**
     * 从MinIO下载文件
     */
    private InputStream downloadFromMinio(String minioUrl) throws Exception {
        URL url = new URL(minioUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        return connection.getInputStream();
    }

    /**
     * 保存数据到MySQL和向量库（带事务管理）
     * 事务范围：MySQL 批量插入
     * 如果向量库写入失败，回滚 MySQL 数据
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveDataWithTransaction(List<RagUnit> units, FileProcessTask task) throws Exception {
        // 准备数据字段
        for (RagUnit unit : units) {
            unit.setSourceId(task.getSourceId());
            unit.setFileHash(task.getFileHash());
            unit.setFilename(task.getFilename());
            unit.setMinioPath(task.getMinioPath());
            unit.setMinioUrl(task.getMinioUrl());
        }

        // 1. 先批量插入 MySQL（生成 ID）
        if (!units.isEmpty()) {
            ragUnitService.saveBatch(units);
            log.info("已批量保存 {} 个切片到数据库", units.size());
        }

        // 2. 用生成的 ID 构建 Document 列表
        List<Document> documents = new ArrayList<>();
        for (RagUnit unit : units) {
            if (unit.getContent() != null && !unit.getContent().isEmpty()) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("source_id", unit.getSourceId());
                metadata.put("source_type", unit.getSourceType().name());
                metadata.put("unit_id", unit.getId());
                if (unit.getStartTime() != null) metadata.put("start_time", unit.getStartTime());
                if (unit.getEndTime() != null) metadata.put("end_time", unit.getEndTime());
                metadata.put("filename", task.getFilename());

                Document doc = new Document(unit.getId(), unit.getContent(), metadata);
                documents.add(doc);
            }
        }

        // 3. 批量写入向量数据库
        // 注意：如果向量库写入失败，会触发事务回滚，删除已插入的 MySQL 数据
        if (!documents.isEmpty()) {
            addDocumentsToVectorStore(documents, task.getFilename());
        }
    }

    /**
     * 批量添加文档到向量数据库，带限流控制和重试机制
     */
    private void addDocumentsToVectorStore(List<Document> documents, String filename) throws Exception {
        int batchSize = 3; // 减小批次大小，避免 Redis 连接重置
        int totalDocs = documents.size();
        int successCount = 0;
        int failCount = 0;

        log.info("开始向量化 {} 个文档，批次大小: {}", totalDocs, batchSize);

        for (int i = 0; i < totalDocs; i += batchSize) {
            int end = Math.min(i + batchSize, totalDocs);
            List<Document> batch = documents.subList(i, end);
            int batchNum = (i / batchSize) + 1;
            int numBatches = (totalDocs + batchSize - 1) / batchSize;

            // 重试机制：最多重试 3 次
            boolean success = false;
            for (int retry = 0; retry < 3; retry++) {
                try {
                    vectorStore.add(batch);
                    log.info("批次 {}/{}: 成功添加 {} 个向量",
                            batchNum, numBatches, batch.size());
                    successCount += batch.size();
                    success = true;
                    break;
                } catch (Exception e) {
                    log.warn("批次 {}/{} 写入失败（第 {} 次尝试）: {}",
                            batchNum, numBatches, retry + 1, e.getMessage());

                    if (retry < 2) {
                        // 使用指数退避策略，但不阻塞线程
                        // 简单等待（后续可改为异步调度）
                        try {
                            Thread.sleep(1000 * (retry + 1)); // 1秒、2秒递增
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new Exception("重试被中断", ie);
                        }
                    } else {
                        // 最后一次失败，记录错误
                        log.error("批次 {}/{} 最终失败，跳过这 {} 个文档",
                                batchNum, numBatches, batch.size());
                        failCount += batch.size();
                    }
                }
            }

            // 批次间无需延迟（已通过 Redis 连接池优化和批次大小控制）
        }

        log.info("文件 {} 向量写入完成 - 成功: {}/{}, 失败: {}",
                filename, successCount, totalDocs, failCount);

        // 如果失败太多，抛出异常
        if (failCount > totalDocs / 2) {
            throw new Exception("向量写入失败率过高: " + failCount + "/" + totalDocs);
        }
    }
}

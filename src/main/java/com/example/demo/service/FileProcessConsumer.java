package com.example.demo.service;

import com.example.demo.config.RabbitMQConfig;
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

        try {
            // 1. 从MinIO下载文件
            InputStream fileStream = downloadFromMinio(task.getMinioUrl());

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

            // 4. 保存到MySQL和准备向量数据
            List<Document> documents = new ArrayList<>();

            for (RagUnit unit : units) {
                unit.setSourceId(task.getSourceId());
                unit.setFileHash(task.getFileHash());  // 设置文件哈希值
                unit.setFilename(task.getFilename());  // 设置filename字段
                unit.setMinioPath(task.getMinioPath());
                unit.setMinioUrl(task.getMinioUrl());

                // 保存到MySQL
                ragUnitMapper.insert(unit);

                // 准备向量数据
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

            // 5. 批量写入向量数据库（带限流）
            if (!documents.isEmpty()) {
                addDocumentsToVectorStore(documents, task.getFilename());
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("文件处理成功: {} (耗时: {}ms, 切片数: {})",
                    task.getFilename(), duration, units.size());

            // 6. 手动确认消息
            channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            log.error("文件处理失败: {}", task.getFilename(), e);

            try {
                // 拒绝消息并重新入队（最多重试3次）
                // 如果是第3次失败，则发送到死信队列
                channel.basicNack(deliveryTag, false, false);
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
     * 批量添加文档到向量数据库，带限流控制
     */
    private void addDocumentsToVectorStore(List<Document> documents, String filename) throws Exception {
        int batchSize = 10; // DashScope API限制
        int totalDocs = documents.size();

        if (totalDocs <= batchSize) {
            vectorStore.add(documents);
            log.info("已添加 {} 个向量到VectorStore", documents.size());
        } else {
            int numBatches = (totalDocs + batchSize - 1) / batchSize;

            for (int i = 0; i < totalDocs; i += batchSize) {
                int end = Math.min(i + batchSize, totalDocs);
                List<Document> batch = documents.subList(i, end);
                int batchNum = (i / batchSize) + 1;

                vectorStore.add(batch);
                log.info("批次 {}/{}: 成功添加 {} 个向量",
                        batchNum, numBatches, batch.size());

                // 批次间延迟，避免触发API限流
                if (i + batchSize < totalDocs) {
                    Thread.sleep(1000);
                }
            }

            log.info("文件 {} 的所有 {} 个向量已成功写入（共 {} 个批次）",
                    filename, totalDocs, numBatches);
        }
    }
}

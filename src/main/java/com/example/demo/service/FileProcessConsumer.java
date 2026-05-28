package com.example.demo.service;

import com.example.demo.Config.RabbitMQConfig;
import com.example.demo.exception.FileProcessingCancelledException;
import com.example.demo.model.DocumentFileStatus;
import com.example.demo.model.RagUnit;
import com.example.demo.model.dto.FileProcessTask;
import com.example.demo.service.processor.MediaProcessor;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

@Service
@Slf4j
public class FileProcessConsumer {

    private final RagUnitService ragUnitService;
    private final DocumentFileService documentFileService;
    private final HierarchicalIndexingService hierarchicalIndexingService;

    public FileProcessConsumer(RagUnitService ragUnitService,
                               DocumentFileService documentFileService,
                               HierarchicalIndexingService hierarchicalIndexingService) {
        this.ragUnitService = ragUnitService;
        this.documentFileService = documentFileService;
        this.hierarchicalIndexingService = hierarchicalIndexingService;
    }

    @RabbitListener(queues = RabbitMQConfig.FILE_PROCESS_QUEUE)
    public void processFile(FileProcessTask task,
                            Channel channel,
                            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        long startTime = System.currentTimeMillis();

        try (InputStream fileStream = downloadFromMinio(task.getMinioUrl())) {
            ensureFileActive(task.getUserId(), task.getFileHash());
            if (task.getFileHash() != null) {
                documentFileService.updateStatus(task.getUserId(), task.getFileHash(), DocumentFileStatus.CHUNKING);
            }

            MediaProcessor processor = ragUnitService.findProcessorByMimeType(task.getMimeType());
            if (processor == null) {
                throw new IllegalArgumentException("不支持的文件类型: " + task.getMimeType());
            }

            List<RagUnit> leafUnits = processor.process(
                    fileStream,
                    task.getFilename(),
                    task.getMimeType(),
                    task.getMinioUrl()
            );

            // 异步主链路在这里把“叶子切片”提升为完整摘要树。
            List<RagUnit> allUnits = hierarchicalIndexingService.buildHierarchy(
                    task.getSourceId(),
                    task.getFilename(),
                    leafUnits
            );

            ensureFileActive(task.getUserId(), task.getFileHash());
            saveDataWithTransaction(allUnits, task);

            int leafCount = hierarchicalIndexingService.countLeafNodes(allUnits);
            if (task.getFileHash() != null) {
                documentFileService.updateStatus(task.getUserId(), task.getFileHash(), DocumentFileStatus.SUCCESS, leafCount, null);
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("文件处理成功: {} (耗时: {}ms, 叶子切片数: {}, 总节点数: {})",
                    task.getFilename(), duration, leafCount, allUnits.size());
            safeAck(channel, deliveryTag);
        } catch (FileProcessingCancelledException e) {
            log.info("文件处理被取消: {}", task.getFilename());
            safeAck(channel, deliveryTag);
        } catch (Exception e) {
            log.error("文件处理失败: {}", task.getFilename(), e);
            if (task.getFileHash() != null && documentFileService.isActive(task.getUserId(), task.getFileHash())) {
                documentFileService.markFailed(task.getUserId(), task.getFileHash(), normalizeErrorMessage(e));
            }
            safeAck(channel, deliveryTag);
        }
    }

    private InputStream downloadFromMinio(String minioUrl) throws Exception {
        URL url = new URL(minioUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        return connection.getInputStream();
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveDataWithTransaction(List<RagUnit> units, FileProcessTask task) throws Exception {
        ensureFileActive(task.getUserId(), task.getFileHash());

        for (RagUnit unit : units) {
            unit.setSourceId(task.getSourceId());
            unit.setFileHash(task.getFileHash());
            unit.setUserId(task.getUserId());
            unit.setFilename(task.getFilename());
            unit.setMinioPath(task.getMinioPath());
            unit.setMinioUrl(task.getMinioUrl());
        }

        // 这里保存的是整棵树的所有节点，而不只是原始叶子 chunk。
        if (!units.isEmpty()) {
            ragUnitService.saveBatch(units);
        }

        int leafCount = hierarchicalIndexingService.countLeafNodes(units);

        ensureFileActive(task.getUserId(), task.getFileHash());
        if (task.getFileHash() != null) {
            documentFileService.updateStatus(task.getUserId(), task.getFileHash(), DocumentFileStatus.VECTORIZING, leafCount, null);
        }

        // 向量写入时由 RagUnitService 根据 nodeType 自动分流到叶子库和摘要库。
        if (!units.isEmpty()) {
            ragUnitService.addUnitsToVectorStores(units, task.getFilename());
        }
    }

    private void ensureFileActive(String userId, String fileHash) {
        if (fileHash != null && !documentFileService.isActive(userId, fileHash)) {
            throw new FileProcessingCancelledException("文件已被删除或取消处理");
        }
    }

    private void safeAck(Channel channel, long deliveryTag) {
        try {
            if (channel.isOpen()) {
                channel.basicAck(deliveryTag, false);
            }
        } catch (Exception e) {
            log.error("消息确认失败: deliveryTag={}", deliveryTag, e);
        }
    }

    private String normalizeErrorMessage(Exception e) {
        if (e == null || e.getMessage() == null || e.getMessage().isBlank()) {
            return "文件处理失败";
        }
        return e.getMessage();
    }
}

package com.example.demo.service;

import com.example.demo.Config.RabbitMQConfig;
import com.example.demo.mapper.RagUnitMapper;
import com.example.demo.model.dto.FileDeleteTask;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class FileDeleteConsumer {

    @Autowired
    private RagUnitMapper ragUnitMapper;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private UploadService uploadService;

    @Autowired
    private FileDeleteProducer fileDeleteProducer;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private DocumentFileService documentFileService;

    private static final String DELETE_TASK_PREFIX = "delete:task:";
    private static final int TASK_EXPIRE_HOURS = 24;

    @RabbitListener(queues = RabbitMQConfig.FILE_DELETE_QUEUE)
    public void processDelete(FileDeleteTask task,
                              Channel channel,
                              @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            if (task.getRedisStatus() != FileDeleteTask.StepStatus.SUCCESS && deleteFromRedis(task)) {
                task.setRedisStatus(FileDeleteTask.StepStatus.SUCCESS);
            }

            if (task.getMysqlStatus() != FileDeleteTask.StepStatus.SUCCESS && deleteFromMySQL(task)) {
                task.setMysqlStatus(FileDeleteTask.StepStatus.SUCCESS);
            }

            if (task.getMinioStatus() != FileDeleteTask.StepStatus.SUCCESS && deleteFromMinIO(task)) {
                task.setMinioStatus(FileDeleteTask.StepStatus.SUCCESS);
            }

            if (task.isAllSuccess()) {
                documentFileService.deleteByFileHash(task.getUserId(), task.getFileHash());
                saveTaskStatus(task);
                safeAck(channel, deliveryTag);
                return;
            }

            if (task.needsRetry()) {
                task.incrementRetry();
                if (task.getRedisStatus() != FileDeleteTask.StepStatus.SUCCESS) {
                    task.setRedisStatus(FileDeleteTask.StepStatus.PENDING);
                }
                if (task.getMysqlStatus() != FileDeleteTask.StepStatus.SUCCESS) {
                    task.setMysqlStatus(FileDeleteTask.StepStatus.PENDING);
                }
                if (task.getMinioStatus() != FileDeleteTask.StepStatus.SUCCESS) {
                    task.setMinioStatus(FileDeleteTask.StepStatus.PENDING);
                }

                saveTaskStatus(task);
                Thread.sleep(Math.min(1000L * task.getRetryCount(), 5000L));
                fileDeleteProducer.sendDeleteTask(task);
                safeAck(channel, deliveryTag);
                return;
            }

            if (task.getRedisStatus() != FileDeleteTask.StepStatus.SUCCESS) {
                task.setRedisStatus(FileDeleteTask.StepStatus.FAILED);
            }
            if (task.getMysqlStatus() != FileDeleteTask.StepStatus.SUCCESS) {
                task.setMysqlStatus(FileDeleteTask.StepStatus.FAILED);
            }
            if (task.getMinioStatus() != FileDeleteTask.StepStatus.SUCCESS) {
                task.setMinioStatus(FileDeleteTask.StepStatus.FAILED);
            }
            saveTaskStatus(task);
            safeAck(channel, deliveryTag);
        } catch (Exception e) {
            log.error("删除任务处理异常: {}", task.getFilename(), e);
            saveTaskStatus(task);
            safeAck(channel, deliveryTag);
        }
    }

    private boolean deleteFromRedis(FileDeleteTask task) {
        try {
            if (task.getVectorIds() != null && !task.getVectorIds().isEmpty()) {
                vectorStore.delete(task.getVectorIds());
            }
            return true;
        } catch (Exception e) {
            log.error("Redis 删除失败: {}", task.getFilename(), e);
            return false;
        }
    }

    private boolean deleteFromMySQL(FileDeleteTask task) {
        try {
            if (task.getUnitIds() != null && !task.getUnitIds().isEmpty()) {
                ragUnitMapper.deleteByIds(task.getUnitIds());
            }
            return true;
        } catch (Exception e) {
            log.error("MySQL 删除失败: {}", task.getFilename(), e);
            return false;
        }
    }

    private boolean deleteFromMinIO(FileDeleteTask task) {
        try {
            if (task.getMinioPath() != null && !task.getMinioPath().isEmpty()) {
                uploadService.deleteFile(task.getMinioPath());
            }
            return true;
        } catch (Exception e) {
            log.error("MinIO 删除失败: {}", task.getFilename(), e);
            return false;
        }
    }

    private void saveTaskStatus(FileDeleteTask task) {
        try {
            redisTemplate.opsForValue().set(DELETE_TASK_PREFIX + task.getTaskId(), task, TASK_EXPIRE_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.error("保存任务状态失败: {}", task.getTaskId(), e);
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
}

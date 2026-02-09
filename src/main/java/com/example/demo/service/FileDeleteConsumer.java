package com.example.demo.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.demo.config.RabbitMQConfig;
import com.example.demo.mapper.RagUnitMapper;
import com.example.demo.model.RagUnit;
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

/**
 * 文件删除消息消费者
 * 带重试机制，每个步骤独立重试
 */
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

    private static final String DELETE_TASK_PREFIX = "delete:task:";
    private static final int TASK_EXPIRE_HOURS = 24;

    /**
     * 监听删除队列
     */
    @RabbitListener(queues = RabbitMQConfig.FILE_DELETE_QUEUE)
    public void processDelete(FileDeleteTask task,
                             Channel channel,
                             @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {

        log.info("开始处理删除任务: taskId={}, filename={}, retry={}/{}",
                task.getTaskId(), task.getFilename(), task.getRetryCount(), task.getMaxRetries());

        boolean hasChanges = false;

        try {
            // 1. 尝试删除 Redis VectorStore
            if (task.getRedisStatus() != FileDeleteTask.StepStatus.SUCCESS) {
                if (deleteFromRedis(task)) {
                    task.setRedisStatus(FileDeleteTask.StepStatus.SUCCESS);
                    hasChanges = true;
                    log.info("✓ Redis删除成功: {}", task.getFilename());
                } else {
                    log.warn("✗ Redis删除失败: {} (将重试)", task.getFilename());
                }
            }

            // 2. 尝试删除 MySQL
            if (task.getMysqlStatus() != FileDeleteTask.StepStatus.SUCCESS) {
                if (deleteFromMySQL(task)) {
                    task.setMysqlStatus(FileDeleteTask.StepStatus.SUCCESS);
                    hasChanges = true;
                    log.info("✓ MySQL删除成功: {}", task.getFilename());
                } else {
                    log.warn("✗ MySQL删除失败: {} (将重试)", task.getFilename());
                }
            }

            // 3. 尝试删除 MinIO
            if (task.getMinioStatus() != FileDeleteTask.StepStatus.SUCCESS) {
                if (deleteFromMinIO(task)) {
                    task.setMinioStatus(FileDeleteTask.StepStatus.SUCCESS);
                    hasChanges = true;
                    log.info("✓ MinIO删除成功: {}", task.getFilename());
                } else {
                    log.warn("✗ MinIO删除失败: {} (将重试)", task.getFilename());
                }
            }

            // 4. 检查是否全部成功
            if (task.isAllSuccess()) {
                log.info("删除任务完成: {} (Redis✓ MySQL✓ MinIO✓)", task.getFilename());
                saveTaskStatus(task);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 5. 检查是否需要重试
            if (task.needsRetry()) {
                task.incrementRetry();

                // 标记未成功的步骤为待重试
                if (task.getRedisStatus() != FileDeleteTask.StepStatus.SUCCESS) {
                    task.setRedisStatus(FileDeleteTask.StepStatus.PENDING);
                }
                if (task.getMysqlStatus() != FileDeleteTask.StepStatus.SUCCESS) {
                    task.setMysqlStatus(FileDeleteTask.StepStatus.PENDING);
                }
                if (task.getMinioStatus() != FileDeleteTask.StepStatus.SUCCESS) {
                    task.setMinioStatus(FileDeleteTask.StepStatus.PENDING);
                }

                log.warn("删除任务未完成，准备第 {} 次重试: {}", task.getRetryCount(), task.getFilename());

                // 延迟重试（指数退避）
                Thread.sleep(Math.min(1000L * task.getRetryCount(), 5000L));

                // 重新发送到队列
                fileDeleteProducer.sendDeleteTask(task);
                channel.basicAck(deliveryTag, false);
            } else {
                // 6. 达到最大重试次数，标记失败步骤
                if (task.getRedisStatus() != FileDeleteTask.StepStatus.SUCCESS) {
                    task.setRedisStatus(FileDeleteTask.StepStatus.FAILED);
                }
                if (task.getMysqlStatus() != FileDeleteTask.StepStatus.SUCCESS) {
                    task.setMysqlStatus(FileDeleteTask.StepStatus.FAILED);
                }
                if (task.getMinioStatus() != FileDeleteTask.StepStatus.SUCCESS) {
                    task.setMinioStatus(FileDeleteTask.StepStatus.FAILED);
                }

                log.error("删除任务失败（已重试{}次）: {} - Redis:{} MySQL:{} MinIO:{}",
                        task.getRetryCount(), task.getFilename(),
                        task.getRedisStatus(), task.getMysqlStatus(), task.getMinioStatus());

                saveTaskStatus(task);
                channel.basicAck(deliveryTag, false);
            }

        } catch (Exception e) {
            log.error("删除任务处理异常: {}", task.getFilename(), e);
            try {
                // 发生异常，拒绝消息但不重新入队（避免死循环）
                channel.basicNack(deliveryTag, false, false);
            } catch (Exception ackEx) {
                log.error("消息确认失败", ackEx);
            }
        }
    }

    /**
     * 删除Redis向量数据
     */
    private boolean deleteFromRedis(FileDeleteTask task) {
        try {
            if (task.getVectorIds() != null && !task.getVectorIds().isEmpty()) {
                vectorStore.delete(task.getVectorIds());
                return true;
            }
            return true; // 没有数据需要删除也算成功
        } catch (Exception e) {
            log.error("Redis删除失败: {}", task.getFilename(), e);
            return false;
        }
    }

    /**
     * 删除MySQL数据
     */
    private boolean deleteFromMySQL(FileDeleteTask task) {
        try {
            if (task.getUnitIds() != null && !task.getUnitIds().isEmpty()) {
                for (String unitId : task.getUnitIds()) {
                    ragUnitMapper.deleteById(unitId);
                }
                return true;
            }
            return true; // 没有数据需要删除也算成功
        } catch (Exception e) {
            log.error("MySQL删除失败: {}", task.getFilename(), e);
            return false;
        }
    }

    /**
     * 删除MinIO文件
     */
    private boolean deleteFromMinIO(FileDeleteTask task) {
        try {
            if (task.getMinioPath() != null && !task.getMinioPath().isEmpty()) {
                uploadService.deleteFile(task.getMinioPath());
                return true;
            }
            return true; // 没有文件需要删除也算成功
        } catch (Exception e) {
            log.error("MinIO删除失败: {}", task.getFilename(), e);
            return false;
        }
    }

    /**
     * 保存任务状态到Redis（供前端查询）
     */
    private void saveTaskStatus(FileDeleteTask task) {
        try {
            String key = DELETE_TASK_PREFIX + task.getTaskId();
            redisTemplate.opsForValue().set(key, task, TASK_EXPIRE_HOURS, TimeUnit.HOURS);
            log.info("任务状态已保存: taskId={}", task.getTaskId());
        } catch (Exception e) {
            log.error("保存任务状态失败: {}", task.getTaskId(), e);
        }
    }
}

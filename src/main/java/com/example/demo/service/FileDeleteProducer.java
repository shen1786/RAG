package com.example.demo.service;

import com.example.demo.Config.RabbitMQConfig;
import com.example.demo.model.dto.FileDeleteTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * 文件删除消息生产者 —— 把"文件删除任务"投递到 RabbitMQ 队列。
 *
 * <h2>通俗解释</h2>
 * <p>用户点击"删除文件"后，不是立刻去删（因为要清理 3 个地方：Redis 向量 + MySQL 记录 + MinIO 文件），
 * 而是把删除任务扔进队列，让 {@link FileDeleteConsumer} 慢慢处理。</p>
 *
 * <h2>为什么要异步删除？</h2>
 * <ul>
 *   <li><b>用户体验</b> — 用户点删除后立即得到响应，不用等 3 个存储都删完</li>
 *   <li><b>可靠性</b> — 某个存储（如 MinIO）临时不可用时，消息不会丢失，会自动重试</li>
 *   <li><b>最终一致性</b> — 即使中间某步失败，重试机制保证最终全部清理干净</li>
 * </ul>
 *
 * <h2>消息流转路径</h2>
 * <pre>
 * FileDeleteTask 对象
 *       │
 *       ▼  convertAndSend()
 * 交换机 FILE_EXCHANGE + routingKey="file.delete"
 *       │
 *       ▼
 * FILE_DELETE_QUEUE（rag.file.delete.queue）
 *       │
 *       ▼
 * FileDeleteConsumer 取出 → 删 Redis → 删 MySQL → 删 MinIO
 * </pre>
 *
 * @see FileDeleteConsumer 消费端（从队列取出任务并执行三步删除）
 * @see FileDeleteTask 删除任务的消息体（携带三步状态，支持断点续删）
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileDeleteProducer {

    private final RabbitTemplate rabbitTemplate;

    /**
     * 发送文件删除任务到 RabbitMQ 队列。
     *
     * <p><b>调用场景：</b></p>
     * <ul>
     *   <li>用户主动删除文件时，由 Controller/Service 调用</li>
     *   <li>{@link FileDeleteConsumer} 删除失败需要重试时，也会调用本方法重新投递</li>
     * </ul>
     *
     * <p><b>retryCount 的作用：</b>每重试一次 +1，最多 3 次。
     * 消费者会根据 retryCount 决定是继续重试还是放弃（进死信队列）。</p>
     *
     * @param task 删除任务，包含文件信息 + 三步删除状态 + 重试计数
     */
    public void sendDeleteTask(FileDeleteTask task) {
        try {
            rabbitTemplate.convertAndSend(
                RabbitMQConfig.FILE_EXCHANGE,
                RabbitMQConfig.FILE_DELETE_ROUTING_KEY,
                task
            );
            log.info("已发送删除任务到队列: taskId={}, filename={}, retry={}",
                    task.getTaskId(), task.getFilename(), task.getRetryCount());
        } catch (Exception e) {
            log.error("发送删除任务失败: {}", task.getFilename(), e);
            throw new RuntimeException("Failed to queue delete task", e);
        }
    }
}

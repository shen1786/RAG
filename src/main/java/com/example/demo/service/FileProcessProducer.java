package com.example.demo.service;

import com.example.demo.Config.RabbitMQConfig;
import com.example.demo.model.dto.FileProcessTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * 文件处理消息生产者 —— 把"文件处理任务"投递到 RabbitMQ 队列。
 *
 * <h2>通俗解释</h2>
 * <p>如果把 RabbitMQ 比作快递柜，这个类就是"寄件人"：
 * 把文件处理任务打包好，贴上标签（Routing Key），投进快递柜。</p>
 *
 * <h2>在系统中的位置</h2>
 * <pre>
 * 用户上传文件
 *   │
 *   ▼
 * RagUnitService（总指挥）
 *   ├─ 存文件到 MinIO
 *   ├─ 建数据库记录
 *   └─ 调用本类.sendFileProcessTask() ──→ RabbitMQ 文件处理队列
 *                                            │
 *                                            ▼
 *                                     FileProcessConsumer（消费者）
 *                                     从队列取出任务 → 解析 → 切块 → 向量化
 * </pre>
 *
 * <h2>为什么用 MQ 而不是直接处理？</h2>
 * <ul>
 *   <li><b>异步</b> — 文件解析+向量化可能要几分钟，不能让用户一直等着</li>
 *   <li><b>削峰</b> — 100 个用户同时上传，MQ 先存着，消费者按能力一个个处理</li>
 *   <li><b>可靠</b> — 处理失败自动重试，重试 3 次还失败就进死信队列，不会丢</li>
 * </ul>
 *
 * @see FileProcessConsumer 消费端（从队列取出任务并处理）
 * @see RabbitMQConfig 队列/交换机/绑定的定义
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileProcessProducer {

    private final RabbitTemplate rabbitTemplate;

    /**
     * 发送文件处理任务到 RabbitMQ 队列。
     *
     * <p><b>做了什么：</b>把 {@link FileProcessTask} 对象序列化为 JSON，投递到指定交换机。</p>
     *
     * <p><b>消息流转路径：</b></p>
     * <pre>
     * FileProcessTask 对象
     *       │
     *       ▼  convertAndSend()
     * Jackson 序列化为 JSON 字节
     *       │
     *       ▼  发送到 FILE_EXCHANGE（rag.file.exchange）
     * 交换机根据 routingKey="file.process" 路由
     *       │
     *       ▼
     * FILE_PROCESS_QUEUE（rag.file.process.queue）
     *       │
     *       ▼
     * FileProcessConsumer 取出并处理
     * </pre>
     *
     * <p><b>RabbitTemplate.convertAndSend() 是什么：</b>Spring AMQP 提供的便捷方法，
     * 一步完成"序列化 + 发送"。底层做的事情：</p>
     * <ol>
     *   <li>用 {@link org.springframework.amqp.support.converter.MessageConverter} 把 task 转成 Message</li>
     *   <li>通过 TCP 连接把 Message 发到 RabbitMQ 服务器</li>
     *   <li>RabbitMQ 根据 Exchange + RoutingKey 把消息投递到对应的 Queue</li>
     * </ol>
     *
     * @param task 文件处理任务，包含 sourceId、文件名、MIME 类型、MinIO 路径等
     * @throws RuntimeException 发送失败时抛出（连接断开、MQ 不可用等）
     */
    public void sendFileProcessTask(FileProcessTask task) {
        try {
            rabbitTemplate.convertAndSend(
                RabbitMQConfig.FILE_EXCHANGE,
                RabbitMQConfig.FILE_PROCESS_ROUTING_KEY,
                task
            );
            log.info("已发送文件处理任务到队列: sourceId={}, filename={}",
                    task.getSourceId(), task.getFilename());
        } catch (Exception e) {
            log.error("发送文件处理任务失败: {}", task.getFilename(), e);
            throw new RuntimeException("Failed to queue file process task", e);
        }
    }
}

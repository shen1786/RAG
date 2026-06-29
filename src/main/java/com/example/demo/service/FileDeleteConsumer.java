package com.example.demo.service;

import com.example.demo.Config.RabbitMQConfig;
import com.example.demo.mapper.RagUnitMapper;
import com.example.demo.model.dto.FileDeleteTask;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 文件删除消费者 —— 从 RabbitMQ 队列取出删除任务，执行"删 Redis + 删 MySQL + 删 MinIO"三步删除。
 *
 * <h2>通俗解释</h2>
 * <p>如果说 {@link FileDeleteProducer} 是"扔垃圾的人"，那这个类就是"清洁工"：
 * 从垃圾桶（队列）里拿出垃圾，分别倒进三个垃圾桶（Redis、MySQL、MinIO）。</p>
 *
 * <h2>为什么分三步删？</h2>
 * <p>一个文件在系统中有三份数据：</p>
 * <ol>
 *   <li><b>Redis 向量</b> — 文件被切块后向量化存储的地方，用于语义检索</li>
 *   <li><b>MySQL 记录</b> — rag_unit 表中的切块元数据（文件名、用户ID等）</li>
 *   <li><b>MinIO 文件</b> — 原始文件存储的地方（对象存储）</li>
 * </ol>
 * <p>必须三步都成功，文件才算彻底删除。任何一步失败都会自动重试。</p>
 *
 * <h2>三步删除流程</h2>
 * <pre>
 * 从队列取出 FileDeleteTask
 *       │
 *       ▼
 * Step 1: deleteFromRedis()  — 删除 Redis 中的向量索引
 *       │                      （leaf + summary 两个索引都删）
 *       ▼
 * Step 2: deleteFromMySQL()  — 删除 rag_unit 表中的切块记录
 *       │
 *       ▼
 * Step 3: deleteFromMinIO()  — 删除 MinIO 中的原始文件
 *       │
 *       ▼
 * 三步全成功 → ack 消息 + 删除 document_file 表记录
 * 某步失败   → 判断是否需要重试
 *             ├─ retryCount < 3 → 重新投递到队列（断点续删，已完成的步骤不会重复执行）
 *             └─ retryCount >= 3 → nack 消息，进入死信队列
 * </pre>
 *
 * <h2>断点续删机制</h2>
 * <p>{@link FileDeleteTask} 中有三个状态字段：redisStatus、mysqlStatus、minioStatus。
 * 每完成一步就标记为 SUCCESS。下次重试时，已成功的步骤会被跳过，直接从失败的步骤继续。</p>
 *
 * @see FileDeleteProducer 生产端（投递删除任务）
 * @see FileDeleteTask 删除任务的消息体（携带三步状态 + 重试计数）
 */
@Service
@Slf4j
public class FileDeleteConsumer {

    private final RagUnitMapper ragUnitMapper;
    private final VectorStore leafVectorStore;
    private final VectorStore summaryVectorStore;
    private final UploadService uploadService;
    private final FileDeleteProducer fileDeleteProducer;
    private final RedisTemplate<String, Object> redisTemplate;
    private final DocumentFileService documentFileService;

    public FileDeleteConsumer(RagUnitMapper ragUnitMapper,
                              @Qualifier("leafVectorStore") VectorStore leafVectorStore,
                              @Qualifier("summaryVectorStore") VectorStore summaryVectorStore,
                              UploadService uploadService,
                              FileDeleteProducer fileDeleteProducer,
                              RedisTemplate<String, Object> redisTemplate,
                              DocumentFileService documentFileService) {
        this.ragUnitMapper = ragUnitMapper;
        this.leafVectorStore = leafVectorStore;
        this.summaryVectorStore = summaryVectorStore;
        this.uploadService = uploadService;
        this.fileDeleteProducer = fileDeleteProducer;
        this.redisTemplate = redisTemplate;
        this.documentFileService = documentFileService;
    }

    private static final String DELETE_TASK_PREFIX = "delete:task:";
    private static final int TASK_EXPIRE_HOURS = 24;

    /**
     * 文件删除的核心方法 —— 从队列取出一条删除任务，执行三步删除。
     *
     * <p><b>@RabbitListener</b> 是 Spring AMQP 的注解，标记这个方法是"消息消费者"。
     * Spring 启动时会自动创建消费者线程，监听指定队列，有新消息就调用这个方法。</p>
     *
     * <p><b>参数说明：</b></p>
     * <ul>
     *   <li>{@code task} — 反序列化后的删除任务对象（Spring 自动从 JSON 还原）</li>
     *   <li>{@code channel} — RabbitMQ 的 TCP 通道，用于手动 ack/nack</li>
     *   <li>{@code deliveryTag} — 消息的"回执编号"，ack 时告诉 MQ "这个编号的消息我处理完了"</li>
     * </ul>
     *
     * <p><b>为什么用手动 ack 而不是自动？</b>自动 ack 的话，消息一到消费者就从队列删除了。
     * 如果处理到一半消费者崩了，消息就丢了。手动 ack 确保处理成功后才删除消息。</p>
     */
    @RabbitListener(queues = RabbitMQConfig.FILE_DELETE_QUEUE)
    public void processDelete(FileDeleteTask task,
                              Channel channel,
                              @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            // ── Step 1: 删 Redis 向量（如果还没成功就执行）──
            if (task.getRedisStatus() != FileDeleteTask.StepStatus.SUCCESS && deleteFromRedis(task)) {
                task.setRedisStatus(FileDeleteTask.StepStatus.SUCCESS);
            }

            // ── Step 2: 删 MySQL 记录（如果还没成功就执行）──
            if (task.getMysqlStatus() != FileDeleteTask.StepStatus.SUCCESS && deleteFromMySQL(task)) {
                task.setMysqlStatus(FileDeleteTask.StepStatus.SUCCESS);
            }

            // ── Step 3: 删 MinIO 文件（如果还没成功就执行）──
            if (task.getMinioStatus() != FileDeleteTask.StepStatus.SUCCESS && deleteFromMinIO(task)) {
                task.setMinioStatus(FileDeleteTask.StepStatus.SUCCESS);
            }

            // ── 全部成功 → 确认消息 + 删除 document_file 表记录 ──
            if (task.isAllSuccess()) {
                documentFileService.deleteByFileHash(task.getUserId(), task.getFileHash());
                saveTaskStatus(task);
                safeAck(channel, deliveryTag);  // 告诉 MQ 消息已处理完，从队列删除
                return;
            }

            // ── 还有步骤没成功 → 判断是否需要重试 ──
            if (task.needsRetry()) {
                task.incrementRetry();  // 重试计数 +1

                // 把未成功的步骤状态重置为 PENDING（下次重试时会重新执行）
                if (task.getRedisStatus() != FileDeleteTask.StepStatus.SUCCESS) {
                    task.setRedisStatus(FileDeleteTask.StepStatus.PENDING);
                }
                if (task.getMysqlStatus() != FileDeleteTask.StepStatus.SUCCESS) {
                    task.setMysqlStatus(FileDeleteTask.StepStatus.PENDING);
                }
                if (task.getMinioStatus() != FileDeleteTask.StepStatus.SUCCESS) {
                    task.setMinioStatus(FileDeleteTask.StepStatus.PENDING);
                }

                saveTaskStatus(task);  // 持久化当前状态（下次重试时读取）
                // 线性退避：第1次等1秒，第2次等2秒，第3次等3秒（最多5秒）
                Thread.sleep(Math.min(1000L * task.getRetryCount(), 5000L));
                // 重新投递到队列，由消费者再次取出处理（断点续删）
                fileDeleteProducer.sendDeleteTask(task);
                safeAck(channel, deliveryTag);  // 确认当前消息（新消息已重新入队）
                return;
            }

            // ── 重试次数用尽 → 标记失败 + nack 进死信队列 ──
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
            safeNack(channel, deliveryTag);  // nack 消息，进入死信队列等待人工排查
        } catch (Exception e) {
            log.error("删除任务处理异常: {}", task.getFilename(), e);
            saveTaskStatus(task);
            safeNack(channel, deliveryTag);
        }
    }

    /**
     * Step 1: 删除 Redis 中的向量索引。
     *
     * <p><b>做了什么：</b>把文件对应的向量从两个 Redis 索引中都删掉：</p>
     * <ul>
     *   <li>leafVectorStore — 叶子节点索引（细粒度文档切块）</li>
     *   <li>summaryVectorStore — 摘要节点索引（章节/文档摘要）</li>
     * </ul>
     *
     * <p><b>为什么两个都删：</b>不确定这个文件的向量在哪个索引里，
     * 干脆两个都删一遍，不存在的 ID 会被 Redis 静默忽略。</p>
     *
     * <p><b>返回 true/false：</b>标识是否全部成功。如果某个索引删失败了，
     * 返回 false，上层会根据这个结果决定是否重试。</p>
     */
    private boolean deleteFromRedis(FileDeleteTask task) {
        if (task.getVectorIds() == null || task.getVectorIds().isEmpty()) {
            return true;
        }

        boolean leafSuccess = true;
        boolean summarySuccess = true;

        try {
            leafVectorStore.delete(task.getVectorIds());
            log.info("leaf 向量删除完成, vectorIds={}", task.getVectorIds().size());
        } catch (Exception e) {
            leafSuccess = false;
            log.error("leaf 向量删除失败, vectorIds={}", task.getVectorIds().size(), e);
        }

        try {
            summaryVectorStore.delete(task.getVectorIds());
            log.info("summary 向量删除完成, vectorIds={}", task.getVectorIds().size());
        } catch (Exception e) {
            summarySuccess = false;
            log.error("summary 向量删除失败, vectorIds={}", task.getVectorIds().size(), e);
        }

        return leafSuccess && summarySuccess;
    }

    /**
     * Step 2: 删除 MySQL 中的切块记录。
     *
     * <p><b>做了什么：</b>根据 unitIds（切块 ID 列表）批量删除 rag_unit 表中的记录。
     * 这些记录是文件上传时解析切块后写入的元数据（内容、位置、层级等）。</p>
     *
     * <p><b>为什么在 Redis 之后删：</b>如果先删 MySQL 再删 Redis，
     * 中间崩溃了会导致"MySQL 没了但 Redis 还有"，检索时会命中已删除文件的数据。
     * 先删 Redis（向量），即使后面失败，至少不会检索到脏数据。</p>
     */
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

    /**
     * Step 3: 删除 MinIO 中的原始文件。
     *
     * <p><b>做了什么：</b>根据 minioPath（文件在 MinIO 中的存储路径）删除原始文件。
     * 这是用户上传时存进去的那份完整文件（PDF、Word 等）。</p>
     *
     * <p><b>为什么最后删 MinIO：</b>MinIO 是最"贵"的资源（大文件存储），
     * 如果前面的步骤失败了需要重试，保留原始文件可以避免重新上传。
     * 而且 MinIO 删除是不可逆的，放在最后最安全。</p>
     */
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

    /**
     * 把任务的当前状态持久化到 Redis。
     *
     * <p><b>为什么需要：</b>删除任务可能重试多次，每次重试前需要知道哪些步骤已经成功。
     * 保存到 Redis 后，即使消费者重启，也能从上次的进度继续（断点续删）。</p>
     *
     * <p><b>TTL 24 小时：</b>任务状态不会永久保留，24 小时后自动清理。</p>
     */
    private void saveTaskStatus(FileDeleteTask task) {
        try {
            redisTemplate.opsForValue().set(DELETE_TASK_PREFIX + task.getTaskId(), task, TASK_EXPIRE_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.error("保存任务状态失败: {}", task.getTaskId(), e);
        }
    }

    /**
     * 安全地确认消息（ack）—— 告诉 RabbitMQ "这条消息我处理完了，可以删了"。
     *
     * <p><b>ack 做了什么：</b>RabbitMQ 收到 ack 后，会把这条消息从队列中永久删除。
     * 如果不 ack，消息会被重新分配给其他消费者（或等消费者重启后重新处理）。</p>
     *
     * <p><b>为什么要检查 channel.isOpen()：</b>如果网络断了，channel 已经关闭，
     * 此时调 ack 会抛异常。先检查一下，避免无意义的异常。</p>
     */
    private void safeAck(Channel channel, long deliveryTag) {
        try {
            if (channel.isOpen()) {
                channel.basicAck(deliveryTag, false);
            }
        } catch (Exception e) {
            log.error("消息确认失败: deliveryTag={}", deliveryTag, e);
        }
    }

    /**
     * 安全地拒绝消息（nack）—— 告诉 RabbitMQ "这条消息我处理不了"。
     *
     * <p><b>nack 做了什么：</b>RabbitMQ 收到 nack 后，因为配置了 {@code defaultRequeueRejected=false}，
     * 消息不会重新回到原队列，而是根据死信路由键（file.delete.dlq）转入死信队列。</p>
     *
     * <p><b>nack 的第三个参数 requeue=false：</b>
     * false = 不重新入队（走死信），true = 重新回到原队列（可能无限重试）。</p>
     */
    private void safeNack(Channel channel, long deliveryTag) {
        try {
            if (channel.isOpen()) {
                channel.basicNack(deliveryTag, false, false);
            }
            log.warn("消息 nack 成功, deliveryTag={}", deliveryTag);
        } catch (Exception e) {
            log.error("消息 nack 失败, deliveryTag={}", deliveryTag, e);
        }
    }
}

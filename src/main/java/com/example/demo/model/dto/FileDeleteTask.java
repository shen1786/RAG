package com.example.demo.model.dto;

import com.example.demo.service.FileDeleteConsumer;
import com.example.demo.service.FileDeleteProducer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 文件删除任务消息体 —— 通过 RabbitMQ 传递，携带删除所需的全部信息和三步删除状态。
 *
 * <h2>通俗解释</h2>
 * <p>就像一张"退货单"，上面写着：退什么货、退到哪里、当前退到哪一步了。
 * {@link com.example.demo.service.FileDeleteProducer} 寄出这张单子，
 * {@link com.example.demo.service.FileDeleteConsumer} 收到后按单子一步步操作。</p>
 *
 * <h2>三步删除状态（断点续删的核心）</h2>
 * <pre>
 * FileDeleteTask 携带三个状态字段：
 *   redisStatus  — Redis 向量是否已删除
 *   mysqlStatus  — MySQL 记录是否已删除
 *   minioStatus  — MinIO 文件是否已删除
 *
 * 每个状态有三种值：PENDING（待处理）→ SUCCESS（成功）→ FAILED（失败）
 *
 * 重试时的行为：
 *   第 1 次：redisStatus=PENDING, mysqlStatus=PENDING, minioStatus=PENDING
 *           → 全部执行，假设 Redis 成功，MySQL 失败
 *   第 2 次：redisStatus=SUCCESS, mysqlStatus=PENDING, minioStatus=PENDING
 *           → 跳过 Redis，只执行 MySQL + MinIO
 *   第 3 次：如果还失败 → redisStatus=SUCCESS, mysqlStatus=FAILED, minioStatus=FAILED
 *           → 进入死信队列，等待人工排查
 * </pre>
 *
 * <h2>为什么需要 retryCount 和 maxRetries？</h2>
 * <p>防止无限重试。默认最多重试 3 次（maxRetries=3），
 * 超过后标记为 FAILED 并 nack 进死信队列。避免某个存储长期不可用时消息一直循环。</p>
 *
 * @see FileDeleteProducer 生产者（发送删除任务）
 * @see FileDeleteConsumer 消费者（执行三步删除 + 断点续删 + 重试）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileDeleteTask implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 删除任务的唯一标识（UUID），用于在 Redis 中保存任务状态 */
    private String taskId;
    /** 原始文件名，仅用于日志展示 */
    private String filename;
    /** 文件的 SHA-256 哈希，用于定位 document_file 表记录 */
    private String fileHash;
    /** 上传用户 ID，用于数据隔离 */
    private String userId;
    /** MinIO 存储路径，Step 3 删除时使用 */
    private String minioPath;
    /** Redis 向量 ID 列表，Step 1 删除时使用（从 rag_unit 表中查出） */
    private List<String> vectorIds;
    /** MySQL rag_unit 表的记录 ID 列表，Step 2 删除时使用 */
    private List<String> unitIds;

    /** 当前已重试次数，每次重试 +1 */
    @Builder.Default
    private int retryCount = 0;
    /** 最大重试次数，超过后进死信队列 */
    @Builder.Default
    private int maxRetries = 3;

    /** 任务创建时间戳 */
    private Long createTimestamp;

    /** Step 1 状态：Redis 向量是否已删除 */
    @Builder.Default
    private StepStatus redisStatus = StepStatus.PENDING;
    /** Step 2 状态：MySQL 记录是否已删除 */
    @Builder.Default
    private StepStatus mysqlStatus = StepStatus.PENDING;
    /** Step 3 状态：MinIO 文件是否已删除 */
    @Builder.Default
    private StepStatus minioStatus = StepStatus.PENDING;

    /**
     * 步骤状态枚举。
     * <ul>
     *   <li>PENDING — 待处理（还没开始或需要重试）</li>
     *   <li>SUCCESS — 已成功</li>
     *   <li>FAILED — 已失败（超过最大重试次数后标记）</li>
     * </ul>
     */
    public enum StepStatus {
        PENDING,
        SUCCESS,
        FAILED
    }

    /**
     * 判断是否还需要重试：重试次数未超限 且 至少有一步还没成功。
     *
     * @return true 表示应该重新投递到队列继续处理
     */
    public boolean needsRetry() {
        return retryCount < maxRetries &&
                (redisStatus != StepStatus.SUCCESS
                        || mysqlStatus != StepStatus.SUCCESS
                        || minioStatus != StepStatus.SUCCESS);
    }

    /** 重试计数 +1，在重新投递到队列前调用 */
    public void incrementRetry() {
        this.retryCount++;
    }

    /** 判断三步是否全部成功，全部成功后可以安全 ack 消息并删除 document_file 记录 */
    public boolean isAllSuccess() {
        return redisStatus == StepStatus.SUCCESS
                && mysqlStatus == StepStatus.SUCCESS
                && minioStatus == StepStatus.SUCCESS;
    }
}

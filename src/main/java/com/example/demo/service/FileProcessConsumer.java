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
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * 文件处理消费者 —— 从 RabbitMQ 队列中取出上传任务，执行解析、切块、向量化。
 *
 * <p><b>通俗解释：</b>如果说 {@link FileProcessProducer} 是"快递员"（负责把任务投进信箱），
 * 那这个类就是"分拣员"（从信箱里取出包裹，拆包 → 分拣 → 上架）。</p>
 *
 * <p><b>它在整个系统中的位置：</b></p>
 * <pre>
 * 用户上传文件 → RagUnitService（总指挥）
 *                  ├─ 存文件到 MinIO
 *                  ├─ 建数据库记录
 *                  └─ 发送任务到 RabbitMQ ──→ 【本类】从队列取出任务并处理
 *                                               ├─ 从 MinIO 下载文件
 *                                               ├─ 调用 MediaProcessor 解析为切块
 *                                               ├─ 构建层级摘要树
 *                                               ├─ 写入 MySQL + Redis 向量库
 *                                               └─ 更新状态为 SUCCESS
 * </pre>
 *
 * @see FileProcessProducer 任务生产端
 * @see MediaProcessor 文件解析策略接口
 */
@Service
@Slf4j
public class FileProcessConsumer {

    private final RagUnitService ragUnitService;
    private final DocumentFileService documentFileService;
    private final HierarchicalIndexingService hierarchicalIndexingService;
    private final TransactionTemplate transactionTemplate;

    /**
     * 构造注入所有依赖。
     *
     * <p><b>为什么用构造注入而不是 @Autowired：</b>构造注入能保证依赖不为空，
     * 且方便单元测试时传入 mock 对象。</p>
     *
     * @param ragUnitService              RAG 核心服务（批量写入、向量化等）
     * @param documentFileService         文档文件表的 CRUD 服务
     * @param hierarchicalIndexingService 层级索引服务（把叶子切块组装成摘要树）
     * @param transactionTemplate         Spring 事务模板（手动控制事务边界）
     */
    public FileProcessConsumer(RagUnitService ragUnitService,
                               DocumentFileService documentFileService,
                               HierarchicalIndexingService hierarchicalIndexingService,
                               TransactionTemplate transactionTemplate) {
        this.ragUnitService = ragUnitService;
        this.documentFileService = documentFileService;
        this.hierarchicalIndexingService = hierarchicalIndexingService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 文件处理的核心方法 —— 从队列取出一条任务，完成"解析 → 切块 → 摘要树 → 写库 → 向量化"全流程。
     *
     * <p><b>通俗解释：</b>这就是"分拣员"的日常工作：拿到一个包裹 → 拆开 → 按规则分拣 → 放上货架。</p>
     *
     * <p><b>处理流程：</b></p>
     * <ol>
     *   <li>从 MinIO 下载文件流</li>
     *   <li>检查文件是否仍有效（未被用户删除）</li>
     *   <li>更新状态为 CHUNKING（正在切块）</li>
     *   <li>根据 MIME 类型找到对应的解析器（PDF/Word/PPT 等）</li>
     *   <li>解析文件 → 得到叶子切块列表</li>
     *   <li>将叶子切块组装成层级摘要树（叶子 → 章节摘要 → 文档摘要）</li>
     *   <li>在事务中完成：写 MySQL + 写 Redis 向量库</li>
     *   <li>更新状态为 SUCCESS</li>
     * </ol>
     *
     * <p><b>异常处理：</b></p>
     * <ul>
     *   <li>文件被取消 → 正常 ack（不再重试）</li>
     *   <li>处理失败 → 清理残留数据、标记 FAILED、nack 消息</li>
     * </ul>
     *
     * @param task        从 MQ 取出的文件处理任务（包含 sourceId、文件名、MIME 类型等）
     * @param channel     RabbitMQ 的通道对象，用于手动确认/拒绝消息
     * @param deliveryTag 消息的投递标签（相当于消息的"回执编号"，ack/nack 时需要用到）
     */
    @RabbitListener(queues = RabbitMQConfig.FILE_PROCESS_QUEUE)
    public void processFile(FileProcessTask task,
                            Channel channel,
                            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        long startTime = System.currentTimeMillis();

        // try-with-resources：方法结束时自动关闭文件流，防止资源泄漏
        try (InputStream fileStream = downloadFromMinio(task.getMinioUrl())) {

            // ---- 第 1 步：安全检查 ----
            // 用户可能在文件处理过程中删除了文档，这里检查一下，避免白干活
            ensureFileActive(task.getUserId(), task.getFileHash());

            // 更新数据库状态为 CHUNKING，前端轮询时会显示"正在切块"
            if (task.getFileHash() != null) {
                documentFileService.updateStatus(task.getUserId(), task.getFileHash(), DocumentFileStatus.CHUNKING);
            }

            // ---- 第 2 步：解析文件为叶子切块 ----
            // 根据 MIME 类型（如 application/pdf）找到对应的解析器
            MediaProcessor processor = ragUnitService.findProcessorByMimeType(task.getMimeType());
            if (processor == null) {
                throw new IllegalArgumentException("不支持的文件类型: " + task.getMimeType());
            }

            // 调用解析器，把文件流切成一个个小块（叶子节点）
            // 例如 PDF 会被切成多个段落级别的切块

            List<RagUnit> leafUnits = processor.process(
                    fileStream,      // 从 MinIO 下载的文件流
                    task.getFilename(),     // 文件名
                    task.getMimeType(),     // MIME 类型（如 application/pdf）
                    task.getMinioUrl()      // 文件在 MinIO 中的路径
            );


            // ---- 第 3 步：构建层级摘要树 ----
            // 把扁平的叶子切块组装成三层结构：叶子节点 → 章节摘要 → 文档摘要
            // 这样后续检索时可以"先粗后细"，提高召回质量
            List<RagUnit> allUnits = hierarchicalIndexingService.buildHierarchy(
                    task.getSourceId(),
                    task.getFilename(),
                    leafUnits
            );

            // ---- 第 4 步：再次检查文件是否仍然有效 ----
            ensureFileActive(task.getUserId(), task.getFileHash());

            // ---- 第 5 步：事务内写入 MySQL + Redis 向量库 ----
            // 在同一个事务中完成数据库写入和向量化，失败则整体回滚
            saveDataWithTransaction(allUnits, task);

            // ---- 第 6 步：标记处理成功 ----
            int leafCount = hierarchicalIndexingService.countLeafNodes(allUnits);
            if (task.getFileHash() != null) {
                documentFileService.updateStatus(task.getUserId(), task.getFileHash(), DocumentFileStatus.SUCCESS, leafCount, null);
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("文件处理成功: {} (耗时: {}ms, 叶子切片数: {}, 总节点数: {})",
                    task.getFilename(), duration, leafCount, allUnits.size());

            // 确认消息已处理完成，RabbitMQ 会将其从队列中移除
            safeAck(channel, deliveryTag);

        } catch (FileProcessingCancelledException e) {
            // 文件被用户删除或取消，不算失败，正常确认消息即可
            log.info("文件处理被取消: {}", task.getFilename());
            safeAck(channel, deliveryTag);
        } catch (Exception e) {
            // 处理失败：清理残留数据 + 标记失败 + 拒绝消息
            log.error("文件处理失败: {}", task.getFilename(), e);
            cleanupPartialData(task);
            if (task.getFileHash() != null && documentFileService.isActive(task.getUserId(), task.getFileHash())) {
                documentFileService.markFailed(task.getUserId(), task.getFileHash(), normalizeErrorMessage(e));
            }
            // nack 拒绝消息，根据队列配置可能进入死信队列（DLQ）
            safeNack(channel, deliveryTag);
        }
    }

    /**
     * 从 MinIO 下载文件，返回一个可读的输入流。
     *
     * <p><b>通俗解释：</b>MinIO 是一个"私有网盘"，文件存在那里面。
     * 这个方法就是"从网盘把文件下载到本地"，返回一个可以逐字节读取的流。</p>
     *
     * <p><b>为什么要包装 FilterInputStream：</b>原生的 HttpURLConnection 在关闭流的时候
     * 不一定会断开底层的 TCP 连接，包装后可以确保 close() 时一定调用 disconnect()，
     * 避免连接泄漏。</p>
     *
     * @param minioUrl 文件在 MinIO 中的访问地址（带签名的临时 URL）
     * @return 文件的输入流（调用方用完后必须关闭，建议用 try-with-resources）
     */
    protected InputStream downloadFromMinio(String minioUrl) throws Exception {
        URL url = new URL(minioUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        // 连接超时 10 秒，读取超时 30 秒，防止网络卡死时线程一直挂着
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        InputStream rawStream = connection.getInputStream();
        // 包装为 Closeable，确保调用方关闭 InputStream 时同时断开底层连接
        return new java.io.FilterInputStream(rawStream) {
            @Override
            public void close() throws java.io.IOException {
                try {
                    super.close();
                } finally {
                    connection.disconnect();
                }
            }
        };
    }

    /**
     * 在同一个事务中完成"写 MySQL + 写 Redis 向量库"，任何一步失败都会整体回滚。
     *
     * <p><b>通俗解释：</b>想象你去银行同时办两件事：转账 + 打印回单。
     * 如果转账成功了但打印回单卡了，银行会把转账也撤销，回到操作前的状态。
     * 这就是"事务"——要么全成功，要么全撤销。</p>
     *
     * <p><b>执行步骤：</b></p>
     * <ol>
     *   <li>检查文件是否仍然有效（未被删除）</li>
     *   <li>给每个切块补充元数据（sourceId、userId 等）</li>
     *   <li>批量写入 MySQL 的 rag_unit 表</li>
     *   <li>更新状态为 VECTORIZING（正在向量化）</li>
     *   <li>将切块写入 Redis 向量库（供后续语义检索使用）</li>
     * </ol>
     *
     * @param units 所有切块（叶子节点 + 摘要节点）
     * @param task  文件处理任务（携带元数据）
     */
    public void saveDataWithTransaction(List<RagUnit> units, FileProcessTask task) throws Exception {
        transactionTemplate.executeWithoutResult(status -> {
            try {
                // 写入前再次检查：用户可能在处理过程中删除了文件
                ensureFileActive(task.getUserId(), task.getFileHash());

                // 给每个切块补充"属于哪个文件"的元信息
                for (RagUnit unit : units) {
                    unit.setSourceId(task.getSourceId());
                    unit.setFileHash(task.getFileHash());
                    unit.setUserId(task.getUserId());
                    unit.setFilename(task.getFilename());
                    unit.setMinioPath(task.getMinioPath());
                    unit.setMinioUrl(task.getMinioUrl());
                }

                // 批量写入 MySQL（BATCH 模式，比逐条插入快 5~10 倍）
                if (!units.isEmpty()) {
                    ragUnitService.saveBatch(units);
                }

                int leafCount = hierarchicalIndexingService.countLeafNodes(units);

                // 更新状态为"正在向量化"，前端轮询时会显示对应进度
                ensureFileActive(task.getUserId(), task.getFileHash());
                if (task.getFileHash() != null) {
                    documentFileService.updateStatus(task.getUserId(), task.getFileHash(), DocumentFileStatus.VECTORIZING, leafCount, null);
                }

                // 将切块的文本内容向量化后写入 Redis，供后续相似度检索使用
                if (!units.isEmpty()) {
                    ragUnitService.addUnitsToVectorStores(units, task.getFilename());
                }
            } catch (RuntimeException e) {
                // 标记事务为"只回滚"，Spring 会在方法结束时撤销所有数据库操作
                status.setRollbackOnly();
                throw e;
            } catch (Exception e) {
                status.setRollbackOnly();
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 清理处理失败后残留的部分数据（比如已经写入 Redis 的向量）。
     *
     * <p><b>为什么需要清理：</b>处理到一半失败了，可能已经往 Redis 写了一部分向量。
     * 如果不清理，下次重新上传时会出现"幽灵数据"——检索时会搜到已经被删除的文档的片段。</p>
     */
    private void cleanupPartialData(FileProcessTask task) {
        try {
            ragUnitService.removeIndexedData(task.getSourceId());
        } catch (Exception cleanupError) {
            // 清理失败只记日志，不抛异常，避免掩盖真正的失败原因
            log.error("文件处理失败后清理残留数据失败: sourceId={}, filename={}",
                    task.getSourceId(), task.getFilename(), cleanupError);
        }
    }

    /**
     * 检查文件是否仍然处于"活跃"状态（未被用户删除或取消）。
     *
     * <p><b>通俗解释：</b>文件处理可能要好几分钟，用户可能中途把它删了。
     * 这个方法就是"干到一半抬头看看，老板是不是已经取消了这个任务"。
     * 如果已取消，直接抛异常中止，不做无用功。</p>
     *
     * @throws FileProcessingCancelledException 如果文件已被删除或取消
     */
    private void ensureFileActive(String userId, String fileHash) {
        if (fileHash != null && !documentFileService.isActive(userId, fileHash)) {
            throw new FileProcessingCancelledException("文件已被删除或取消处理");
        }
    }

    /**
     * 安全地确认消息已处理完成（ack）。
     *
     * <p><b>通俗解释：</b>处理完一条消息后，要给 RabbitMQ 发个"回执"说"我处理完了"，
     * RabbitMQ 才会把这条消息从队列里删掉。这里加了 channel.isOpen() 检查，
     * 防止连接已断开时发 ack 报错。</p>
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
     * 安全地拒绝消息（nack）。
     *
     * <p><b>通俗解释：</b>处理失败了，告诉 RabbitMQ "这条消息我处理不了"。
     * 根据队列配置，消息可能会被丢弃，也可能进入死信队列（DLQ）等待人工排查。</p>
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

    /**
     * 将异常信息转换为用户友好的错误提示。
     *
     * <p><b>为什么需要：</b>技术性异常（如 NullPointerException）直接展示给用户会很困惑，
     * 所以对已知业务异常保留原始消息，未知异常统一返回"请稍后重试"。</p>
     */
    private String normalizeErrorMessage(Exception e) {
        if (e == null) {
            return "文件处理失败";
        }
        if (e instanceof IllegalStateException || e instanceof IllegalArgumentException) {
            return e.getMessage();
        }
        return "文件处理失败，请稍后重试";
    }
}

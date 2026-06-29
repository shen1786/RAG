package com.example.demo.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 文件处理任务消息体 —— 通过 RabbitMQ 传递，携带文件处理所需的全部信息。
 *
 * <h2>通俗解释</h2>
 * <p>就像一张"快递单"，上面写着：这是什么文件、存在哪里、谁的文件。
 * {@link com.example.demo.service.FileProcessProducer} 寄出这张单子，
 * {@link com.example.demo.service.FileProcessConsumer} 收到后按单子上的信息去处理。</p>
 *
 * <h2>在系统中的流转</h2>
 * <pre>
 * RagUnitService（上传文件时）
 *   │
 *   │  构建 FileProcessTask，填入以下信息：
 *   │  - sourceId: 文件的唯一标识（UUID）
 *   │  - filename: 原始文件名（如"简历.pdf"）
 *   │  - fileHash: 文件的 SHA-256 哈希（用于去重）
 *   │  - userId: 上传用户 ID
 *   │  - mimeType: 文件类型（如 "application/pdf"）
 *   │  - minioPath: MinIO 存储路径
 *   │  - minioUrl: MinIO 访问 URL（带签名的临时地址）
 *   │  - fileSize: 文件大小（字节）
 *   │  - createTimestamp: 任务创建时间
 *   │
 *   ▼
 * FileProcessProducer.send()  →  RabbitMQ（JSON 序列化）
 *   │
 *   ▼
 * FileProcessConsumer.processFile()  →  反序列化为 FileProcessTask 对象
 *   │
 *   ▼
 * 按 task 中的信息：下载文件 → 解析 → 切块 → 向量化
 * </pre>
 *
 * <h2>为什么实现 Serializable？</h2>
 * <p>RabbitMQ 消息需要序列化后才能传输。虽然我们用 Jackson JSON 序列化（配置在
 * {@link com.example.demo.Config.RabbitMQConfig#messageConverter()}），
 * 但实现 Serializable 是 Java 惯例，也方便未来切换序列化方式。</p>
 *
 * @see FileProcessProducer 生产者（发送任务）
 * @see FileProcessConsumer 消费者（接收并处理任务）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileProcessTask implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 源文件 ID（UUID），是整个处理流程的唯一标识，贯穿 MinIO/MySQL/Redis */
    private String sourceId;
    /** 原始文件名（如"简历.pdf"），用于日志展示和元数据存储 */
    private String filename;
    /** 文件 SHA-256 哈希值，用于文件去重（同一文件不重复处理） */
    private String fileHash;
    /** 上传用户 ID，用于数据隔离（每个用户只能看到自己的文件） */
    private String userId;
    /** MIME 类型（如 "application/pdf"、"image/png"），决定使用哪个 MediaProcessor 解析 */
    private String mimeType;
    /** MinIO 存储路径（bucket + object key），用于定位和删除文件 */
    private String minioPath;
    /** MinIO 访问 URL（带签名的临时地址），消费者通过它下载文件流 */
    private String minioUrl;
    /** 文件大小（字节），用于前端展示和日志记录 */
    private Long fileSize;
    /** 任务创建时间戳，用于监控处理耗时 */
    private Long createTimestamp;
}

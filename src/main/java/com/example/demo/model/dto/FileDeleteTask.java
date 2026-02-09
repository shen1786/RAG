package com.example.demo.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件删除任务消息体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileDeleteTask implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 任务ID（用于前端查询删除状态）
     */
    private String taskId;

    /**
     * 要删除的文件名
     */
    private String filename;

    /**
     * MinIO路径
     */
    private String minioPath;

    /**
     * 要删除的向量ID列表
     */
    private List<String> vectorIds;

    /**
     * 要删除的MySQL记录ID列表
     */
    private List<String> unitIds;

    /**
     * 当前重试次数
     */
    @Builder.Default
    private int retryCount = 0;

    /**
     * 最大重试次数
     */
    @Builder.Default
    private int maxRetries = 3;

    /**
     * 创建时间戳
     */
    private Long createTimestamp;

    /**
     * Redis删除状态
     */
    @Builder.Default
    private StepStatus redisStatus = StepStatus.PENDING;

    /**
     * MySQL删除状态
     */
    @Builder.Default
    private StepStatus mysqlStatus = StepStatus.PENDING;

    /**
     * MinIO删除状态
     */
    @Builder.Default
    private StepStatus minioStatus = StepStatus.PENDING;

    /**
     * 步骤执行状态
     */
    public enum StepStatus {
        PENDING,    // 待执行
        SUCCESS,    // 成功
        FAILED      // 失败（已重试多次）
    }

    /**
     * 检查是否需要重试
     */
    public boolean needsRetry() {
        return retryCount < maxRetries &&
                (redisStatus != StepStatus.SUCCESS ||
                 mysqlStatus != StepStatus.SUCCESS ||
                 minioStatus != StepStatus.SUCCESS);
    }

    /**
     * 增加重试次数
     */
    public void incrementRetry() {
        this.retryCount++;
    }

    /**
     * 检查是否全部成功
     */
    public boolean isAllSuccess() {
        return redisStatus == StepStatus.SUCCESS &&
               mysqlStatus == StepStatus.SUCCESS &&
               minioStatus == StepStatus.SUCCESS;
    }
}

package com.example.demo.Config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@ConfigurationProperties(prefix = "video")
@Data
public class VideoConfig {
    private int segmentDuration = 30;  // seconds
    private int keyframeInterval = 5;   // seconds
    private int threadPoolSize = 4;     // 并行处理线程数

    @Bean(name = "videoProcessorExecutor")
    public ExecutorService videoProcessorExecutor() {
        return Executors.newFixedThreadPool(threadPoolSize);
    }
}

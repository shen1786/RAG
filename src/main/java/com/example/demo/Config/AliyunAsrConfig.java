package com.example.demo.Config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "aliyun.asr")
@Data
public class AliyunAsrConfig {
    private String accessKeyId;
    private String accessKeySecret;
    private String appKey;
}

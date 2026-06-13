package com.example.demo.service;

import com.alibaba.nls.client.AccessToken;
import com.alibaba.nls.client.protocol.NlsClient;
import com.alibaba.nls.client.protocol.InputFormatEnum;
import com.alibaba.nls.client.protocol.SampleRateEnum;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriber;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriberListener;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriberResponse;
import com.example.demo.Config.AliyunAsrConfig;
import com.example.demo.exception.AsrUnavailableException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class AsrService {

    @Autowired
    private AliyunAsrConfig asrConfig;

    private volatile NlsClient nlsClient;
    private String accessToken;
    private volatile boolean configured = false;

    @PostConstruct
    public void init() {
        try {
            if (asrConfig.getAccessKeyId() != null && !asrConfig.getAccessKeyId().isEmpty()) {
                log.info("正在初始化阿里云 NLS 客户端...");
                AccessToken token = new AccessToken(asrConfig.getAccessKeyId(), asrConfig.getAccessKeySecret());
                token.apply();
                this.accessToken = token.getToken();
                this.nlsClient = new NlsClient(accessToken);
                this.configured = true;
                log.info("阿里云 ASR 客户端初始化成功");
            } else {
                log.warn("未配置阿里云 ASR 凭证，ASR 语音识别服务将不可用");
            }
        } catch (Exception e) {
            log.error("初始化阿里云 ASR 客户端失败", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (nlsClient != null) {
            nlsClient.shutdown();
        }
    }

    public String transcribe(InputStream audioStream) {
        return transcribeWithRetry(audioStream, 2); // 最多重试2次
    }

    /**
     * 判断 ASR 服务是否可用。
     */
    public boolean isAvailable() {
        return configured && nlsClient != null;
    }

    private String transcribeWithRetry(InputStream audioStream, int maxRetries) {
        if (!configured || nlsClient == null) {
            throw new AsrUnavailableException("ASR 语音识别服务未配置或初始化失败");
        }

        // 将流读入内存以便重试
        byte[] audioData;
        try {
            audioData = audioStream.readAllBytes();
        } catch (Exception e) {
            log.error("读取音频流失败", e);
            throw new AsrUnavailableException("读取音频流失败", e);
        }

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                log.info("ASR 重试第 {} 次", attempt);
            }

            String result = doTranscribe(new ByteArrayInputStream(audioData));
            if (result != null) {
                return result;
            }
        }

        log.warn("ASR 语音识别在重试 {} 次后仍失败", maxRetries);
        throw new AsrUnavailableException("ASR 语音识别失败，已重试 " + maxRetries + " 次");
    }

    private String doTranscribe(InputStream audioStream) {
        StringBuilder result = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        SpeechTranscriber transcriber = null;

        try {
            transcriber = new SpeechTranscriber(nlsClient, getTranscriberListener(result, latch));
            transcriber.setAppKey(asrConfig.getAppKey());
            transcriber.setFormat(InputFormatEnum.PCM);
            transcriber.setSampleRate(SampleRateEnum.SAMPLE_RATE_16K);
            transcriber.setEnableIntermediateResult(false);
            transcriber.setEnablePunctuation(true);
            transcriber.setEnableITN(true);

            transcriber.start();

            byte[] buffer = new byte[3200];
            int bytesRead;
            while ((bytesRead = audioStream.read(buffer)) != -1) {
                transcriber.send(buffer, bytesRead);
            }

            transcriber.stop();
            if (!latch.await(5, TimeUnit.SECONDS)) {
                log.warn("ASR 回调超时");
                return null;
            }

            return result.toString();

        } catch (Exception e) {
            log.error("ASR 语音识别过程中出现异常", e);
            return null;
        } finally {
            // 主动关闭 transcriber，避免 IDLE_TIMEOUT 错误
            if (transcriber != null) {
                try {
                    transcriber.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private SpeechTranscriberListener getTranscriberListener(StringBuilder result, CountDownLatch latch) {
        return new SpeechTranscriberListener() {
            @Override
            public void onTranscriberStart(SpeechTranscriberResponse response) {
                log.debug("ASR 语音识别已开始，任务 ID: {}", response.getTaskId());
            }

            @Override
            public void onSentenceBegin(SpeechTranscriberResponse response) {
                log.debug("句子开始，序号: {}", response.getTransSentenceIndex());
            }

            @Override
            public void onSentenceEnd(SpeechTranscriberResponse response) {
                String text = response.getTransSentenceText();
                if (text != null && !text.isEmpty()) {
                    result.append(text).append(" ");
                }
                log.debug("句子结束，内容: {}", text);
            }

            @Override
            public void onTranscriptionResultChange(SpeechTranscriberResponse response) {
                // Intermediate results, ignored as we set enableIntermediateResult to false
            }

            @Override
            public void onTranscriptionComplete(SpeechTranscriberResponse response) {
                latch.countDown();
            }

            @Override
            public void onFail(SpeechTranscriberResponse response) {
                log.error("ASR 语音识别失败: {}", response.getStatusText());
                latch.countDown();
            }
        };
    }
}

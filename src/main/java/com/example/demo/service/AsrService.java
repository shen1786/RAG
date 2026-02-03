package com.example.demo.service;

import com.alibaba.nls.client.AccessToken;
import com.alibaba.nls.client.protocol.NlsClient;
import com.alibaba.nls.client.protocol.InputFormatEnum;
import com.alibaba.nls.client.protocol.SampleRateEnum;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriber;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriberListener;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriberResponse;
import com.example.demo.Config.AliyunAsrConfig;
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

    @PostConstruct
    public void init() {
        try {
            if (asrConfig.getAccessKeyId() != null && !asrConfig.getAccessKeyId().isEmpty()) {
                log.info("Initializing Aliyun NLS client...");
                AccessToken token = new AccessToken(asrConfig.getAccessKeyId(), asrConfig.getAccessKeySecret());
                token.apply();
                this.accessToken = token.getToken();
                this.nlsClient = new NlsClient(accessToken);
                log.info("Aliyun ASR client initialized successfully");
            } else {
                log.warn("Aliyun ASR credentials not configured, ASR service will be unavailable");
            }
        } catch (Exception e) {
            log.error("Failed to initialize Aliyun ASR client", e);
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

    private String transcribeWithRetry(InputStream audioStream, int maxRetries) {
        if (nlsClient == null) {
            log.warn("ASR client not initialized, returning empty transcription");
            return "";
        }

        // 将流读入内存以便重试
        byte[] audioData;
        try {
            audioData = audioStream.readAllBytes();
        } catch (Exception e) {
            log.error("Failed to read audio stream", e);
            return "";
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

        log.warn("ASR transcription failed after {} retries", maxRetries);
        return "";
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
                log.warn("ASR callback timeout");
                return null;
            }

            return result.toString();

        } catch (Exception e) {
            log.error("Error during ASR transcription", e);
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
                log.debug("ASR transcription started, task_id: {}", response.getTaskId());
            }

            @Override
            public void onSentenceBegin(SpeechTranscriberResponse response) {
                log.debug("Sentence begin, index: {}", response.getTransSentenceIndex());
            }

            @Override
            public void onSentenceEnd(SpeechTranscriberResponse response) {
                String text = response.getTransSentenceText();
                if (text != null && !text.isEmpty()) {
                    result.append(text).append(" ");
                }
                log.debug("Sentence end: {}", text);
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
                log.error("ASR transcription failed: {}", response.getStatusText());
                latch.countDown();
            }
        };
    }
}

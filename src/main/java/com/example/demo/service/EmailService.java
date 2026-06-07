package com.example.demo.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${app.mail.from:${spring.mail.username:}}")
    private String fromAddress;

    @Value("${app.mail.fallback-dir:./temp/emails}")
    private String fallbackDir;

    public EmailService(@Nullable JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendResetCode(String username, String email, String resetCode, long expiresInSeconds) {
        try {
            if (canSendRealEmail()) {
                sendRealEmail(username, email, resetCode, expiresInSeconds);
                return;
            }
            log.info("未检测到可用邮件配置，重置码将写入本地模拟邮箱文件: username={}, email={}", username, email);
        } catch (Exception ex) {
            log.warn("发送真实重置邮件失败，降级写入本地模拟邮箱文件: username={}, email={}", username, email, ex);
        }

        writeFallbackMail(username, email, resetCode, expiresInSeconds);
    }

    private boolean canSendRealEmail() {
        return mailSender != null && fromAddress != null && !fromAddress.isBlank();
    }

    private void sendRealEmail(String username, String email, String resetCode, long expiresInSeconds) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(email);
        message.setSubject("RAG Knowledge Hub 密码重置验证码");
        message.setText(buildMailBody(username, email, resetCode, expiresInSeconds));
        mailSender.send(message);
        log.info("密码重置邮件发送成功: username={}, email={}", username, email);
    }

    private void writeFallbackMail(String username, String email, String resetCode, long expiresInSeconds) {
        String maskedCode = resetCode.length() >= 2 ? resetCode.substring(0, 2) + "****" : "******";
        try {
            Path outputDir = Paths.get(fallbackDir);
            Files.createDirectories(outputDir);
            Path outputFile = outputDir.resolve("reset-" + sanitizeFilename(username) + ".txt");
            Files.writeString(outputFile, buildMailBody(username, email, maskedCode, expiresInSeconds), StandardCharsets.UTF_8);
            log.info("密码重置模拟邮件已写入: {}", outputFile.toAbsolutePath());
        } catch (IOException ex) {
            log.error("写入密码重置模拟邮件失败: username={}, email={}", username, email, ex);
        }
    }

    private String buildMailBody(String username, String email, String resetCode, long expiresInSeconds) {
        return String.format(
                "RAG Knowledge Hub - 密码重置通知%n%n收件账号: %s%n收件邮箱: %s%n发送时间: %s%n验证码: %s%n有效期: %d 秒%n%n请在有效期内完成密码重置。如果这不是您的操作，请忽略本邮件。%n",
                username,
                email,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                resetCode,
                expiresInSeconds
        );
    }

    private String sanitizeFilename(String username) {
        return username.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}

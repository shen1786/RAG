package com.example.demo.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EmailServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldWriteFallbackMailWhenMailSenderUnavailable() throws Exception {
        EmailService emailService = new EmailService(null);
        ReflectionTestUtils.setField(emailService, "fallbackDir", tempDir.toString());
        ReflectionTestUtils.setField(emailService, "fromAddress", "");

        emailService.sendResetCode("alice", "alice@example.com", "123456", 600);

        Path mailFile = tempDir.resolve("reset-alice.txt");
        assertTrue(Files.exists(mailFile));
        String content = Files.readString(mailFile);
        assertTrue(content.contains("alice@example.com"));
        assertTrue(content.contains("123456"));
    }

    @Test
    void shouldUseJavaMailSenderWhenConfigured() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        EmailService emailService = new EmailService(mailSender);
        ReflectionTestUtils.setField(emailService, "fallbackDir", tempDir.toString());
        ReflectionTestUtils.setField(emailService, "fromAddress", "noreply@example.com");

        emailService.sendResetCode("alice", "alice@example.com", "123456", 600);

        verify(mailSender).send(any(SimpleMailMessage.class));
    }
}

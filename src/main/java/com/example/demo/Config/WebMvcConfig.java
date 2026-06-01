package com.example.demo.Config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(mvcTaskExecutor());
        // configurer.setDefaultTimeout(30000); // optional timeout 
    }

    @Bean
    public TaskDecorator requestContextTaskDecorator() {
        return runnable -> {
            RequestAttributes capturedRequestAttributes = RequestContextHolder.getRequestAttributes();
            return () -> {
                RequestAttributes previousRequestAttributes = RequestContextHolder.getRequestAttributes();
                try {
                    if (capturedRequestAttributes != null) {
                        RequestContextHolder.setRequestAttributes(capturedRequestAttributes);
                    } else {
                        RequestContextHolder.resetRequestAttributes();
                    }
                    runnable.run();
                } finally {
                    if (previousRequestAttributes != null) {
                        RequestContextHolder.setRequestAttributes(previousRequestAttributes);
                    } else {
                        RequestContextHolder.resetRequestAttributes();
                    }
                }
            };
        };
    }

    @Bean
    public ThreadPoolTaskExecutor mvcTaskExecutor() {
        ThreadPoolTaskExecutor threadPoolTaskExecutor = new ThreadPoolTaskExecutor();
        threadPoolTaskExecutor.setCorePoolSize(10);
        threadPoolTaskExecutor.setMaxPoolSize(100);
        threadPoolTaskExecutor.setQueueCapacity(200);
        threadPoolTaskExecutor.setThreadNamePrefix("mvc-async-");
        threadPoolTaskExecutor.setTaskDecorator(requestContextTaskDecorator());
        threadPoolTaskExecutor.initialize();
        return threadPoolTaskExecutor;
    }
}

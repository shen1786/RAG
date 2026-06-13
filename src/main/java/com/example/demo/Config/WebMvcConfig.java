package com.example.demo.Config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private static final int MVC_CORE_POOL_SIZE = 10;
    private static final int MVC_MAX_POOL_SIZE = 100;
    private static final int MVC_QUEUE_CAPACITY = 200;
    private static final int MVC_AWAIT_TERMINATION_SECONDS = 30;
    private static final int CORS_MAX_AGE = 3600;

    private final CorsProperties corsProperties;

    public WebMvcConfig(CorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> origins = corsProperties.getAllowedOrigins();
        if (origins == null || origins.isEmpty()) {
            return;
        }
        registry.addMapping("/**")
                .allowedOrigins(origins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(CORS_MAX_AGE);
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(mvcTaskExecutor());
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
        threadPoolTaskExecutor.setCorePoolSize(MVC_CORE_POOL_SIZE);
        threadPoolTaskExecutor.setMaxPoolSize(MVC_MAX_POOL_SIZE);
        threadPoolTaskExecutor.setQueueCapacity(MVC_QUEUE_CAPACITY);
        threadPoolTaskExecutor.setThreadNamePrefix("mvc-async-");
        threadPoolTaskExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        threadPoolTaskExecutor.setWaitForTasksToCompleteOnShutdown(true);
        threadPoolTaskExecutor.setAwaitTerminationSeconds(MVC_AWAIT_TERMINATION_SECONDS);
        threadPoolTaskExecutor.setTaskDecorator(requestContextTaskDecorator());
        threadPoolTaskExecutor.initialize();
        return threadPoolTaskExecutor;
    }
}

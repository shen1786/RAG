package com.example.demo.Config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import com.alibaba.cloud.ai.memory.redis.RedisChatMemoryRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.prompt.ChatOptions;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Aiconfig {

    @Bean
    public DashScopeApi dashScopeApi() {
        return DashScopeApi.builder()
                .apiKey("your-dashscope-api-key")
                .build();
    }
    @Bean(name = "qwen")
    public DashScopeChatModel dashScopeChatModel(DashScopeApi dashScopeApi) {
        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel("qwen-vl-max")
                        .withMultiModel(true)
                        .build())
                .build();
    }
    @Bean(name = "deepchat")
    public DashScopeChatModel deepChatModel(DashScopeApi dashScopeApi) {
        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel("qwen-plus")
                        .build())
                .build();
    }

    @Bean
    public ChatClient chatClient(@Qualifier("qwen") DashScopeChatModel dashScopeChatModel,
                                 @Qualifier("redisChatMemoryRepository")RedisChatMemoryRepository redisChatMemoryRepository,
                                 ToolCallbackProvider tools
                                 ) {
        MessageWindowChatMemory windowChatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(redisChatMemoryRepository)
                .maxMessages(10)
                .build();
        return ChatClient.builder(dashScopeChatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(windowChatMemory).build())
                .defaultOptions(ChatOptions.builder().model("qwen3-omni-flash-2025-12-01").build())
                .defaultToolCallbacks(tools.getToolCallbacks())
                .build();
    }
    @Bean(value = "deepchatClient")
    public ChatClient deepchatClient(@Qualifier("deepchat") DashScopeChatModel dashScopeChatModel,
                                 @Qualifier("redisChatMemoryRepository")RedisChatMemoryRepository redisChatMemoryRepository,
                                 ToolCallbackProvider tools
    ) {
        MessageWindowChatMemory windowChatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(redisChatMemoryRepository)
                .maxMessages(10)
                .build();
        return ChatClient.builder(dashScopeChatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(windowChatMemory).build())
                .defaultOptions(ChatOptions.builder().model("qwen-plus").build())
                .defaultToolCallbacks(tools.getToolCallbacks())
                .build();
    }
}


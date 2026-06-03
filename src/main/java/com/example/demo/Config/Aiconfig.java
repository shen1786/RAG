package com.example.demo.Config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankModel;
import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankOptions;
import com.alibaba.cloud.ai.memory.redis.RedisChatMemoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

import com.alibaba.cloud.ai.model.RerankModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class Aiconfig {
    @Value("${spring.ai.dashscope.api-key}")
    private String API_KEY;

    @Bean
    public DashScopeApi dashScopeApi() {
        return DashScopeApi.builder()
                .apiKey(API_KEY)
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

    /**
     * 鑷畾涔夎亰澶╄蹇嗭細婊戝姩绐楀彛 + 绱Н鎽樿
     * 瀹屾暣鍘嗗彶姘镐笉涓㈠け锛屾棫娑堟伅鑷姩鍘嬬缉涓烘憳瑕佹敞鍏ユā鍨嬩笂涓嬫枃
     */
    @Bean
    public SummaryWindowChatMemory chatMemory(
            @Qualifier("redisChatMemoryRepository") RedisChatMemoryRepository repository,
            @Qualifier("summaryChatClient") ChatClient summaryChatClient,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${chat.memory.max-messages:10}") int maxMessages,
            @Value("${chat.memory.summarize-threshold:12}") int summarizeThreshold,
            @Value("${chat.memory.assistant-truncate-length:300}") int assistantTruncateLength,
            @Value("${chat.memory.summary-max-length:500}") int summaryMaxLength) {
        if (summarizeThreshold <= maxMessages) {
            throw new IllegalArgumentException(
                    String.format("chat.memory.summarize-threshold(%d) 蹇呴』澶т簬 chat.memory.max-messages(%d)",
                            summarizeThreshold, maxMessages));
        }
        return new SummaryWindowChatMemory(
                repository, summaryChatClient, redisTemplate, objectMapper,
                maxMessages, summarizeThreshold, assistantTruncateLength, summaryMaxLength);
    }

    @Bean
    public ChatClient chatClient(@Qualifier("qwen") DashScopeChatModel dashScopeChatModel,
                                 ChatMemory chatMemory,
                                 ToolCallbackProvider tools
                                 ) {
        return ChatClient.builder(dashScopeChatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultOptions(ChatOptions.builder().model("qwen3-omni-flash-2025-12-01").build())
                .defaultToolCallbacks(tools.getToolCallbacks())
                .build();
    }
    @Bean(value = "deepchatClient")
    public ChatClient deepchatClient(@Qualifier("deepchat") DashScopeChatModel dashScopeChatModel,
                                 ChatMemory chatMemory,
                                 ToolCallbackProvider tools
    ) {
        return ChatClient.builder(dashScopeChatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultOptions(ChatOptions.builder().model("qwen-plus").build())
                .defaultToolCallbacks(tools.getToolCallbacks())
                .build();
    }

    @Bean("summaryChatClient")
    public ChatClient summaryChatClient(@Qualifier("deepchat") ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultOptions(ChatOptions.builder().model("qwen-plus").build())
                .build();
    }

    /**
     * DashScope Rerank 妯″瀷锛堢簿鎺掞級
     * 鐢ㄤ簬瀵瑰悜閲忔绱㈢殑绮楀彫鍥炵粨鏋滆繘琛屼簩娆＄簿鎺掞紝鎻愬崌 RAG 妫€绱㈣川閲?
     */
    @Bean
    public RerankModel rerankModel(DashScopeApi dashScopeApi,
                                   @Value("${rag.rerank.model:gte-rerank-v2}") String rerankModelName) {
        return new DashScopeRerankModel(dashScopeApi,
                DashScopeRerankOptions.builder()
                        .withModel(rerankModelName)
                        .build());
    }
}


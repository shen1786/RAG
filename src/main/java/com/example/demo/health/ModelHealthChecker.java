package com.example.demo.health;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.model.RerankModel;
import com.alibaba.cloud.ai.model.RerankRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class ModelHealthChecker {

    private final ChatClient chatClient;
    private final ChatClient deepchatClient;
    private final ChatModel visionChatModel;
    private final RerankModel rerankModel;

    @Value("${rag.model.main-chat:qwen3-omni-flash-2025-12-01}")
    private String mainChatModel;

    @Value("${rag.model.deep-chat:qwen-plus}")
    private String deepChatModel;

    @Value("${rag.model.vision:qwen-vl-max}")
    private String visionModel;

    @Value("${rag.model.rerank:gte-rerank-v2}")
    private String rerankModelName;

    public ModelHealthChecker(ChatClient chatClient,
                              @Qualifier("deepchatClient") ChatClient deepchatClient,
                              @Qualifier("qwen") ChatModel visionChatModel,
                              RerankModel rerankModel) {
        this.chatClient = chatClient;
        this.deepchatClient = deepchatClient;
        this.visionChatModel = visionChatModel;
        this.rerankModel = rerankModel;
        System.out.println(">>> ModelHealthChecker bean created");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkModels() {
        System.out.println(">>> ModelHealthChecker.checkModels() started");
        log.info("========== 模型可用性检查开始 ==========");
        int ok = 0;
        int fail = 0;

        if (pingChat(deepchatClient, "deep-chat", deepChatModel)) ok++; else fail++;
        if (pingVision()) ok++; else fail++;
        if (pingChat(chatClient, "main-chat", mainChatModel)) ok++; else fail++;
        if (pingRerank()) ok++; else fail++;

        log.info("========== 模型可用性检查完成: {} 通过, {} 失败 ==========", ok, fail);
        System.out.println(">>> ModelHealthChecker.checkModels() done: " + ok + " ok, " + fail + " fail");
    }

    private boolean pingChat(ChatClient client, String label, String modelName) {
        try {
            client.prompt().user("hi").call().content();
            log.info("[{}] 模型 {} 可用", label, modelName);
            return true;
        } catch (Exception e) {
            log.warn("[{}] 模型 {} 不可用: {}", label, modelName, e.getMessage());
            return false;
        }
    }

    private boolean pingVision() {
        try {
            ChatClient visionClient = ChatClient.builder(visionChatModel).build();
            visionClient.prompt(new Prompt("hi",
                    DashScopeChatOptions.builder()
                            .withModel(visionModel)
                            .build()))
                    .call().content();
            log.info("[vision] 模型 {} 可用", visionModel);
            return true;
        } catch (Exception e) {
            log.warn("[vision] 模型 {} 不可用: {}", visionModel, e.getMessage());
            return false;
        }
    }

    private boolean pingRerank() {
        try {
            RerankRequest request = new RerankRequest("test",
                    List.of(new Document("test document")));
            rerankModel.call(request);
            log.info("[rerank] 模型 {} 可用", rerankModelName);
            return true;
        } catch (Exception e) {
            log.warn("[rerank] 模型 {} 不可用: {}", rerankModelName, e.getMessage());
            return false;
        }
    }
}

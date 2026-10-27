package com.travelguide.ai;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 大模型统一出口。
 *
 * <p>封装 Spring AI 的 {@code OpenAiChatModel}（默认千问兼容接口）：
 * 只在配置了 {@code travel.ai.api-key} 时启用，{@link #complete(String, String)}
 * 接收系统指令 + 用户文本，返回纯文本；调用失败由使用方决定降级方式。
 * 全局没有第二处直连模型，换模型或换厂商只改这个类。</p>
 */
@Service
public class AiGateway {

    private final OpenAiChatModel chatModel;
    private final boolean enabled;

    public AiGateway(
            @Value("${travel.ai.api-key:}") String apiKey,
            @Value("${travel.ai.model:qwen-plus}") String model,
            @Value("${travel.ai.base-url:https://api.openai.com}") String baseUrl
    ) {
        this.enabled = apiKey != null && !apiKey.isBlank();
        if (enabled) {
            OpenAiApi api = new OpenAiApi(baseUrl, apiKey);
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .model(model)
                    .temperature(0.35)
                    .maxTokens(650)
                    .build();
            this.chatModel = new OpenAiChatModel(api, options);
        } else {
            this.chatModel = null;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String complete(String instructions, String userText) {
        if (!enabled) {
            throw new IllegalStateException("AI model is not configured");
        }
        ChatResponse response = chatModel.call(new Prompt(instructions + "\n\n用户上下文：\n" + userText));
        return response.getResult().getOutput().getText().trim();
    }
}

package com.travelguide.ai;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AiGateway {

    private final OpenAiChatModel chatModel;
    private final boolean enabled;

    public AiGateway(
            @Value("${travel.ai.api-key:}") String apiKey,
            @Value("${travel.ai.model:gpt-4o-mini}") String model
    ) {
        this.enabled = apiKey != null && !apiKey.isBlank();
        if (enabled) {
            OpenAiApi api = new OpenAiApi(apiKey);
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

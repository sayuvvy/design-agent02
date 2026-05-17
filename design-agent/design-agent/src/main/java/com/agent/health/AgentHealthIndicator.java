package com.agent.health;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component("agent")
public class AgentHealthIndicator implements HealthIndicator {

    private final ChatModel model;
    private final String aiProvider;
    private final String anthropicApiKey;
    private final String openAiApiKey;

    public AgentHealthIndicator(@Qualifier("anthropicChatModel") ChatModel anthropicModel,
                                @Qualifier("ollamaChatModel") @Nullable ChatModel ollamaModel,
                                @Value("${agent.ai.provider:anthropic}") String aiProvider,
                                @Value("${spring.ai.anthropic.api-key:}") String anthropicApiKey,
                                @Value("${spring.ai.openai.api-key:}") String openAiApiKey) {
        this.model = "ollama".equalsIgnoreCase(aiProvider) && ollamaModel != null ? ollamaModel : anthropicModel;
        this.aiProvider = aiProvider;
        this.anthropicApiKey = anthropicApiKey;
        this.openAiApiKey = openAiApiKey;
    }

    @Override
    public Health health() {
        // Ollama / local models don't need an API key
        if ("ollama".equalsIgnoreCase(aiProvider)) {
            return Health.up()
                    .withDetail("provider", "ollama (local)")
                    .withDetail("model", model.getClass().getSimpleName())
                    .withDetail("cost", "free")
                    .withDetail("openai-embeddings", "not required (ollama mode)")
                    .build();
        }

        // Check Anthropic key (used for chat / analysis)
        boolean anthropicOk = StringUtils.hasText(anthropicApiKey)
                && !anthropicApiKey.equals("your-api-key-here");

        // Check OpenAI key (used for embeddings / semantic memory)
        boolean openAiOk = StringUtils.hasText(openAiApiKey)
                && !openAiApiKey.equals("your-api-key-here");

        String anthropicStatus = anthropicOk ? "API key configured" : "❌ API key MISSING";
        String openAiStatus    = openAiOk    ? "API key configured" : "❌ API key MISSING";

        if (!anthropicOk || !openAiOk) {
            return Health.down()
                    .withDetail("provider", aiProvider)
                    .withDetail("anthropic-chat", anthropicStatus)
                    .withDetail("openai-embeddings", openAiStatus)
                    .withDetail("model", model.getClass().getSimpleName())
                    .build();
        }

        return Health.up()
                .withDetail("provider", aiProvider)
                .withDetail("anthropic-chat", anthropicStatus)
                .withDetail("openai-embeddings", openAiStatus)
                .withDetail("model", model.getClass().getSimpleName())
                .build();
    }
}

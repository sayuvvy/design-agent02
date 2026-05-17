package com.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

@Configuration
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    @Value("${agent.skills.directory:.claude/skills}")
    private String skillsDirectory;

    @Value("${agent.memory.max-messages:50}")
    private int maxMessages;

    @Value("${agent.ai.provider:anthropic}")
    private String aiProvider;

    @Value("${agent.token-limits.default-max-tokens:4096}")
    private int defaultMaxTokens;

    @Bean
    public ChatMemory chatMemory() {
        // IMPORTANT: Keep this LOW. Every message in the window is re-sent as
        // input tokens on every LLM call. 50 messages ≈ 25K input tokens.
        // At 500 messages you were burning 250K+ input tokens per call!
        log.info("ChatMemory window: {} messages (≈{}K input tokens per call)",
                maxMessages, maxMessages / 2);
        return MessageWindowChatMemory.builder()
                .maxMessages(maxMessages)
                .build();
    }

    /**
     * Per-phase max-token budgets (configurable via YAML):
     *
     *   agent.token-limits.analyze-max-tokens:  4096  (small codebase)
     *   agent.token-limits.design-max-tokens:   6144  (needs more output)
     *   agent.token-limits.default-max-tokens:  4096  (everything else)
     *
     * These are OUTPUT token caps — they limit how much the model writes
     * per turn, NOT how much it reads. Input tokens are controlled by
     * the memory window and prompt length.
     */
    @Bean
    public AnthropicChatOptions analyzeOptions(
            @Value("${agent.token-limits.analyze-max-tokens:4096}") int maxTokens) {
        return AnthropicChatOptions.builder().maxTokens(maxTokens).build();
    }

    @Bean
    public AnthropicChatOptions crossRefOptions(
            @Value("${agent.token-limits.crossref-max-tokens:4096}") int maxTokens) {
        return AnthropicChatOptions.builder().maxTokens(maxTokens).build();
    }

    @Bean
    public AnthropicChatOptions designOptions(
            @Value("${agent.token-limits.design-max-tokens:6144}") int maxTokens) {
        return AnthropicChatOptions.builder().maxTokens(maxTokens).build();
    }

    @Bean
    public AnthropicChatOptions publishOptions(
            @Value("${agent.token-limits.publish-max-tokens:4096}") int maxTokens) {
        return AnthropicChatOptions.builder().maxTokens(maxTokens).build();
    }

    @Bean
    public AnthropicChatOptions fetchJiraOptions(
            @Value("${agent.token-limits.fetchjira-max-tokens:2048}") int maxTokens) {
        return AnthropicChatOptions.builder().maxTokens(maxTokens).build();
    }

    @Bean
    public ChatClient chatClient(
            @Qualifier("anthropicChatModel") ChatModel anthropicModel,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            @Qualifier("ollamaChatModel") ChatModel ollamaModel,
            ChatMemory chatMemory) {

        ChatModel model = "ollama".equalsIgnoreCase(aiProvider) && ollamaModel != null
                ? ollamaModel : anthropicModel;
        log.info("AI provider: {} → ChatModel: {}", aiProvider, model.getClass().getSimpleName());
        log.info("Default max-tokens per turn: {}", defaultMaxTokens);

        SkillsTool.Builder skillsBuilder = SkillsTool.builder();

        try {
            PathMatchingResourcePatternResolver resolver =
                    new PathMatchingResourcePatternResolver();
            Resource[] skillFiles = resolver.getResources(
                    "classpath*:.claude/skills/*/SKILL.md");
            if (skillFiles.length > 0) {
                log.info("Found {} skill(s) on classpath:", skillFiles.length);
                for (Resource skill : skillFiles) {
                    Resource skillDir = skill.createRelative(".");
                    skillsBuilder.addSkillsResource(skillDir);
                    log.info("  + skill: {}", skill.getFilename());
                }
            } else {
                log.warn("No skills found on classpath at .claude/skills/*/SKILL.md");
            }
        } catch (Exception e) {
            log.warn("Could not scan classpath skills: {}", e.getMessage());
        }

        java.nio.file.Path externalSkills = java.nio.file.Path.of(skillsDirectory);
        if (java.nio.file.Files.isDirectory(externalSkills)) {
            log.info("Loading external skills from: {}", externalSkills.toAbsolutePath());
            skillsBuilder.addSkillsDirectory(skillsDirectory);
        }

        return ChatClient.builder(model)
                .defaultOptions(AnthropicChatOptions.builder()
                        .maxTokens(defaultMaxTokens)
                        .build())
                .defaultAdvisors(
                        ToolCallAdvisor.builder()
                                .build()
                )
                .defaultToolCallbacks(skillsBuilder.build())
                .build();
    }
}

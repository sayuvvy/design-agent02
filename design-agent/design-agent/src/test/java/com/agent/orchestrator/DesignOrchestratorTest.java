package com.agent.orchestrator;

import com.agent.cache.AnalysisCacheService;
import com.agent.memory.LongTermMemoryService;
import com.agent.memory.SemanticMemoryService;
import com.agent.memory.SessionMemoryService;
import com.agent.model.AgentPhase;
import com.agent.model.AgentRequest;
import com.agent.model.AgentResponse;
import com.agent.model.AgentStatus;
import com.agent.model.CodebaseComplexity;
import com.agent.service.CodebaseComplexityAnalyzer;
import com.agent.service.CodebaseIndexingService;
import com.agent.telemetry.TokenUsageReport;
import com.agent.telemetry.TokenUsageTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DesignOrchestratorTest {

    @Mock ChatClient chatClient;
    @Mock DesignPrompts prompts;
    @Mock DesignToolsFactory toolsFactory;
    @Mock AnalysisCacheService cacheService;
    @Mock SessionMemoryService sessionMemory;
    @Mock LongTermMemoryService longTermMemory;
    @Mock SemanticMemoryService semanticMemory;
    @Mock TokenUsageTracker tokenTracker;
    @Mock CodebaseIndexingService indexingService;

    @Mock ChatClient.ChatClientRequestSpec requestSpec;
    @Mock ChatClient.CallResponseSpec callSpec;
    @Mock ChatResponse chatResponse;

    DesignOrchestrator orchestrator;

    private AgentRequest baseRequest;

    @BeforeEach
    void setUp() {
        // Per-phase token limit options (test values)
        AnthropicChatOptions analyzeOpts = AnthropicChatOptions.builder().maxTokens(4096).build();
        AnthropicChatOptions crossRefOpts = AnthropicChatOptions.builder().maxTokens(4096).build();
        AnthropicChatOptions designOpts = AnthropicChatOptions.builder().maxTokens(6144).build();
        AnthropicChatOptions publishOpts = AnthropicChatOptions.builder().maxTokens(4096).build();
        AnthropicChatOptions fetchJiraOpts = AnthropicChatOptions.builder().maxTokens(2048).build();

        CodebaseComplexityAnalyzer complexityAnalyzer = mock(CodebaseComplexityAnalyzer.class);

        orchestrator = new DesignOrchestrator(chatClient, prompts, toolsFactory,
                cacheService, sessionMemory, longTermMemory, semanticMemory, tokenTracker,
                complexityAnalyzer, indexingService,
                analyzeOpts, crossRefOpts, designOpts, publishOpts, fetchJiraOpts);

        baseRequest = new AgentRequest(
                "session-1", null,
                "/workspace/my-app", "MYAPP",
                null, null, null, null, null, CodebaseComplexity.MEDIUM
        );

        // Stub the chat client chain
        lenient().when(chatClient.prompt()).thenReturn(requestSpec);
        lenient().when(requestSpec.system(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.user(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.options(any())).thenReturn(requestSpec);
        lenient().when(requestSpec.advisors((Consumer<ChatClient.AdvisorSpec>) any())).thenReturn(requestSpec);
        lenient().when(requestSpec.tools(any(Object[].class))).thenReturn(requestSpec);
        lenient().when(requestSpec.call()).thenReturn(callSpec);

        // Return ChatResponse with text
        Generation generation = new Generation(new AssistantMessage("phase output"));
        lenient().when(chatResponse.getResult()).thenReturn(generation);
        lenient().when(callSpec.chatResponse()).thenReturn(chatResponse);
        lenient().when(callSpec.content()).thenReturn("phase output");

        // Stub memory services to return empty
        lenient().when(longTermMemory.buildHistoricalContext(anyString())).thenReturn("");
        lenient().when(longTermMemory.computeProjectId(anyString())).thenReturn("test-project-id");
        lenient().when(semanticMemory.retrieveRelevantContext(anyString())).thenReturn("");
        lenient().when(sessionMemory.getPreviousContext(anyString())).thenReturn("");

        // Stub token tracker to return a dummy report
        lenient().when(tokenTracker.track(anyString(), anyString(), any(), any(ChatResponse.class), anyLong(), anyInt()))
                .thenReturn(new TokenUsageReport("s", "p", "m", 0, 0, 0, 0, 0, 0, 0, 0, Instant.now()));

        // Stub prompts
        lenient().when(prompts.fetchJiraSystem(any())).thenReturn("fetch system");
        lenient().when(prompts.fetchJiraUser(any())).thenReturn("fetch user");
        lenient().when(prompts.analyzeSystem(any())).thenReturn("analyze system");
        lenient().when(prompts.analyzeUser(any())).thenReturn("analyze user");
        lenient().when(prompts.crossRefSystem(any())).thenReturn("crossref system");
        lenient().when(prompts.crossRefUser(any())).thenReturn("crossref user");
        lenient().when(prompts.designSystem(any())).thenReturn("design system");
        lenient().when(prompts.designUser(any())).thenReturn("design user");
        lenient().when(prompts.publishSystem(any())).thenReturn("publish system");
        lenient().when(prompts.publishUser(any(), any())).thenReturn("publish user");

        lenient().when(toolsFactory.jiraReadTools()).thenReturn(new Object[0]);
        lenient().when(toolsFactory.codeReadTools()).thenReturn(new Object[0]);
        lenient().when(toolsFactory.publishTools(any())).thenReturn(new Object[0]);
        lenient().when(toolsFactory.lastUpdatedTickets()).thenReturn(List.of("MYAPP-1", "MYAPP-2"));
    }

    @Test
    @DisplayName("should run FETCH_JIRA phase successfully")
    void should_runFetchJira_successfully() {
        AgentRequest req = withPhase(AgentPhase.FETCH_JIRA);

        AgentResponse response = orchestrator.execute(req);

        assertThat(response.status()).isEqualTo(AgentStatus.SUCCESS);
        assertThat(response.phase()).isEqualTo(AgentPhase.FETCH_JIRA);
        verify(toolsFactory).jiraReadTools();
    }

    @Test
    @DisplayName("should run ANALYZE phase with code read tools")
    void should_runAnalyze_withCodeReadTools() {
        AgentRequest req = withPhase(AgentPhase.ANALYZE);

        AgentResponse response = orchestrator.execute(req);

        assertThat(response.status()).isEqualTo(AgentStatus.SUCCESS);
        assertThat(response.phase()).isEqualTo(AgentPhase.ANALYZE);
        verify(toolsFactory).codeReadTools();
        verify(toolsFactory, never()).jiraReadTools();
    }

    @Test
    @DisplayName("should run CROSS_REF phase with code read tools")
    void should_runCrossRef_withCodeReadTools() {
        AgentResponse response = orchestrator.execute(withPhase(AgentPhase.CROSS_REF));

        assertThat(response.status()).isEqualTo(AgentStatus.SUCCESS);
        assertThat(response.phase()).isEqualTo(AgentPhase.CROSS_REF);
        verify(toolsFactory).codeReadTools();
    }

    @Test
    @DisplayName("should run DESIGN phase with code read tools only")
    void should_runDesign_withCodeReadToolsOnly() {
        AgentResponse response = orchestrator.execute(withPhase(AgentPhase.DESIGN));

        assertThat(response.status()).isEqualTo(AgentStatus.SUCCESS);
        assertThat(response.phase()).isEqualTo(AgentPhase.DESIGN);
        verify(toolsFactory).codeReadTools();
        verify(toolsFactory, never()).publishTools(any());
    }

    @Test
    @DisplayName("should run PUBLISH phase and return DesignDocument")
    void should_runPublish_and_returnDesignDocument() {
        AgentResponse response = orchestrator.execute(withPhase(AgentPhase.PUBLISH));

        assertThat(response.status()).isEqualTo(AgentStatus.SUCCESS);
        assertThat(response.designDocument()).isNotNull();
        assertThat(response.designDocument().updatedJiraTickets())
                .containsExactly("MYAPP-1", "MYAPP-2");
        verify(toolsFactory).publishTools(any());
    }

    @Test
    @DisplayName("should run all 5 phases for FULL pipeline")
    void should_runAllPhases_for_fullPipeline() {
        AgentResponse response = orchestrator.execute(withPhase(AgentPhase.FULL));

        assertThat(response.status()).isEqualTo(AgentStatus.SUCCESS);
        assertThat(response.designDocument()).isNotNull();
        verify(chatClient, times(5)).prompt();
    }

    @Test
    @DisplayName("should default to FULL when phase is null")
    void should_defaultToFull_when_phaseIsNull() {
        AgentResponse response = orchestrator.execute(baseRequest);

        assertThat(response.phase()).isEqualTo(AgentPhase.FULL);
        verify(chatClient, times(5)).prompt();
    }

    @Test
    @DisplayName("should generate sessionId when null")
    void should_generateSessionId_when_null() {
        AgentRequest req = new AgentRequest(
                null, AgentPhase.ANALYZE,
                "/workspace/app", "MYAPP",
                null, null, null, null, null, CodebaseComplexity.MEDIUM);

        AgentResponse response = orchestrator.execute(req);

        assertThat(response.sessionId()).isNotNull().isNotBlank();
    }

    @Test
    @DisplayName("should return FAILED when chat client throws")
    void should_returnFailed_when_exception() {
        when(requestSpec.call()).thenThrow(new RuntimeException("API timeout"));

        AgentResponse response = orchestrator.execute(withPhase(AgentPhase.FETCH_JIRA));

        assertThat(response.status()).isEqualTo(AgentStatus.FAILED);
        assertThat(response.error()).contains("API timeout");
    }

    private AgentRequest withPhase(AgentPhase phase) {
        return new AgentRequest(
                baseRequest.sessionId(), phase,
                baseRequest.repoPath(), baseRequest.jiraProjectKey(),
                null, null, null, null, null, CodebaseComplexity.MEDIUM);
    }
}

package com.agent.controller;

import com.agent.memory.LongTermMemoryService;
import com.agent.memory.SemanticMemoryService;
import com.agent.memory.SessionMemoryService;
import com.agent.telemetry.TokenUsageTracker;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST endpoints for token-usage telemetry and memory status.
 *
 * <pre>
 * GET /api/telemetry/usage                — full usage dashboard (Anthropic + OpenAI)
 * GET /api/telemetry/usage/{sessionId}    — usage for one session
 * GET /api/telemetry/memory               — memory tier status
 * GET /api/telemetry/history?repoPath=... — project analysis history
 * GET /api/telemetry/embeddings           — OpenAI embedding usage (separate from chat)
 * </pre>
 */
@RestController
@RequestMapping("/api/telemetry")
public class TelemetryController {

    private final TokenUsageTracker tokenTracker;
    private final LongTermMemoryService longTermMemory;
    private final SessionMemoryService sessionMemory;
    private final SemanticMemoryService semanticMemory;

    public TelemetryController(TokenUsageTracker tokenTracker,
                                LongTermMemoryService longTermMemory,
                                SessionMemoryService sessionMemory,
                                SemanticMemoryService semanticMemory) {
        this.tokenTracker = tokenTracker;
        this.longTermMemory = longTermMemory;
        this.sessionMemory = sessionMemory;
        this.semanticMemory = semanticMemory;
    }

    @GetMapping("/usage")
    public ResponseEntity<Map<String, Object>> getUsageDashboard() {
        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("anthropic_chat", tokenTracker.getCurrentTotals());
        dashboard.put("openai_embeddings", semanticMemory.getEmbeddingTelemetry());
        dashboard.put("historical", longTermMemory.getUsageSummary());
        dashboard.put("activeSessions", sessionMemory.getActiveSessionCount());
        return ResponseEntity.ok(dashboard);
    }

    @GetMapping("/usage/{sessionId}")
    public ResponseEntity<?> getSessionUsage(@PathVariable String sessionId) {
        return ResponseEntity.ok(longTermMemory.getSessionUsage(sessionId));
    }

    @GetMapping("/memory")
    public ResponseEntity<Map<String, Object>> getMemoryStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("shortTerm", Map.of(
                "activeSessions", sessionMemory.getActiveSessionCount(),
                "sessionIds", sessionMemory.getAllSessions().keySet()
        ));
        status.put("longTerm", Map.of(
                "tokenUsageSummary", longTermMemory.getUsageSummary()
        ));
        return ResponseEntity.ok(status);
    }

    @GetMapping("/history")
    public ResponseEntity<?> getProjectHistory(@RequestParam String repoPath) {
        return ResponseEntity.ok(longTermMemory.getProjectHistory(repoPath, 20));
    }

    /**
     * Dedicated endpoint for OpenAI embedding usage — completely separate from Anthropic chat.
     * Hit this to see exactly how much your embeddings are costing.
     */
    @GetMapping("/embeddings")
    public ResponseEntity<Map<String, Object>> getEmbeddingUsage() {
        return ResponseEntity.ok(semanticMemory.getEmbeddingTelemetry());
    }
}

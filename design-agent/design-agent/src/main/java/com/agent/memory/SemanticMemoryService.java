package com.agent.memory;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Semantic memory service — stores and retrieves knowledge using real vector embeddings.
 * <p>
 * Uses OpenAI text-embedding-3-small via Spring AI's {@link EmbeddingModel} interface.
 * Vectors are stored in-memory with cosine similarity search — no external vector DB required.
 * <p>
 * When {@code agent.memory.semantic.enabled=false} or no {@link EmbeddingModel} bean is
 * available, all operations gracefully no-op and return empty strings.
 * <p>
 * Cost: text-embedding-3-small = $0.02 per 1M tokens (~$0.00002 per embed call).
 * <p>
 * <b>TELEMETRY:</b> Every embedding API call is tracked separately from Anthropic chat:
 * <ul>
 *   <li>Micrometer counters: {@code agent.embedding.calls}, {@code agent.embedding.tokens}, {@code agent.embedding.cost.usd}</li>
 *   <li>SQLite: persisted to {@code token_usage_log} with phase=EMBEDDING_STORE / EMBEDDING_QUERY</li>
 *   <li>Budget: embedding costs count toward the daily budget</li>
 * </ul>
 */
@Service
public class SemanticMemoryService {

    private static final Logger log = LoggerFactory.getLogger(SemanticMemoryService.class);

    /** Approximate tokens-per-char ratio for English text (conservative estimate). */
    private static final double TOKENS_PER_CHAR = 0.3;

    /** OpenAI text-embedding-3-small pricing: $0.02 per 1M tokens. */
    private static final double EMBEDDING_COST_PER_MILLION = 0.02;

    private static final String EMBEDDING_MODEL_NAME = "text-embedding-3-small";

    @Value("${agent.memory.semantic.enabled:false}")
    private boolean enabled;

    @Value("${agent.memory.semantic.top-k:5}")
    private int topK;

    @Value("${agent.memory.semantic.max-chunk-chars:2000}")
    private int maxChunkChars;

    private final EmbeddingModel embeddingModel;
    private final LongTermMemoryService longTermMemory;
    private final MeterRegistry meterRegistry;

    // ── Embedding telemetry counters ─────────────────────────────────
    private final AtomicLong totalEmbeddingCalls = new AtomicLong();
    private final AtomicLong totalEmbeddingTokens = new AtomicLong();
    private final AtomicLong totalStoreCalls = new AtomicLong();
    private final AtomicLong totalQueryCalls = new AtomicLong();

    // Thread-safe in-memory vector store
    private final List<VectorEntry> vectorStore = new CopyOnWriteArrayList<>();

    /**
     * Constructor — EmbeddingModel is optional. If Spring can't wire one
     * (e.g. no OpenAI key configured), we still start up but stay disabled.
     */
    public SemanticMemoryService(@Autowired(required = false) @Qualifier("openAiEmbeddingModel") EmbeddingModel embeddingModel,
                                  LongTermMemoryService longTermMemory,
                                  MeterRegistry meterRegistry) {
        this.embeddingModel = embeddingModel;
        this.longTermMemory = longTermMemory;
        this.meterRegistry = meterRegistry;
    }

    public boolean isAvailable() {
        return enabled && embeddingModel != null;
    }

    /**
     * Store a piece of knowledge with its vector embedding.
     * Tracks embedding token usage in Micrometer + SQLite.
     */
    public void storeKnowledge(String content, String category, String projectId,
                                Map<String, Object> metadata) {
        if (!isAvailable()) return;
        try {
            String chunk = truncate(content, maxChunkChars);
            long startMs = System.currentTimeMillis();
            float[] embedding = embeddingModel.embed(chunk);
            long durationMs = System.currentTimeMillis() - startMs;

            vectorStore.add(new VectorEntry(chunk, embedding, category, projectId, metadata, Instant.now()));

            // ── Telemetry: track this embedding call ──
            long estimatedTokens = estimateTokens(chunk);
            trackEmbeddingUsage("EMBEDDING_STORE", projectId, estimatedTokens, durationMs);

            log.info("[SEMANTIC-MEMORY] Stored {} knowledge for project {} ({} chars, {}d vector, ~{} tokens, {}ms)",
                    category, projectId, chunk.length(), embedding.length, estimatedTokens, durationMs);
        } catch (Exception e) {
            log.warn("[SEMANTIC-MEMORY] Failed to embed and store: {}", e.getMessage());
        }
    }

    /**
     * Convenience method used by the orchestrator after each phase.
     */
    public void storeAnalysisKnowledge(String projectId, String repoPath,
                                        String phase, String content) {
        if (!isAvailable()) return;
        storeKnowledge(content, phase, projectId,
                Map.of("repoPath", repoPath, "phase", phase));
    }

    /**
     * Retrieve the top-K most semantically similar entries to the query.
     * Returns a formatted context string, or empty string if nothing relevant.
     * Tracks embedding token usage for the query vector.
     */
    public String retrieveRelevantContext(String query) {
        if (!isAvailable() || vectorStore.isEmpty()) return "";
        try {
            long startMs = System.currentTimeMillis();
            float[] queryVector = embeddingModel.embed(query);
            long durationMs = System.currentTimeMillis() - startMs;

            // ── Telemetry: track this embedding call ──
            long estimatedTokens = estimateTokens(query);
            trackEmbeddingUsage("EMBEDDING_QUERY", "query", estimatedTokens, durationMs);

            // Score all entries by cosine similarity, take top-K
            List<ScoredEntry> scored = new ArrayList<>(vectorStore.size());
            for (VectorEntry entry : vectorStore) {
                double sim = cosineSimilarity(queryVector, entry.embedding());
                scored.add(new ScoredEntry(entry, sim));
            }
            scored.sort(Comparator.comparingDouble(ScoredEntry::score).reversed());

            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (ScoredEntry se : scored) {
                if (count >= topK) break;
                if (se.score() < 0.3) break; // similarity threshold — skip noise
                sb.append(String.format("\n[%s | project:%s | similarity:%.2f]\n%s\n",
                        se.entry().category(), se.entry().projectId(),
                        se.score(), se.entry().content()));
                count++;
            }

            if (sb.isEmpty()) return "";
            log.debug("[SEMANTIC-MEMORY] Retrieved {} relevant entries for query ({}...)",
                    count, query.substring(0, Math.min(60, query.length())));
            return "\n\n--- SEMANTIC CONTEXT (vector similarity) ---\n" + sb;

        } catch (Exception e) {
            log.warn("[SEMANTIC-MEMORY] Retrieval failed: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Retrieve only CODEBASE_CHUNK entries for a project — used by the RAG search tool.
     * Filters strictly to category "CODEBASE_CHUNK" so phase-output memories don't pollute
     * code search results.
     */
    public String retrieveCodebaseChunks(String projectId, String query) {
        if (!isAvailable()) return "";
        try {
            long startMs = System.currentTimeMillis();
            float[] queryVector = embeddingModel.embed(query);
            long durationMs = System.currentTimeMillis() - startMs;

            long estimatedTokens = estimateTokens(query);
            trackEmbeddingUsage("EMBEDDING_QUERY", projectId, estimatedTokens, durationMs);

            List<ScoredEntry> scored = new ArrayList<>();
            for (VectorEntry entry : vectorStore) {
                if (!projectId.equals(entry.projectId())) continue;
                if (!"CODEBASE_CHUNK".equals(entry.category())) continue;
                double sim = cosineSimilarity(queryVector, entry.embedding());
                scored.add(new ScoredEntry(entry, sim));
            }
            scored.sort(Comparator.comparingDouble(ScoredEntry::score).reversed());

            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (ScoredEntry se : scored) {
                if (count >= topK) break;
                if (se.score() < 0.25) break;
                Object fileTag = se.entry().metadata().get("file");
                sb.append(String.format("\n--- File: %s (similarity: %.2f) ---\n%s\n",
                        fileTag != null ? fileTag : "unknown",
                        se.score(),
                        se.entry().content()));
                count++;
            }

            if (sb.isEmpty()) return "No relevant code found for: " + query;
            log.info("[RAG-SEARCH] '{}' → {} chunks returned for project={}", query, count, projectId);
            return sb.toString();
        } catch (Exception e) {
            log.warn("[RAG-SEARCH] Retrieval failed: {}", e.getMessage());
            return "Search failed: " + e.getMessage();
        }
    }

    /**
     * Retrieve knowledge filtered to a specific project.
     * Tracks embedding token usage for the query vector.
     */
    public String retrieveProjectKnowledge(String projectId, String query) {
        if (!isAvailable()) return "";
        try {
            long startMs = System.currentTimeMillis();
            float[] queryVector = embeddingModel.embed(query);
            long durationMs = System.currentTimeMillis() - startMs;

            // ── Telemetry: track this embedding call ──
            long estimatedTokens = estimateTokens(query);
            trackEmbeddingUsage("EMBEDDING_QUERY", projectId, estimatedTokens, durationMs);
            List<ScoredEntry> scored = new ArrayList<>();
            for (VectorEntry entry : vectorStore) {
                if (!projectId.equals(entry.projectId())) continue;
                double sim = cosineSimilarity(queryVector, entry.embedding());
                scored.add(new ScoredEntry(entry, sim));
            }
            scored.sort(Comparator.comparingDouble(ScoredEntry::score).reversed());

            return scored.stream()
                    .limit(topK)
                    .filter(se -> se.score() >= 0.3)
                    .map(se -> se.entry().content())
                    .reduce((a, b) -> a + "\n\n" + b)
                    .orElse("");
        } catch (Exception e) {
            log.warn("[SEMANTIC-MEMORY] Project retrieval failed: {}", e.getMessage());
            return "";
        }
    }

    /** Number of entries in the vector store (for telemetry). */
    public int size() {
        return vectorStore.size();
    }

    /** Clear all entries (useful for testing). */
    public void clear() {
        vectorStore.clear();
    }

    // ── Embedding Telemetry ────────────────────────────────────────

    /**
     * Track a single embedding API call — Micrometer counters + SQLite persistence.
     * Uses a separate phase prefix (EMBEDDING_STORE / EMBEDDING_QUERY) so it shows
     * up distinctly from Anthropic chat phases in the dashboard.
     */
    private void trackEmbeddingUsage(String phase, String context, long estimatedTokens, long durationMs) {
        try {
            double cost = (estimatedTokens / 1_000_000.0) * EMBEDDING_COST_PER_MILLION;

            // In-memory counters
            totalEmbeddingCalls.incrementAndGet();
            totalEmbeddingTokens.addAndGet(estimatedTokens);
            if (phase.contains("STORE")) {
                totalStoreCalls.incrementAndGet();
            } else {
                totalQueryCalls.incrementAndGet();
            }

            // Micrometer — tagged distinctly from Anthropic
            Counter.builder("agent.embedding.calls")
                    .tag("phase", phase).tag("model", EMBEDDING_MODEL_NAME)
                    .register(meterRegistry).increment();
            Counter.builder("agent.embedding.tokens")
                    .tag("phase", phase).tag("model", EMBEDDING_MODEL_NAME)
                    .register(meterRegistry).increment(estimatedTokens);
            Counter.builder("agent.embedding.cost.usd")
                    .tag("phase", phase).tag("model", EMBEDDING_MODEL_NAME)
                    .register(meterRegistry).increment(cost);

            // Persist to SQLite — same table, distinct phase + model
            longTermMemory.logTokenUsage(
                    "embedding-" + context, phase, EMBEDDING_MODEL_NAME,
                    estimatedTokens, 0, cost, durationMs, 0);

            log.debug("[EMBEDDING-TELEMETRY] {} | ~{} tokens | ${} | {}ms",
                    phase, estimatedTokens, String.format("%.8f", cost), durationMs);
        } catch (Exception e) {
            log.debug("[EMBEDDING-TELEMETRY] Failed to track: {}", e.getMessage());
        }
    }

    /** Approximate token count from character length (conservative: 1 token ≈ 3.3 chars). */
    private long estimateTokens(String text) {
        return Math.max(1, Math.round(text.length() * TOKENS_PER_CHAR));
    }

    /**
     * Return embedding-specific telemetry for the dashboard.
     * Kept separate from Anthropic chat telemetry so you can see exactly
     * how much OpenAI embedding is costing you.
     */
    public Map<String, Object> getEmbeddingTelemetry() {
        long tokens = totalEmbeddingTokens.get();
        double cost = (tokens / 1_000_000.0) * EMBEDDING_COST_PER_MILLION;
        return Map.of(
                "model", EMBEDDING_MODEL_NAME,
                "enabled", isAvailable(),
                "totalEmbeddingCalls", totalEmbeddingCalls.get(),
                "storeCalls", totalStoreCalls.get(),
                "queryCalls", totalQueryCalls.get(),
                "estimatedTokens", tokens,
                "estimatedCostUsd", cost,
                "vectorStoreSize", vectorStore.size(),
                "costPerMillionTokens", EMBEDDING_COST_PER_MILLION
        );
    }

    // ── Vector math ─────────────────────────────────────────────────

    private static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0.0 : dot / denom;
    }

    private String truncate(String text, int max) {
        return text.length() > max ? text.substring(0, max) + "..." : text;
    }

    // ── Internal records ────────────────────────────────────────────

    record VectorEntry(
            String content, float[] embedding, String category, String projectId,
            Map<String, Object> metadata, Instant storedAt
    ) {}

    record ScoredEntry(VectorEntry entry, double score) {}
}

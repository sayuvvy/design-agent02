package com.agent.telemetry;

import com.agent.memory.LongTermMemoryService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central token-usage tracker.
 * <ul>
 *   <li>Extracts token counts from {@link ChatResponse} metadata</li>
 *   <li>Calculates cost based on configurable per-model pricing</li>
 *   <li>Records Micrometer counters/timers (exportable to Prometheus)</li>
 *   <li>Persists every call to SQLite via {@link LongTermMemoryService}</li>
 *   <li>Checks daily budget and logs warnings</li>
 * </ul>
 */
@Component
public class TokenUsageTracker {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageTracker.class);

    private final LongTermMemoryService longTermMemory;
    private final MeterRegistry meterRegistry;

    @Value("${agent.telemetry.cost.input-per-million:1.00}")
    private double inputCostPerMillion;

    @Value("${agent.telemetry.cost.output-per-million:5.00}")
    private double outputCostPerMillion;

    @Value("${agent.telemetry.budget.daily-limit-usd:5.00}")
    private double dailyBudgetUsd;

    @Value("${agent.telemetry.budget.alert-threshold:0.8}")
    private double alertThreshold;

    // Running in-memory totals
    private final AtomicLong totalInputTokens = new AtomicLong();
    private final AtomicLong totalOutputTokens = new AtomicLong();
    private final AtomicLong totalCalls = new AtomicLong();
    private final Map<String, AtomicLong> tokensByPhase = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> tokensByModel = new ConcurrentHashMap<>();

    public TokenUsageTracker(LongTermMemoryService longTermMemory,
                              MeterRegistry meterRegistry) {
        this.longTermMemory = longTermMemory;
        this.meterRegistry = meterRegistry;

        // Register gauges for current-process totals
        Gauge.builder("agent.tokens.input.total", totalInputTokens, AtomicLong::get)
                .description("Total input tokens consumed").register(meterRegistry);
        Gauge.builder("agent.tokens.output.total", totalOutputTokens, AtomicLong::get)
                .description("Total output tokens consumed").register(meterRegistry);
        Gauge.builder("agent.calls.total", totalCalls, AtomicLong::get)
                .description("Total LLM API calls").register(meterRegistry);
    }

    /**
     * Extract usage from a ChatResponse, calculate cost, persist, and return a report.
     */
    public TokenUsageReport track(String sessionId, String phase, String model,
                                   ChatResponse response, long durationMs, int toolCalls) {
        long inputTokens = 0;
        long outputTokens = 0;

        if (response != null && response.getMetadata() != null) {
            Usage usage = response.getMetadata().getUsage();
            if (usage != null) {
                inputTokens = usage.getPromptTokens();
                outputTokens = usage.getCompletionTokens();
            }
        }

        return trackRaw(sessionId, phase, model, inputTokens, outputTokens, durationMs, toolCalls);
    }

    /**
     * Track raw token counts (when you already know the numbers).
     */
    public TokenUsageReport trackRaw(String sessionId, String phase, String model,
                                      long inputTokens, long outputTokens,
                                      long durationMs, int toolCalls) {
        double inputCost  = (inputTokens  / 1_000_000.0) * inputCostPerMillion;
        double outputCost = (outputTokens / 1_000_000.0) * outputCostPerMillion;
        double totalCost  = inputCost + outputCost;

        // Update in-memory counters
        totalInputTokens.addAndGet(inputTokens);
        totalOutputTokens.addAndGet(outputTokens);
        totalCalls.incrementAndGet();
        tokensByPhase.computeIfAbsent(phase, k -> new AtomicLong()).addAndGet(inputTokens + outputTokens);
        tokensByModel.computeIfAbsent(model, k -> new AtomicLong()).addAndGet(inputTokens + outputTokens);

        // Micrometer counters (labelled by phase + model)
        Counter.builder("agent.tokens.input")
                .tag("phase", phase).tag("model", model)
                .register(meterRegistry).increment(inputTokens);
        Counter.builder("agent.tokens.output")
                .tag("phase", phase).tag("model", model)
                .register(meterRegistry).increment(outputTokens);
        Counter.builder("agent.cost.usd")
                .tag("phase", phase).tag("model", model)
                .register(meterRegistry).increment(totalCost);
        Timer.builder("agent.phase.duration")
                .tag("phase", phase)
                .register(meterRegistry).record(Duration.ofMillis(durationMs));
        Counter.builder("agent.tool.calls")
                .tag("phase", phase)
                .register(meterRegistry).increment(toolCalls);

        // Persist to SQLite
        longTermMemory.logTokenUsage(sessionId, phase, model,
                inputTokens, outputTokens, totalCost, durationMs, toolCalls);

        TokenUsageReport report = new TokenUsageReport(
                sessionId, phase, model, inputTokens, outputTokens,
                inputTokens + outputTokens, inputCost, outputCost, totalCost,
                durationMs, toolCalls, Instant.now());

        log.info("""
                [TOKEN-TELEMETRY] ══════════════════════════════════════
                [TOKEN-TELEMETRY]  Session  : {}
                [TOKEN-TELEMETRY]  Phase    : {}
                [TOKEN-TELEMETRY]  Model    : {}
                [TOKEN-TELEMETRY]  Input    : {} tokens (${})\
                
                [TOKEN-TELEMETRY]  Output   : {} tokens (${})\
                
                [TOKEN-TELEMETRY]  Total    : {} tokens | ${}\
                
                [TOKEN-TELEMETRY]  Duration : {}ms | Tool calls: {}
                [TOKEN-TELEMETRY] ══════════════════════════════════════""",
                sessionId, phase, model,
                inputTokens, String.format("%.6f", inputCost),
                outputTokens, String.format("%.6f", outputCost),
                inputTokens + outputTokens, String.format("%.6f", totalCost),
                durationMs, toolCalls);

        checkBudgetAlert();
        return report;
    }

    @SuppressWarnings("unchecked")
    private void checkBudgetAlert() {
        try {
            Map<String, Object> summary = longTermMemory.getUsageSummary();
            Map<String, Object> last24h = (Map<String, Object>) summary.getOrDefault("last24h", Map.of());
            double spent = ((Number) last24h.getOrDefault("costUsd", 0.0)).doubleValue();

            if (spent >= dailyBudgetUsd) {
                log.warn("[BUDGET-ALERT] DAILY BUDGET EXCEEDED! Spent ${} / ${} limit",
                        String.format("%.2f", spent), String.format("%.2f", dailyBudgetUsd));
            } else if (spent >= dailyBudgetUsd * alertThreshold) {
                log.warn("[BUDGET-ALERT] Approaching daily budget: ${} / ${} ({}%)",
                        String.format("%.2f", spent), String.format("%.2f", dailyBudgetUsd),
                        String.format("%.0f", (spent / dailyBudgetUsd) * 100));
            }
        } catch (Exception e) {
            log.debug("[BUDGET-ALERT] Could not check budget: {}", e.getMessage());
        }
    }

    public Map<String, Object> getCurrentTotals() {
        return Map.of(
                "totalInputTokens", totalInputTokens.get(),
                "totalOutputTokens", totalOutputTokens.get(),
                "totalTokens", totalInputTokens.get() + totalOutputTokens.get(),
                "totalCalls", totalCalls.get(),
                "estimatedCostUsd", ((totalInputTokens.get() / 1_000_000.0) * inputCostPerMillion)
                        + ((totalOutputTokens.get() / 1_000_000.0) * outputCostPerMillion)
        );
    }
}

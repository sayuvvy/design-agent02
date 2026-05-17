package com.agent.telemetry;

import java.time.Instant;

/**
 * Immutable report for a single LLM call — token counts, cost, duration.
 */
public record TokenUsageReport(
        String sessionId,
        String phase,
        String model,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        double inputCostUsd,
        double outputCostUsd,
        double totalCostUsd,
        long durationMs,
        int toolCalls,
        Instant timestamp
) {}

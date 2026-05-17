package com.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-term memory service — manages per-session memory with TTL-based eviction.
 * Enables cross-phase context sharing within a single pipeline run.
 */
@Service
public class SessionMemoryService {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryService.class);
    private final Map<String, SessionMemory> sessions = new ConcurrentHashMap<>();

    @Value("${agent.memory.short-term.ttl-minutes:30}")
    private long ttlMinutes;

    public SessionMemory getOrCreateSession(String sessionId, String repoPath) {
        return sessions.computeIfAbsent(sessionId, id -> {
            SessionMemory memory = new SessionMemory(id);
            memory.setRepoPath(repoPath);
            log.info("[SHORT-TERM-MEMORY] Created session {} for repo {}", id, repoPath);
            return memory;
        });
    }

    public Optional<SessionMemory> getSession(String sessionId) {
        SessionMemory memory = sessions.get(sessionId);
        if (memory != null && memory.isExpired(ttlMinutes)) {
            log.info("[SHORT-TERM-MEMORY] Session {} expired, removing", sessionId);
            sessions.remove(sessionId);
            return Optional.empty();
        }
        return Optional.ofNullable(memory);
    }

    public void storePhaseOutput(String sessionId, String phase, String output) {
        getSession(sessionId).ifPresent(m -> {
            m.addPhaseOutput(phase, output);
            log.info("[SHORT-TERM-MEMORY] Stored {} output for session {} ({} chars)",
                    phase, sessionId, output.length());
        });
    }

    /**
     * Retrieve the FULL (un-truncated) output for a specific phase.
     * Returns null if session not found or phase output not yet stored.
     */
    public String getPhaseOutput(String sessionId, String phase) {
        return getSession(sessionId)
                .map(m -> m.getPhaseOutputs().get(phase))
                .orElse(null);
    }

    public String getPreviousContext(String sessionId) {
        return getSession(sessionId)
                .map(SessionMemory::buildContextFromPreviousPhases)
                .orElse("");
    }

    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
        log.debug("[SHORT-TERM-MEMORY] Removed session {}", sessionId);
    }

    public int getActiveSessionCount() {
        return sessions.size();
    }

    public Map<String, SessionMemory> getAllSessions() {
        return Map.copyOf(sessions);
    }

    /** Evict expired sessions every 5 minutes. */
    @Scheduled(fixedRate = 300_000)
    public void evictExpiredSessions() {
        int before = sessions.size();
        sessions.entrySet().removeIf(e -> e.getValue().isExpired(ttlMinutes));
        int evicted = before - sessions.size();
        if (evicted > 0) {
            log.info("[SHORT-TERM-MEMORY] Evicted {} expired sessions", evicted);
        }
    }
}

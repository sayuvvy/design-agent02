package com.agent.cache;

import com.agent.model.AgentPhase;
import com.agent.model.AgentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches analysis results keyed by a hash of the repo path + last-modified
 * timestamp of source files. Avoids redundant LLM calls for unchanged codebases.
 *
 * Cache is in-memory with configurable TTL (default 1 hour).
 * Set agent.cache.enabled=false to disable.
 */
@Service
public class AnalysisCacheService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisCacheService.class);

    private final boolean enabled;
    private final Duration ttl;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public AnalysisCacheService(
            @Value("${agent.cache.enabled:true}") boolean enabled,
            @Value("${agent.cache.ttl-minutes:60}") int ttlMinutes) {
        this.enabled = enabled;
        this.ttl = Duration.ofMinutes(ttlMinutes);
        log.info("Analysis cache: enabled={}, ttl={}min", enabled, ttlMinutes);
    }

    /**
     * Look up a cached response for the given repo + phase + issues combination.
     * Returns null if not cached, expired, or caching is disabled.
     */
    public AgentResponse get(String repoPath, AgentPhase phase, String issues) {
        if (!enabled) return null;

        String key = buildKey(repoPath, phase, issues);
        CacheEntry entry = cache.get(key);

        if (entry == null) {
            log.debug("Cache MISS: {} phase={}", repoPath, phase);
            return null;
        }

        if (entry.isExpired(ttl)) {
            log.debug("Cache EXPIRED: {} phase={}", repoPath, phase);
            cache.remove(key);
            return null;
        }

        // Check if source files changed since cache was written
        String currentHash = computeRepoHash(repoPath);
        if (!entry.repoHash.equals(currentHash)) {
            log.debug("Cache STALE (repo changed): {} phase={}", repoPath, phase);
            cache.remove(key);
            return null;
        }

        log.info("Cache HIT: {} phase={} (saved an LLM call!)", repoPath, phase);
        return entry.response;
    }

    /**
     * Store a response in the cache.
     */
    public void put(String repoPath, AgentPhase phase, String issues, AgentResponse response) {
        if (!enabled) return;

        String key = buildKey(repoPath, phase, issues);
        String repoHash = computeRepoHash(repoPath);
        cache.put(key, new CacheEntry(response, repoHash, Instant.now()));
        log.info("Cache PUT: {} phase={} hash={}", repoPath, phase, repoHash.substring(0, 8));
    }

    /**
     * Clear all cached entries.
     */
    public void clear() {
        int size = cache.size();
        cache.clear();
        log.info("Cache cleared: {} entries removed", size);
    }

    /**
     * Returns cache statistics.
     */
    public String stats() {
        long total = cache.size();
        long expired = cache.values().stream().filter(e -> e.isExpired(ttl)).count();
        return "Cache entries: %d (expired: %d, ttl: %dmin)".formatted(total, expired, ttl.toMinutes());
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private String buildKey(String repoPath, AgentPhase phase, String issues) {
        String raw = repoPath + "|" + phase + "|" + (issues != null ? issues : "");
        return sha256(raw);
    }

    /**
     * Computes a lightweight hash of the repo by walking src/ and hashing
     * file names + last-modified times. This detects file additions, deletions,
     * and modifications without reading file contents (fast).
     */
    String computeRepoHash(String repoPath) {
        try {
            Path srcPath = Path.of(repoPath, "src");
            if (!Files.isDirectory(srcPath)) {
                srcPath = Path.of(repoPath);
            }

            StringBuilder sb = new StringBuilder();
            Path root = srcPath;
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    // Only hash source files, skip compiled/binary
                    if (name.endsWith(".java") || name.endsWith(".yml") || name.endsWith(".yaml")
                            || name.endsWith(".xml") || name.endsWith(".properties")
                            || name.endsWith(".json") || name.endsWith(".md")) {
                        sb.append(root.relativize(file))
                          .append(":")
                          .append(attrs.lastModifiedTime().toMillis())
                          .append("|");
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });

            return sha256(sb.toString());
        } catch (IOException e) {
            log.warn("Could not compute repo hash for {}: {}", repoPath, e.getMessage());
            return "unknown-" + System.currentTimeMillis();
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }

    private record CacheEntry(AgentResponse response, String repoHash, Instant createdAt) {
        boolean isExpired(Duration ttl) {
            return Instant.now().isAfter(createdAt.plus(ttl));
        }
    }
}

package com.agent.service;

import com.agent.model.AgentRequest;
import com.agent.model.AgentResponse;
import com.agent.model.AgentStatus;
import com.agent.orchestrator.DesignOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs the full design pipeline asynchronously so the HTTP response returns
 * immediately with a jobId. Callers poll GET /api/design/status/{jobId}.
 *
 * Jobs are held in a ConcurrentHashMap — they survive for the lifetime of the
 * JVM process. On Render free tier the process sleeps after 15 min of
 * inactivity, so long-running jobs should be polled within that window.
 */
@Service
public class DesignJobService {

    private static final Logger log = LoggerFactory.getLogger(DesignJobService.class);

    private final DesignOrchestrator orchestrator;
    private final ConcurrentHashMap<String, AgentResponse> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "design-job-worker");
        t.setDaemon(true);
        return t;
    });

    public DesignJobService(DesignOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * Submits a full pipeline run. Returns the jobId immediately.
     * The pipeline runs on a background thread; poll getStatus() for completion.
     */
    public String submit(AgentRequest request) {
        String jobId = UUID.randomUUID().toString();
        jobs.put(jobId, inProgress(jobId, request));
        log.info("[JOB] submitted jobId={} repoUrl={} repoPath={}", jobId,
                request.repoUrl(), request.repoPath());

        executor.submit(() -> {
            try {
                AgentResponse result = orchestrator.execute(request);
                jobs.put(jobId, result);
                log.info("[JOB] completed jobId={} status={}", jobId, result.status());
            } catch (Exception ex) {
                log.error("[JOB] failed jobId={}", jobId, ex);
                jobs.put(jobId, AgentResponse.failed(jobId, null, ex.getMessage()));
            }
        });

        return jobId;
    }

    /** Returns empty when jobId is unknown. */
    public Optional<AgentResponse> getStatus(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    private AgentResponse inProgress(String jobId, AgentRequest request) {
        return new AgentResponse(jobId, request.phase(), AgentStatus.IN_PROGRESS,
                "Pipeline running…", null, null, null, Instant.now());
    }
}

package com.agent.controller;

import com.agent.exception.AgentRequestValidator;
import com.agent.model.AgentPhase;
import com.agent.model.AgentRequest;
import com.agent.model.AgentResponse;
import com.agent.model.AgentStatus;
import com.agent.model.JobStatusResponse;
import com.agent.orchestrator.DesignOrchestrator;
import com.agent.service.DesignJobService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for the design pipeline.
 *
 * POST /api/design/run              — async full pipeline; returns jobId immediately
 * GET  /api/design/status/{jobId}   — poll for IN_PROGRESS / SUCCESS / FAILED (JSON)
 * GET  /api/design/result/{jobId}   — download design.md as plain text when SUCCESS
 * POST /api/design/fetch-jira       — FETCH_JIRA phase only
 * POST /api/design/analyze          — ANALYZE phase only
 * POST /api/design/cross-ref        — CROSS_REF phase only
 * POST /api/design/design           — DESIGN phase only
 * POST /api/design/publish          — PUBLISH phase only
 */
@RestController
@RequestMapping("/api/design")
public class DesignController {

    private final DesignOrchestrator orchestrator;
    private final AgentRequestValidator validator;
    private final DesignJobService jobService;

    public DesignController(DesignOrchestrator orchestrator,
                            AgentRequestValidator validator,
                            DesignJobService jobService) {
        this.orchestrator = orchestrator;
        this.validator = validator;
        this.jobService = jobService;
    }

    /**
     * Submits a full pipeline run asynchronously and returns a jobId immediately.
     * Poll GET /api/design/status/{jobId} until status is SUCCESS or FAILED.
     *
     * Example body:
     * {
     *   "repoUrl":  "https://github.com/owner/myapp",   // GitHub URL (Render)
     *   "repoPath": "/workspace/my-app",                // or local path
     *   "issues":   "Epic: ...\nStory: ...\nBug: ...",  // when Jira not configured
     *   "complexity": "MEDIUM"
     * }
     */
    @PostMapping("/run")
    public ResponseEntity<JobStatusResponse> run(@RequestBody AgentRequest request) {
        validator.validate(request);
        String jobId = jobService.submit(request);
        return ResponseEntity.accepted().body(JobStatusResponse.accepted(jobId));
    }

    /**
     * Poll for job completion.
     * Returns 200 with status=IN_PROGRESS while running,
     * 200 with status=SUCCESS/FAILED and the full result when done,
     * 404 when the jobId is unknown.
     */
    @GetMapping("/status/{jobId}")
    public ResponseEntity<JobStatusResponse> status(@PathVariable String jobId) {
        return jobService.getStatus(jobId)
                .map(result -> ResponseEntity.ok(JobStatusResponse.done(jobId, result)))
                .orElse(ResponseEntity.status(404).body(JobStatusResponse.notFound(jobId)));
    }

    /**
     * Returns the finished design document as plain text (text/markdown).
     * Use this after status returns SUCCESS — readable directly in Postman or a browser.
     *
     * 200 text/markdown  — design content
     * 202 Accepted       — job still running
     * 404                — jobId unknown
     * 500                — job failed (body contains the error message)
     */
    @GetMapping("/result/{jobId}")
    public ResponseEntity<String> result(@PathVariable String jobId) {
        return jobService.getStatus(jobId).map(response -> {
            if (response.status() == AgentStatus.IN_PROGRESS) {
                return ResponseEntity.accepted()
                        .<String>body("Job still running. Try again shortly.");
            }
            if (response.status() == AgentStatus.FAILED) {
                return ResponseEntity.internalServerError()
                        .<String>body("Job failed: " + response.error());
            }
            // SUCCESS — extract design content
            String content = null;
            if (response.designDocument() != null) {
                content = response.designDocument().designDocContent();
            }
            if (content == null || content.isBlank()) {
                content = response.output();
            }
            if (content == null || content.isBlank()) {
                return ResponseEntity.noContent().<String>build();
            }
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"design-" + jobId.substring(0, 8) + ".md\"")
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(content);
        }).orElse(ResponseEntity.status(404).<String>body("Job not found: " + jobId));
    }

    @PostMapping("/fetch-jira")
    public ResponseEntity<AgentResponse> fetchJira(@RequestBody AgentRequest request) {
        AgentRequest phased = withPhase(request, AgentPhase.FETCH_JIRA);
        validator.validate(phased);
        return toHttp(orchestrator.execute(phased));
    }

    @PostMapping("/analyze")
    public ResponseEntity<AgentResponse> analyze(@RequestBody AgentRequest request) {
        AgentRequest phased = withPhase(request, AgentPhase.ANALYZE);
        validator.validate(phased);
        return toHttp(orchestrator.execute(phased));
    }

    @PostMapping("/cross-ref")
    public ResponseEntity<AgentResponse> crossRef(@RequestBody AgentRequest request) {
        AgentRequest phased = withPhase(request, AgentPhase.CROSS_REF);
        validator.validate(phased);
        return toHttp(orchestrator.execute(phased));
    }

    @PostMapping("/design")
    public ResponseEntity<AgentResponse> design(@RequestBody AgentRequest request) {
        AgentRequest phased = withPhase(request, AgentPhase.DESIGN);
        validator.validate(phased);
        return toHttp(orchestrator.execute(phased));
    }

    @PostMapping("/publish")
    public ResponseEntity<AgentResponse> publish(@RequestBody AgentRequest request) {
        AgentRequest phased = withPhase(request, AgentPhase.PUBLISH);
        validator.validate(phased);
        return toHttp(orchestrator.execute(phased));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private AgentRequest withPhase(AgentRequest req, AgentPhase phase) {
        return new AgentRequest(req.sessionId(), phase, req.repoPath(),
                req.repoUrl(), req.githubToken(),
                req.jiraProjectKey(), req.jiraSprintId(), req.jiraIssueKeys(), req.issues(),
                req.outputDir(), req.context(), req.complexity());
    }

    private ResponseEntity<AgentResponse> toHttp(AgentResponse response) {
        return switch (response.status()) {
            case SUCCESS     -> ResponseEntity.ok(response);
            case IN_PROGRESS -> ResponseEntity.accepted().body(response);
            case FAILED      -> ResponseEntity.internalServerError().body(response);
        };
    }
}

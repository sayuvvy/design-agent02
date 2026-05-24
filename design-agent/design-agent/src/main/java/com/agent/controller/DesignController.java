package com.agent.controller;

import com.agent.exception.AgentRequestValidator;
import com.agent.model.AgentPhase;
import com.agent.model.AgentRequest;
import com.agent.model.AgentResponse;
import com.agent.orchestrator.DesignOrchestrator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for the design pipeline.
 *
 * POST /api/design/run          — run a specific phase or full pipeline
 * POST /api/design/fetch-jira   — FETCH_JIRA phase only
 * POST /api/design/analyze      — ANALYZE phase only
 * POST /api/design/cross-ref    — CROSS_REF phase only
 * POST /api/design/design       — DESIGN phase only
 * POST /api/design/publish      — PUBLISH phase only
 */
@RestController
@RequestMapping("/api/design")
public class DesignController {

    private final DesignOrchestrator orchestrator;
    private final AgentRequestValidator validator;

    public DesignController(DesignOrchestrator orchestrator,
                            AgentRequestValidator validator) {
        this.orchestrator = orchestrator;
        this.validator = validator;
    }

    /**
     * Generic endpoint — phase in body controls what runs.
     * Omit phase or set to FULL to run the entire pipeline.
     *
     * Example:
     * {
     *   "repoPath": "/workspace/my-app",
     *   "jiraProjectKey": "MYAPP",
     *   "jiraSprintId": "Sprint 42",       // optional
     *   "jiraIssueKeys": ["MYAPP-101"],    // optional, narrows scope
     *   "context": "Use hexagonal arch"    // optional
     * }
     */
    @PostMapping("/run")
    public ResponseEntity<AgentResponse> run(@RequestBody AgentRequest request) {
        validator.validate(request);
        return toHttp(orchestrator.execute(request));
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

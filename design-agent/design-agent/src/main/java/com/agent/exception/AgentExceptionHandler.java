package com.agent.exception;

import com.agent.model.AgentPhase;
import com.agent.model.AgentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.context.request.WebRequest;

import java.util.concurrent.TimeoutException;

@RestControllerAdvice
public class AgentExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentExceptionHandler.class);

    /** Anthropic API / HTTP 4xx errors */
    @ExceptionHandler(HttpClientErrorException.class)
    public ResponseEntity<AgentResponse> handleAiException(
            HttpClientErrorException ex, WebRequest request) {
        log.error("API error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(AgentResponse.failed(null, AgentPhase.FULL,
                        "AI provider error: " + ex.getMessage()));
    }

    /** Missing required configuration (e.g. Jira MCP not configured) */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<AgentResponse> handleMissingConfig(
            IllegalStateException ex, WebRequest request) {
        log.error("Configuration error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(AgentResponse.failed(null, AgentPhase.FULL, ex.getMessage()));
    }

    /** Missing or invalid request fields */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AgentResponse> handleBadRequest(
            IllegalArgumentException ex, WebRequest request) {
        log.warn("Bad request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(AgentResponse.failed(null, AgentPhase.FULL, ex.getMessage()));
    }

    /** Timeout on long-running agent phases */
    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<AgentResponse> handleTimeout(
            TimeoutException ex, WebRequest request) {
        log.error("Agent timed out: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(AgentResponse.failed(null, AgentPhase.FULL,
                        "Agent timed out: " + ex.getMessage()));
    }

    /** Catch-all */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<AgentResponse> handleGeneral(
            Exception ex, WebRequest request) {
        log.error("Unhandled agent error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(AgentResponse.failed(null, AgentPhase.FULL,
                        "Internal error: " + ex.getMessage()));
    }
}

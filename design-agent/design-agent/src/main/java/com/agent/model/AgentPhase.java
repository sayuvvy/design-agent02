package com.agent.model;

public enum AgentPhase {

    /** Pull epics, stories and bugs from Jira Cloud. */
    FETCH_JIRA,

    /** Read the local codebase and map current architecture and patterns. */
    ANALYZE,

    /** Cross-reference Jira items with the codebase — what story touches what. */
    CROSS_REF,

    /** Synthesize a new design addressing epics and resolving bugs. */
    DESIGN,

    /** Write the design document and post updates back to Jira tickets. */
    PUBLISH,

    /** Run all phases in sequence: FETCH_JIRA → ANALYZE → CROSS_REF → DESIGN → PUBLISH */
    FULL
}

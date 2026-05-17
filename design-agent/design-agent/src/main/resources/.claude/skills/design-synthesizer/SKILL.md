---
name: design-synthesizer
description: >
  Synthesize a comprehensive design document from Jira requirements and codebase analysis.
  Use during the DESIGN phase to produce an actionable architecture proposal.
---

# Design synthesizer skill

## Design principles to apply
1. **Single Responsibility** — each component does one thing
2. **Open/Closed** — open for extension, closed for modification
3. **Dependency Inversion** — depend on abstractions, not concretions
4. **Domain-Driven Design** — model the business domain explicitly
5. **Event-Driven where appropriate** — decouple via events for async flows

## Design document structure
Always produce the design in this exact structure:

```markdown
# Design Document: {Project} — {Date}

## Executive Summary
One paragraph: what is changing, why, and expected outcome.

## Current State Assessment
### Architecture overview
### Key strengths
### Key weaknesses / tech debt

## Proposed Architecture
### Architecture style chosen (and rationale)
### High-level component diagram (ASCII or described)
### Key architectural decisions (ADRs)

## Component Design
For each new or significantly changed component:
### {ComponentName}
- **Responsibility**: one sentence
- **Interface**: key public methods/endpoints
- **Dependencies**: what it depends on
- **Changes from current**: what's different

## Epic Implementation Plan
For each Epic:
### {EPIC-KEY}: {Epic Summary}
- **Approach**: how to implement
- **Components affected**: list
- **Sequence**: numbered steps
- **Estimated complexity**: S/M/L/XL

## Bug Fix Plan
For each Bug:
### {BUG-KEY}: {Bug Summary}
- **Root cause**: identified location and reason
- **Fix**: specific change needed
- **Risk**: low/medium/high

## Migration Strategy
### Phase 1: {description}
### Phase 2: {description}

## Risks and Mitigations
| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|

## Open Questions
- [ ] {question requiring stakeholder input}
```

## Quality checklist before finalising
- [ ] Every Epic is addressed
- [ ] Every Bug has a fix plan
- [ ] No orphaned components (everything connects)
- [ ] Migration is incremental, not big-bang
- [ ] Risks are realistic, not boilerplate

---
name: jira-publisher
description: >
  Publish design outputs back to Jira Cloud tickets as structured comments.
  Use during the PUBLISH phase to post design decisions to epics and bugs.
---

# Jira publisher skill

## Comment format for Epics
Post one comment per Epic with this structure:

```
*Design Approach — [EPIC-KEY]*

*Summary*: {one sentence on how this epic is addressed in the design}

*Components affected*:
- {ComponentName}: {what changes}

*Implementation sequence*:
1. {step}
2. {step}

*Estimated complexity*: {S/M/L/XL}

_Posted by Design Agent — see full design doc at: {outputDir}/design.md_
```

## Comment format for Bugs
Post one comment per Bug with this structure:

```
*Design Fix — [BUG-KEY]*

*Root cause*: {identified location and reason}

*Proposed fix*: {specific change}

*Risk*: {low/medium/high}

_Posted by Design Agent — see full design doc at: {outputDir}/design.md_
```

## Rules
- Post comments only — do NOT change ticket status, assignee, or priority
- Keep each comment under 200 words
- Always include the link to the design doc
- If a ticket already has a Design Agent comment, update it rather than adding a new one
- Post epics first, then bugs
- Track every key you post to (for the summary report)

# Roadmap

## Product vision

Build a user-side autonomous support agent that handles customer support interactions across chat, forms, phone trees, and ticket systems while preserving user control and auditability.

## Core requirements

- User submits issue once.
- Agent handles channel actions (chat, selections, attachments, follow-ups).
- User can review timeline and intervene.
- Sensitive actions are policy-gated.
- Resolution outcomes are measurable.

## Target architecture (production)

- `ingestion-api`: case intake and user auth.
- `case-engine`: state machine and event sourcing.
- `policy-service`: safety rules + approvals.
- `planner`: LLM planning and tool selection.
- `channel-adapters`: web, email, ticket APIs, IVR.
- `evidence-store`: encrypted artifact storage.
- `ops-console`: human approval and replay UI.
- `metrics-service`: resolution analytics and quality scoring.

## Rollout sequence

1. Harden current MVP with database and queue.
2. Ship Android companion app (accessibility + media picker + explicit consent UX).
3. Add one real provider adapter (e.g., Amazon or Flipkart complaint flow).
4. Add approval UI and operator workflows.
5. Introduce LLM planner behind feature flag.
6. Expand to multi-channel support and optimize policy coverage.

## Open-source maturity track

1. Lock API contracts and version endpoints.
2. Add adapter plugin SDK for community integrations.
3. Add CI checks (lint, tests, build, contract tests).
4. Publish security hardening guide and threat model.

# 0001. Record architecture decisions as ADRs

- **Status:** Accepted
- **Date:** 2026-11-16
- **Tasks:** TASKS.md M1.12

## Context

ReconFlow's design has already been shaped by decisions made in SQL (V1–V3), in the capacity plan (docs/scalability.md) and in the phase plans. Six questions were left open in the README. Six months from now, someone (often the same person) will look at a `job` table and ask "why not Kafka?", or at a `409` and ask "why not `412`?". If the reasons aren't written down while they're fresh, the likely outcome is that a decision gets undone without anyone knowing what it protected against.

Design docs (requirements, architecture, API design) describe the system as it *is*. They're edited as the system changes, so they lose the history of *why* and *what else was considered*.

## Decision

We will record each significant architectural decision as an Architecture Decision Record in `docs/adr/`, one Markdown file per decision, numbered `NNNN-short-title.md`, using the template in [0000-template.md](0000-template.md): context, decision, consequences, alternatives considered.

- A decision is "significant" if it is expensive to reverse, or if a reasonable engineer would have chosen differently.
- Every ADR lists at least one rejected alternative. An ADR without alternatives is a description, not a decision.
- Once **Accepted**, an ADR is immutable. Changing our minds means a new ADR that **supersedes** the old one; the old one's status becomes "Superseded by NNNN" and nothing else in it changes.
- Design docs link to the ADR instead of repeating its reasoning.

## Consequences

- New contributors (and interviewers) can read why the system looks the way it does, including what was rejected and under what conditions we'd switch.
- Reversing a decision is a visible act with its own record, not a silent drift.
- Writing an ADR takes an hour. That cost is small next to re-arguing a decision, and it forces the alternatives to be thought through before the code is written.
- The ADR folder grows. That's fine: superseded ADRs are history, not clutter.

## Alternatives considered

- **Wiki pages.** Live away from the code, aren't reviewed in pull requests, and drift out of date. Rejected.
- **Comments in code and migrations.** Good for local reasoning (V1's header comment is an example), but a decision like "modular monolith" or "async imports" has no single line of code to attach to. Kept as a complement, not a replacement.
- **Nothing; the design docs are enough.** Design docs are rewritten as the system changes, so they lose the alternatives and the history. Rejected.

# General issue template

Use this structure for local or GitHub issues. The mandatory sections make the
task understandable to someone who did not author the issue and cannot infer
product intent from the code. If a mandatory answer is unknown, write
`Unknown—blocked` and stop at `needs-input`; do not guess.

## Mandatory sections

### Title

State the outcome or problem plainly. Avoid implementation-only titles such as
"Refactor service layer" unless that is the actual accepted outcome.

### Intent

Why does this matter? Name the user, operator, maintainer, or system outcome,
why it matters now, and what problem or opportunity motivates the work.

### Expectation

What should be observably true after the work? Describe behavior at the caller,
user, operator, or integration boundary, including important success and failure
semantics. Do not describe only the files to edit.

### Acceptance criteria

Use finite, testable statements. Each criterion must have an observable pass or
fail signal and should cover the important success, failure, edge, security,
compatibility, and recovery behavior that applies.

### Context that code cannot infer

Record the facts a new implementer or reviewer cannot safely reconstruct:

- target users, audience, actors, and affected workflows;
- business rules, policy, authority, privacy, or compliance constraints;
- why now, priority rationale, and the cost of not doing the work;
- compatibility expectations, supported environments, and operational limits;
- user or stakeholder decisions already made and assumptions still unconfirmed.

### Scope

State what is included, the affected components or interfaces, ownership, and
the expected delivery boundary. Name the issue type, priority, labels, project,
milestone, assignee, and target horizon when known; do not invent metadata.

### Non-goals

State what this issue will not change. Include adjacent improvements that might
look attractive but require separate authorization or planning.

### Dependencies and open decisions

List blocking or blocked-by issues, external dependencies, required approvals,
assumptions, and unresolved decisions. Mark the owner or explicitly state that
the issue is unassigned.

### Verification

Define how each acceptance criterion will be tested or observed, at the real
boundary where possible. Include expected test layers, fixtures or environments,
security checks, rollout or migration observations, and the failure signal.

## Optional sections

Add only when they reduce ambiguity for this issue:

- **Background and research** — sources, prior art, measurements, and
  contradictions.
- **Proposed approach** — a reversible implementation direction, clearly marked
  as a proposal rather than a hidden requirement.
- **UX, API, data, security, or privacy details** — contracts and examples that
  matter to the affected boundary.
- **Examples or scenarios** — representative inputs, outputs, user journeys, or
  counterexamples.
- **Rollout, migration, rollback, and observability** — release sequencing,
  feature flags, dashboards, alerts, recovery, or data transition details.
- **Alternatives considered** — rejected options and the reason they were not
  selected.
- **Documentation and release notes** — user-facing or maintainer-facing docs
  that must change with the implementation.
- **Estimates and target dates** — only when supplied or approved; estimates are
  not acceptance criteria.

## Pull-request contract

Every PR must include these sections in its body, even when it links an issue:

1. **Intent** — why this change exists and who benefits.
2. **Expectation** — what is now observably true.
3. **Acceptance criteria** — each criterion with its evidence or current status.
4. **Non-code context** — relevant users, rules, constraints, compatibility,
   and why-now context that a diff cannot establish.
5. **Scope and non-goals** — what changed and what deliberately did not.
6. **Verification and risk** — tests, security checks, rollout or rollback notes,
   residual risks, and unsupported checks.

Use `None`, `Not applicable`, or `Unknown—blocked` explicitly. Do not make a
reviewer reconstruct intent or product expectations from the diff.

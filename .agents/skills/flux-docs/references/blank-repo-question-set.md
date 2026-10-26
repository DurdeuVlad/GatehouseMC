# Flux Docs progressive disclosure questions

Ask only the smallest set that unlocks the next document. Record unknowns instead of guessing.

## First classification: operating mode

1. Is this owned by one developer or by multiple contributors/teams?
2. Is it private, public, or intended to become open source?
3. If it is public but solo-owned, should outside users contribute, request support, or only consume releases?

## First pass: establish `Business.md`

1. What is being built, and what problem makes it worth building?
2. Who experiences the problem, who uses the solution, and who decides whether it works?
3. What should the user or system be able to do from start to finish?
4. What value or outcome should result, and how will success be recognized or measured?
5. What is explicitly out of scope for the first useful version?
6. What constraints already exist: platform, time, budget, regulation, privacy, reliability, or compatibility?

## Second pass: establish `Decision.md`

1. What choices are already settled, and who has authority over them?
2. Which alternatives were considered or are still open?
3. What tradeoffs, assumptions, or consequences must future work preserve?
4. What would cause a decision to be revisited?

## Third pass: establish `Milestones.md`

1. What meaningful outcome should the first useful release or project checkpoint achieve?
2. What is explicitly inside and outside that checkpoint?
3. What dependencies, risks, or decisions could prevent it?
4. What evidence will prove the outcome is complete, and who owns the judgment?
5. What target horizon matters, if any, and what can be revised without changing the goal?

## Conditional pass: single-developer projects

Ask only when the project is solo-owned:

- What constraints govern your time, attention, budget, or technical choices?
- What context would you need to resume the project after a long pause or hand it to someone else?
- Which decisions require your explicit approval, and which can the agent make within the documented constraints?

## Conditional pass: collaborative projects

Ask only when multiple contributors, teams, or public contribution are in scope:

- Who owns product direction, technical decisions, review, release, and operations?
- Who can contribute or approve changes, and how are disagreements escalated?
- What communication, support, contribution, and handoff expectations must be explicit?

## Conditional pass: interfaces, OSS, and system risks

Ask only if the project signals the concern:

- Public or multi-contributor: Who can contribute, review, release, and resolve disputes? What conduct, support, and security expectations apply?
- API or integration boundary: Who calls it, what must remain compatible, and what are the success, error, auth, and versioning rules?
- Persistent data: What is stored, who owns it, how long is it retained, and what must be private or recoverable?
- Deployment or operations: Where does it run, who operates it, what can fail, and how is failure detected and recovered?
- Security-sensitive behavior: What are the trust boundaries, threats, secrets, and unacceptable outcomes?
- User-facing behavior: What are the key journeys, accessibility needs, and content or interaction rules?

After each answer, update the document map and ask what remains necessary for the next specification or implementation decision. Do not require answers unrelated to the current project risk.

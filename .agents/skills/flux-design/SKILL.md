---
name: flux-design
description: Identify and apply core visual design principles (essential-only design, gestalt grouping, generous spacing, a small design system, and hierarchy) when reviewing or building a website, app screen, or Figma frame. Use when critiquing an existing UI's visual quality or designing a new one; do not use for backend logic, content strategy, copywriting, or accessibility auditing alone.
---

# Flux Design

Apply a small, checkable set of visual design principles to a real UI artifact — a live page, a screenshot, a component, or a Figma frame — and run the same creative loop professional designers use when the task is to originate something new rather than only review.

## Use when

- Reviewing or critiquing the visual design of a website, app screen, or Figma file for concrete improvements.
- Designing a new layout, section, or component and wanting principle-driven decisions instead of guesswork.
- Auditing a design system (spacing scale, type scale, color variables) for consistency.
- Running a full visual-hierarchy and layout pass across an entire multi-screen prototype — every screen, not a cosmetic touch-up of a few.

## Do not use when

- The task is backend logic, data modeling, or content strategy with no visual surface; route elsewhere.
- The user wants a formal accessibility (WCAG) audit; use a dedicated accessibility skill instead — this skill touches contrast and legibility only as a side effect of hierarchy.
- No artifact, screenshot, URL, or Figma reference is available to look at; do not invent an opinion about a design that was never shown.

## Input

- The target: a live URL, screenshot, component/page code, or a Figma file/frame reference.
- Optional: an existing design system (spacing scale, type scale, color tokens), brand constraints, target audience, or reference inspirations the user already likes.
- Whether the task is review (critique what exists) or origination (design something new).

## Actions

1. Read [design-principles.md](references/design-principles.md) and check the artifact against each principle in order: essential-only design, gestalt grouping (similarity/proximity), spacing, design-system tokens, and hierarchy. Note concrete violations with their location (selector, frame name, or coordinates) — never a vague "improve the design" comment.
2. If a design system is missing or inconsistent, propose the smallest one that covers the artifact: a spacing scale (values divisible by 4, converted to rem by dividing by 16), a type scale, and a short list of color variables. Do not introduce a CSS framework or new dependency to do this.
3. For a review task, return a prioritized list of fixes, each tagged with the principle it violates and the specific element affected.
4. For an origination task, follow [creative-process.md](references/creative-process.md): confirm the key functionality first, gather two or three concrete reference inspirations (existing sites, a Figma community file, or a pattern library) before drawing anything, produce an initial pass, then explicitly flag it as a draft that needs outside feedback before being treated as final.
5. When emphasizing an element for hierarchy, prefer the smallest change that works — contrast, then weight, then size — and re-check that de-emphasizing competing elements wasn't skipped.
6. For a full-prototype pass, follow [full-prototype-pass.md](references/full-prototype-pass.md): classify each screen's content into primary/secondary/tertiary before editing it, fix hierarchy, typography scale, whitespace, information density, and grouping per its guidance, then run its final squint/tiny-text/spacing/density/CTA/card/consistency audit before calling any screen done. Cover every screen in the prototype; do not stop after a handful of representative ones, and keep the resulting system consistent across screens rather than redesigning each in isolation.

## Contract contribution

- Goal: make the artifact's design decisions traceable to a specific, checkable principle rather than personal taste.
- System: review before originating; ground every fix or decision in the artifact actually shown, not an assumed brand or audience.
- Constraints: do not invent brand colors, audience research, or usability data that wasn't provided; never present a subjective preference as an objective rule; do not replace real user feedback or usability testing with this skill's judgment alone.
- Evaluation: every finding or decision names the principle it applies, the affected element, and (for a review) a concrete before/after or fix.

## Output

Return a principle-tagged list: for reviews, findings ordered by impact with the violated principle, the element, and the fix; for origination, the design decisions made (spacing values, hierarchy choices, inspiration sources) each tied back to a principle, plus an explicit note that the result is a draft pending outside feedback. For a full-prototype pass, report per screen (which screens were covered, what changed and why, and the final-audit results) — an incomplete sweep must be reported as incomplete, not summarized as done. Never claim a design is "done" without that feedback step or without evidence the target was actually inspected.

## Stop conditions

Stop and ask rather than guess when no visual artifact is available to inspect, when the request is actually about accessibility compliance or content writing, or when applying a principle would require inventing facts about the brand or users that weren't given.

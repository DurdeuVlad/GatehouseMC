# Full prototype visual hierarchy & layout pass

Use this procedure when the task is to go through an entire multi-screen prototype and redesign it for visual hierarchy, spacing, readability, and composition — not a cosmetic cleanup of one screen. Work through every screen; do not stop after a handful of representative ones.

## 1. Audit every screen before editing

For each screen, determine before touching anything:

- What is the user's primary goal on this screen?
- What should they notice first? Second?
- What is supporting/secondary information?
- What is tertiary information or metadata?
- What is the primary action?
- Which elements are currently competing unnecessarily for attention?
- Is too much information being shown at once?

Classify visible content into three tiers and do not give them similar visual weight:

- **Primary** — the screen's main purpose: page title, critical information, main task, key result, or primary action.
- **Secondary** — content supporting the primary task.
- **Tertiary** — metadata, helper text, labels, timestamps, minor actions, technical details, contextual information.

## 2. Establish strong visual hierarchy

Every screen needs an obvious reading order. Within a few seconds a user should understand: where am I, what matters most, and what should I do next.

Create hierarchy through element size, typography, font weight, whitespace, positioning, grouping, container size, alignment, contrast, and information density — not through text color alone. There should be one obvious focal point, then secondary content, then supporting detail. If everything looks equally important, redesign it.

## 3. Fix typography

Don't solve layout problems by shrinking text. Rough scale to aim for:

- Page/display headings: 32–48px where appropriate
- Section headings: 22–28px
- Important component/card titles: 18–20px
- Normal body/UI text: 16px
- Secondary/supporting text: 14–16px
- 12px only for genuinely low-priority metadata, and only when truly necessary

Prefer fewer typography levels with stronger differentiation over many barely-distinct sizes. Line height must stay comfortable and readable.

## 4. Increase whitespace aggressively

The result should not feel compressed. Rough guide (use optical judgment, not exact numbers):

- Major page/section separation: 64–120px
- Large content groups: 32–48px
- Component/card padding: 24–32px
- Related elements: 12–20px
- Very tightly related elements: 8–12px

Never solve a layout problem by reducing font size, padding, gaps, or whitespace. If content doesn't comfortably fit: grow the container or section, lengthen the page, restructure the content, use progressive disclosure, or relocate secondary information — don't compress to fit more above the fold. Empty space does not need to be filled.

## 5. Reduce information density

Don't try to show every available piece of information at once. Remove, collapse, relocate, or visually de-emphasize anything not necessary for the user's immediate task.

Prefer clarity over density, hierarchy over completeness, progressive disclosure over compression. Not every piece of information needs its own card, badge, chip, label, or bordered container — avoid "dashboard soup." Use containers only when grouping genuinely improves comprehension.

## 6. Establish an attention budget

Each screen has limited visual attention. Normally there should be one dominant region, one obvious primary action for the current task, a small number of secondary actions, and visually quiet tertiary controls. If five things are emphasized, nothing is emphasized — reduce competing visual weight.

## 7. Improve grouping

Use proximity to communicate relationships: elements that belong together should visually form a group, and different groups need substantially more space between them than elements share within a group. Don't reach for borders, cards, backgrounds, or dividers when whitespace alone communicates the grouping more elegantly. Avoid nested cards unless the hierarchy genuinely requires them.

## 8. Improve composition

Judge the whole screen, not each component in isolation. Check overall balance, focal point, reading direction, density distribution, whitespace distribution, alignment, visual rhythm, section proportions, and the relationship between content blocks. The screen must work as one composition, not a set of individually pretty pieces.

## 9. Preserve product consistency

Apply these improvements across the entire prototype. Similar screens and components should share typography hierarchy, spacing logic, component proportions, alignment, interaction patterns, button hierarchy, and content density — infer one coherent visual system from the strongest parts of the existing design and apply it consistently rather than redesigning each screen as an unrelated composition. Do not change the product's brand, functionality, content, navigation, or interaction model without separate authorization.

## 10. Final hierarchy audit (run after every screen is redesigned)

Re-review the whole prototype with these checks, per screen:

- **Squint test** — imagine the screen heavily blurred; there should still be an obvious primary focal area, secondary area, and supporting information. If everything reads as equal weight, fix it.
- **Tiny text test** — find text that shrank because the layout was crowded; increase it and restructure the layout instead.
- **Spacing test** — find unrelated groups sitting too close together, or related elements spaced too far apart; correct the spatial relationships.
- **Density test** — find screens trying to communicate too much at once; simplify them.
- **CTA test** — the primary action must be immediately distinguishable from secondary actions.
- **Card test** — question every card, border, badge, chip, divider, and container; remove any that doesn't improve comprehension or hierarchy.
- **Consistency test** — compare screens side by side; equivalent hierarchy levels should feel equivalent throughout the product.

## Critical constraint

This is not a polish pass. Resizing components, substantially increasing whitespace, changing layout structure or content grouping, changing the typography scale, enlarging important elements, simplifying dense sections, removing unnecessary containers, relocating secondary content, increasing page height, changing proportions, and restructuring sections are all explicitly in scope. Preserve functionality and product intent, but redesign the visual composition wherever it serves clarity, hierarchy, readability, or confidence — the goal is not to fit everything in, it's for the product to feel intentional and immediately understandable.

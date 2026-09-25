# Design principles checklist

Five checkable rules for visual design, distilled for repeatable use. Apply them in order — later rules assume the earlier ones are already satisfied.

## 1. Good design is as little design as possible

- Before laying out a screen, name the single key functionality or main selling point. Most screens reduce to a heading, one input, and one action.
- Start the layout from that core, not from the header down. Asking "how wide should this section be" before "what does this screen need to do" wastes effort and produces clutter.
- Cut colors, words, and decorative elements that don't serve the core function. The brain scans for key visual information first; extra elements compete with it.
- Violation signal: a screen with more than one visually competing focal point, or copy/elements that don't map to the stated core function.

## 2. Use similarity and proximity to group elements (Gestalt)

- Group related elements with shared shape, size, color, or spacing (similarity) and physical closeness (proximity). The eye should read the whole layout as a small number of groups before it reads any individual element.
- A design should be scannable as a whole within seconds. If a first glance doesn't reveal the groups, spacing or similarity is missing.
- Violation signal: elements that belong together are spaced identically to elements that don't, or visually unrelated elements share the same visual weight.

## 3. Elements need more spacing than the designer expects

- Start with generous spacing across the whole layout, then remove it incrementally — pulling only elements that belong together closer — rather than starting tight and adding space piecemeal.
- Spacing is context-dependent: a value that works for one component can be wrong for another. Don't design against placeholder/lorem-ipsum content and assume the spacing will hold with real content.
- Violation signal: elements crowd each other, or every gap in the layout uses the same value regardless of relationship.

## 4. Use a small design system instead of one-off values

- Spacing scale: pick values divisible by 4 (e.g. 4, 8, 12, 16, 20, 24, 32, 40). Convert to rem by dividing the pixel value by 16 so the layout respects the user's font-size preference.
- Store spacing, type, and color choices as variables/tokens, not hard-coded values, so they can be tuned globally.
- Type: pick one font and one type scale rather than improvising sizes per component.
- Color: pick one dark and one light neutral for text/background, plus one or two accent colors. There is no principled "psychology of color" shortcut — legibility and restraint matter more than hue choice.
- Line height is inversely proportional to font size: smaller text needs relatively more line height for legibility, and generous line height doubles as vertical spacing between text elements.
- Avoid centered text alignment for paragraphs and small text; it hurts scanability.
- Violation signal: spacing or font sizes that don't come from a documented scale, or color values repeated ad hoc instead of referenced as variables.

## 5. Hierarchy is everything

- Decide what the user looks for first (usually a title or the primary action) and make that element win using the smallest change that works, in this order: contrast, then font weight, then font size. Overusing all three at once usually looks heavy-handed.
- Emphasizing one element often requires de-emphasizing competing ones (reducing their contrast or size), not just boosting the target.
- Not every instance of the same tag (h1, h2, button, etc.) needs identical treatment — hierarchy is contextual to the screen, not the markup.
- After making a change, zoom out and check whether the intended element actually reads first. If it doesn't, adjust before moving on.
- Optional, sparing exceptions to "less is more": shadows or subtle gradients to add depth on a handful of elevated elements, cards for otherwise bland groups, accent color for the one thing that most needs focus. These are deliberate exceptions, not a default — overusing them recreates the clutter rule 1 removed.
- Violation signal: no clear single focal point, or every element fighting for attention at the same visual weight.

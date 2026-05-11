---
title: "Introduce Semantic Color Tokens (Onyx Refined) in styles.css"
labels: ["enhancement", "ui", "ui-overhaul", "phase-1", "design-system", "tokens"]
---

# Introduce Semantic Color Tokens (Onyx Refined) in styles.css

## Motivation

Phase 1 of the UI Design Book §6 migration roadmap. This is the foundation: every later story in the UI overhaul assumes the stylesheet is driven by semantic tokens, not literal hex.

UI Design Book §1.6 calls out the root cause: `daw-app/src/main/resources/com/benesquivelmusic/daw/app/ui/styles.css` is 1,431 lines of literal hex values. The single comment block at lines 9–17 lists the palette by *colour name* ("Green, Red, Purple, Orange, Cyan"), and then literal hex is sprinkled across 50+ rules. Renaming "purple accent" requires a find-and-replace. There is no semantic name like `accent`, `surface-1`, or `danger`, so themes (the Phase 3 work) cannot be added without rewriting everything.

UI Design Book §3.1 specifies the token contract — a role layer (`surface-bg`, `surface-1..3`, `surface-overlay`, `line-soft/strong`, `focus-ring`, `text-hi/text/text-mute/text-on-accent`, `accent`, `accent-soft`, `ok/warn/danger`, `meter-low..clip`) that maps to a *palette* layer. Palette A ("Onyx Refined", §3.1) becomes the default values for every token in this story; Palette B (Studio Slate) and Palette C (Atelier) are introduced by story 277 in Phase 3, proving that swapping palettes never requires touching the structural CSS.

This story does **not** restructure any selectors — it replaces literal hex *in place* with `-fx-…` lookup references. The visible behaviour stays identical until later Phase 1 stories (de-rainbow transport, elevation, etc.) consume the tokens.

## Goals

- Define the full semantic token set from UI Design Book §3.1 as JavaFX looked-up colours on `.root-pane` in `daw-app/src/main/resources/com/benesquivelmusic/daw/app/ui/styles.css`. Use the canonical token names verbatim (`-accent`, `-surface-bg`, `-surface-1`, `-surface-2`, `-surface-3`, `-surface-overlay`, `-line-soft`, `-line-strong`, `-focus-ring`, `-text-hi`, `-text`, `-text-mute`, `-text-on-accent`, `-accent`, `-accent-soft`, `-ok`, `-warn`, `-danger`, `-meter-low`, `-meter-mid`, `-meter-hi`, `-meter-clip`). Map each to its Palette A hex per the table in §3.1.
- Replace every literal hex occurrence in `styles.css` with a token lookup. The header comment block at lines 9–17 is replaced with a *role* table (no hex), with hex moved into the single Palette A definitions block at the top of the file. Add a comment that any future palette overrides the *role* values, never the literal hex.
- Introduce two new font-family tokens at the same time so later stories (story 266) can reuse them: `-font-sans` (`"Inter, 'Segoe UI', system-ui, sans-serif"`) and `-font-mono` (`"'JetBrains Mono', 'IBM Plex Mono', 'Cascadia Code', Consolas, monospace"`).
- Tests:
  - New `TokenValidationTest` (under `daw-app/src/test/java/com/benesquivelmusic/daw/app/ui/`) that loads `styles.css` as a string and asserts **zero** literal hex matches (`Pattern.compile("#[0-9a-fA-F]{3,8}\\b")`) outside the Palette A definitions block delimited by sentinel comments (`/* ── Palette A: Onyx Refined ─ TOKEN VALUES ─ */ … /* ── End Palette A ── */`). The test prints offending line numbers so future literals are caught in code review.
  - Smoke test that constructs a `Scene` from `main-view.fxml`, applies `styles.css`, and asserts the root pane resolves each token to the expected Palette A colour via `node.lookup(".root-pane").getStyle()` or `Region.styleableProperties` lookup.
- No visual regression in the existing screens. Token values for Palette A are chosen so that the rendered colours are *as close as possible* to today's palette, except where §1.1's overload is being intentionally undone (replacing neon purple `#e040fb` with the more muted `accent` `#7C8CFF` is acceptable; that is the point of "Onyx Refined" per §3.1).

## Non-Goals

- Adding additional palettes — Palettes B and C are story 277 (Phase 3).
- Restructuring any selectors or removing any rules — this is a pure substitution pass.
- De-rainbowing the transport bar — that is story 262, which consumes these tokens.
- Switching to a `:root` CSS variable model — JavaFX CSS does not support custom properties the way browser CSS does; the lookup-colour mechanism (`-fx-…` references that resolve up the scene graph) is the supported pattern (see [JavaFX CSS Reference Guide](https://docs.oracle.com/javase/8/javafx/api/javafx/scene/doc-files/cssref.html#typecolor)).
- Touching `daw-app/src/main/resources/com/benesquivelmusic/daw/app/ui/main-view.fxml` style classes.

## Technical Notes

- JavaFX looked-up colours are declared like `-accent: #7C8CFF;` on `.root-pane`, then consumed elsewhere as `-fx-background-color: -accent;`. They cascade like CSS but are resolved at style application time.
- Keep the palette block tightly fenced (`/* ── Palette A: Onyx Refined ─ TOKEN VALUES ─ */` and `/* ── End Palette A ── */`) so the `TokenValidationTest` can scope its hex-detection regex to *only* that block.
- This story is the prerequisite of every later Phase 1 / 2 / 3 story. Builds on: nothing.
- Reference: UI Design Book §1.6 (problem), §3.1 (solution), §7 (veto list — implicit acceptance criteria).

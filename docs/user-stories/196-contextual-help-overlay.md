---
title: "Contextual Help Overlay (Hover Tooltip + F1 Deep-Link to Docs)"
labels: ["enhancement", "ui", "help", "onboarding"]
---

# Contextual Help Overlay (Hover Tooltip + F1 Deep-Link to Docs)

## Motivation

New users opening a DAW face hundreds of controls with no affordance for what each does. Tooltips cover part of this but miss the "why" and the "how does this interact with other controls?" context. Every productivity tool adds `F1`-driven help that opens a specific documentation topic for the currently-focused control. Logic has its "Quick Help" panel, Pro Tools has "Help > About This Command," Reaper has "Show action in menu."

## Goals

- Add `HelpRegistry` in `com.benesquivelmusic.daw.app.ui.help` mapping control IDs → help topic slugs → markdown documentation files in `daw-app/src/main/resources/help/`.
- Every significant UI control exposes a `setHelpTopic(String slug)` method; the global key handler for `F1` reads the topic of the currently-focused (or hovered) control and opens the `HelpOverlay`.
- `HelpOverlay`: a non-modal right-side panel rendering the markdown with syntax-highlighted code, screenshots from `icon-pack`, and links to related topics.
- Search input in the overlay to browse topics; breadcrumb trail showing the path back to the index.
- "Quick Help" toggle (`Shift+F1`): a persistent tooltip area at the bottom of the screen showing the help for the control under the cursor, updating live.
- Docs live in-tree so edits come with code changes; markdown renders via a small bundled renderer.
- Onboarding tour: first-launch flag triggers a guided highlight of the main controls with help text for each.
- Tests: `F1` on a known control opens the expected topic; search finds topics by title and body; missing-topic links fall back to the index, not a broken view.

## Non-Goals

- Video tutorials (static images only).
- AI-powered "ask" interface (a possible follow-on).
- Localization — English only in MVP.

---
title: "Command Palette (Ctrl+K) Searchable Over All DAW Actions"
labels: ["enhancement", "ui", "usability", "keyboard"]
---

# Command Palette (Ctrl+K) Searchable Over All DAW Actions

## Motivation

A modern power-user UI feature popularized by VS Code and every Electron app: press `Ctrl+K` (or `Ctrl+Shift+P`), type the first letters of an action, execute it. For a DAW with hundreds of actions across dozens of menus, this is dramatically faster than navigating menu trees. Ableton has "Command Browser," Cubase's Key Commands search serves this role, Bitwig has "Action" search. For accessibility and new-user discoverability alike, a searchable command palette is a force multiplier.

`DawAction` already exists as an enum of every DAW action; wiring a palette over it is mostly UI.

## Goals

- Add `CommandPaletteView` in `daw-app.ui`: a centered floating panel activated by `Ctrl+K` / `Ctrl+Shift+P` with a search input and a ranked result list.
- Result entries include: label, keyboard shortcut (if any), current enabled state, and a short description. Disabled actions appear greyed with a tooltip explaining why.
- Fuzzy-match ranking (camelCase-aware) — typing "nt" matches "**N**ew **T**rack."
- Execute selected action on Enter; navigate with Up/Down; close on Esc or loss of focus.
- Data sources: every `DawAction` entry + every `@MenuItem`-annotated method + all plugin-discovery entries (stories 079, 108, 112).
- "Recent" section at top shows last 5 executed commands this session for quick re-issue.
- Per-action icon slot (reuse `icon-pack` module) for visual recognition.
- Persist "recent" commands across sessions in `~/.daw/command-palette-recents.json`.
- Tests: fuzzy match correctly ranks expected results; executing an action from the palette produces identical effects to the equivalent menu click.

## Non-Goals

- Scripted-command creation from the palette.
- Non-action search (searching tracks, plugins, markers by name is a separate "global search" story).
- AI-assisted command suggestions.

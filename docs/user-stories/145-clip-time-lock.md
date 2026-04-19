---
title: "Clip-Level Time Lock Preventing Accidental Movement"
labels: ["enhancement", "editing", "safety"]
---

# Clip-Level Time Lock Preventing Accidental Movement

## Motivation

On film-post sessions where every clip has been aligned to picture, an accidental drag on the wrong track can shift a clip by a frame and nobody notices until the QC pass. Every post-production DAW offers clip time-lock: Pro Tools' "Time Lock," Reaper's "Clip: Lock position," Nuendo's lock flag. When a clip is time-locked, horizontal drag, nudge, and ripple leave it alone; only an explicit unlock allows movement.

`AudioClip` has editable position; the work is a flag and enforcement points in every operation that changes position.

## Goals

- Add `locked` boolean to `AudioClip` and `MidiClip` (via shared `Clip` sealed interface).
- Visual indicator: a small lock icon on the clip header and a subtle diagonal-stripe overlay when hovered.
- Operations `moveClip`, `slipClip` (per clip), `ripple`, `nudge`, and `cross-track drag` check `locked` and refuse with a status-bar message when any selected clip is locked.
- Explicit "Lock selected" / "Unlock selected" context-menu actions and shortcuts (`Ctrl+L`, `Ctrl+Shift+L`).
- Lock state is distinct from mute/bypass; locked clips still play, split, trim, and render normally — lock is strictly about timeline position.
- Edit operations that would *delete* a locked clip require an explicit confirmation (once per session, suppressible).
- Persist `locked` via `ProjectSerializer`; false by default.
- Undo: `SetClipLockedAction`.
- Tests: every position-changing operation is blocked on locked clips; explicit "Move anyway" confirmation path works; unlock then move produces the expected result.

## Non-Goals

- Locking non-position attributes (gain, fade, pan).
- Locking an entire track's timeline (a follow-on track-level-lock story).
- Role-based locking (multi-user concern, out of scope).

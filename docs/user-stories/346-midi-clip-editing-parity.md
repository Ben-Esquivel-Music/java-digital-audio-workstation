---
title: "MIDI Clip Editing Parity in the Arrangement"
labels: ["bug", "arrangement", "midi", "editing"]
---

# MIDI Clip Editing Parity in the Arrangement

## Motivation

MIDI clips are second-class citizens of the arrangement. Copy/cut/duplicate/delete read only the audio selection (`ClipEditController.java:96‑104` and siblings — `getSelectedClips()` yields audio `ClipboardEntry` only); nudge resolves audio targets only (`:395‑397`). A pointer press on a MIDI clip selects but never arms a drag (`ClipInteractionController.java:891‑897` — no dragClip is set), and eraser/scissors/glue hit-test with the audio-only `clipAt` (`:256`, used at `:1017/:1040`). Every one of these operations is a *silent no-op* on a MIDI clip — the defect class the book's rejection list names explicitly (§9.8: an operation that declines says why).

The **only** arrangement operation that treats MIDI clips as equals is slip (`ClipEditController.java:207‑246`, which handles both kinds) — proof the selection model and undo layer can carry MIDI; the operations simply never consult them. This is consult-the-selection wiring, not substrate work.

For a studio engineer: a hybrid session is unarrangeable. You cannot move a MIDI part two bars later, cannot copy a verse's MIDI to the chorus, cannot delete a bad take's MIDI clip — every gesture that works on the audio clip beside it silently does nothing, with no explanation.

## Goals

- **The parity matrix (book §5.6) closed**: drag-move, cut/copy/paste/duplicate/delete, split/glue, nudge, eraser, and marquee/group selection all treat MIDI clips with the same observable contract as audio — model change + the same undo-action family + invalidation-ledger publication. The slip implementation is the template.
- **Drag-move** arms on a MIDI clip press and renders ghost + snap through the story-343 drag presenter — no MIDI-specific rendering path.
- **Mixed-kind selections** work: a marquee spanning audio and MIDI clips moves/copies/deletes both kinds as one group, one undo entry.
- **Split/glue on MIDI clips** produce clips whose combined playback content equals the original (split at the click beat, glue rejoins adjacent clips) via the audio-parity hit-test.
- **No silent no-ops**: an operation that legitimately declines (empty selection, invalid target) states why — status hint or disabled reason — never silence.
- **The pencil guard holds across every op**: no operation path can deposit or duplicate a phantom `AudioClip` onto a MIDI track (the pencil *fix* itself is story 342's; this story asserts the guard across the parity surface).
- Weaves **existing story 143 — cross-track range selection**: the RANGE-tool time-selection substrate lands with (or immediately after) this stage, since parity makes mixed-kind selections meaningful and Stage 3 (story 341) made over-lane marquees reachable.

## Goals — Tests

- **Parity sweep** (the book's §5.6 bar): a test enumerating the arrangement clip operations runs each against a MIDI clip and asserts the same observable contract as audio — model change, undo entry, ledger publication. Today every operation except slip fails this sweep as a silent no-op. A no-op without a published status reason fails the test.
- **Drag-move test**: pressing a MIDI clip arms a drag; the presenter renders a ghost at the snapped position; release moves the clip; undo restores it.
- **Clipboard round-trip**: cut/copy/paste/duplicate a MIDI clip, and paste a mixed audio+MIDI selection — both kinds land at the paste position with one undo entry.
- **Split/glue test**: scissors on a MIDI clip yields two clips whose combined content equals the original; glue rejoins them; both undoable.
- **Nudge and eraser tests**: nudge moves a MIDI clip by the nudge increment; eraser deletes it; both undoable.
- **Declined-op reason test**: an operation invoked with nothing applicable publishes a visible reason (status hint), never silence.
- **Phantom-clip guard**: a regression test asserting no arrangement operation creates an `AudioClip` on a MIDI track (holds story 342's fix across the parity surface).

## Non-Goals

- **The pencil fix** (create/extend MIDI content or no-op with a hint on MIDI tracks) — story 342 (Stage 4) owns it; this story only asserts the guard.
- **MIDI-content editing** (notes, CC lanes, piano-roll gestures) — this story is arrangement-level clip operations only; the MIDI editor surface is untouched.
- **MIDI persistence** (notes/CC serialization round-trip) — story 330 (`PERSISTENCE_INTEGRITY_DESIGN_BOOK.md`).
- **The drag presenter itself** — story 343 (Stage 5, prerequisite for the move ghost; the non-drag parity ops do not depend on it).
- **Whether MIDI plays** — the engine⇄project wiring is Book 1's (story 314); edit-op parity holds regardless of playback wiring.

## Technical Notes

- **Implements Stage 6 of `docs/design/INTERACTION_COMPLETENESS_DESIGN_BOOK.md` — "MIDI Clip Editing Parity in the Arrangement"** (§5.6 parity matrix). Stage order is dependency order (…343 → 346 → 347): MIDI drag-moves inherit Stage 5's presenter, and Stage 7 depends on this stage's ops publishing invalidation.
- Files: `ClipEditController.java:96‑104` (selection consult), `:395‑397` (nudge targets), `:207‑246` (slip — the template to generalise from), `ClipInteractionController.java:256` (kind-aware `clipAt`), `:891‑897` (arm MIDI drag), `:1017/:1040` (eraser/scissors/glue call sites), `:1004` (pencil site — guard assertion only, fix in 342).
- The selection model and undo layer already carry MIDI (slip proves the substrate); extend `getSelectedClips()`/clipboard entries/hit-testing to be clip-kind-aware rather than building a parallel MIDI path — one code path per operation (book §2.8 discipline).
- Every parity op publishes its invalidation cause per the book's ledger taxonomy (§3.5 — "clip added/removed/moved/trimmed": affected lanes + overlay); story 347 removes the 60 fps repaint crutch and will expose any op that skipped publication.
- Cross-refs: story **342** (pencil fix + visual truth), story **343** (drag presenter, prerequisite), story **347** (ledger consumer — repaint economy), existing **143** (range selection, woven here), story **330** (MIDI persistence, Book 3), story **314** (Book 1 — MIDI audibility).

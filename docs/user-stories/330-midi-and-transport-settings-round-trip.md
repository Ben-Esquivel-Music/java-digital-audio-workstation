---
title: "MIDI and Transport Settings Round-Trip"
labels: ["bug", "persistence", "midi", "transport"]
---

# MIDI and Transport Settings Round-Trip

## Motivation

The serializer and deserializer have drifted into asymmetry, and whole categories of work vanish between save and reopen:

- **MIDI notes and CC lanes are never saved.** Every track owns a `MidiClip` (`Track.java:91`) that the piano roll and MIDI recording write into, but the track serializer (`ProjectSerializer.java:268` onward) writes clips, soundfont assignment, automation, and the MIDI effect chain — never the `MidiClip`. The pair `serializeMidiClip`/`deserializeMidiClip` exists (`ProjectSerializer.java:1062`, `:1130`) with **only test callers**. All piano-roll work is lost on save, silently.
- **MIDI effect chains are written and never restored.** The serializer emits a full `midi-effect-chain` element per track (`ProjectSerializer.java:341-343`); the deserializer contains no handling for it at all. Write-only persistence: the bytes are on disk, the arpeggiator settings are gone on load.
- **Pre-roll / post-roll are written and never restored.** `buildTransport` writes the three pre/post-roll attributes (`ProjectSerializer.java:252-254`); no `pre-roll` token exists anywhere in the deserializer.

For a studio engineer, every MIDI arrangement session is unconditionally destroyed by the act of saving it — and the write-only elements are worse than nothing: they waste bytes, imply safety, and rot silently (design book §2.9). Nothing *fails* today when a serializer gains an element the deserializer never learns; that structural hole is what let these three bugs ship.

## Goals

- Each track's `MidiClip` (notes, velocities, channels, CC lanes with breakpoints) embeds as a child element in `project.daw`, promoting the already-written-and-tested `serializeMidiClip` / `deserializeMidiClip` pair from test-only utility to the production path (design book §4.6).
- The `midi-effect-chain` element gains its deserializer reader: per-track MIDI-effect chains (arpeggiator settings) restore on load.
- Pre-roll / post-roll restore joins the existing loop/punch parsing in the transport deserializer.
- The **write/read parity harness** (design book §4.4) lands in this story and retroactively pins the whole current schema:
  - A schema parity test derives the element/attribute names the serializer can emit and asserts the deserializer handles each (and vice versa for required elements), failing with the offending name.
  - A model round-trip test family: build a project exercising the book's §3.1 inventory rows, save, reopen, compare fact by fact; each new serialized fact adds its assertion in the same PR.
  - Both live in `daw-core` (JavaFX-free), following the repo's conformance-sentinel tradition: the test names the contract, the failure message names the drifted element.
- Schema additions ride the story-188 migration registry with a schema-version bump and pre-migration backup; old project files open unchanged with defaults for the absent elements.

## Goals — Tests

- **Parity harness is green — and demonstrably red on regression**: removing (or renaming) any deserializer reader for an emittable element (e.g. `midi-effect-chain`) fails the build with a message naming that element. The historical write-only elements of this story would have been build failures the day they were written.
- **MIDI round-trip**: piano-roll notes (pitch, velocity, channel, timing) and CC lanes survive save → close → reopen identically.
- **MIDI-effect-chain round-trip**: per-track chain contents and settings survive save → reopen.
- **Pre/post-roll round-trip**: pre-roll/post-roll configuration survives save → reopen; the §3.1 MIDI and transport rows flip to OK.
- **Migration test**: a project file written before the version bump opens unchanged through the story-188 registry, with sensible defaults where the new elements are absent.

## Non-Goals

- The tempo map, take stacks / comp selections, and plugin-slot descriptors — story 334 (it extends this story's parity harness in the same PRs).
- Audio-data rehydration and the missing-assets report — story 329.
- Making the restored MIDI-effect chain audible/processed — existing **story 151** owns the arpeggiator's processing wire-up; this story restores the persisted settings only.
- Write atomicity, snapshot isolation, and the single-save collapse — story 331.
- MIDI clip editing parity in the arrangement (move/cut/copy/split) — `INTERACTION_COMPLETENESS_DESIGN_BOOK.md` story 346; savable MIDI is its precondition.
- Forward compatibility (new files opening in old builds) — the migration registry's version check refusing with a clear message is the accepted behaviour (book §6.3).

## Technical Notes

- Implements **Stage 2 — MIDI and Transport Settings Round-Trip** of `docs/design/PERSISTENCE_INTEGRITY_DESIGN_BOOK.md` (§4.4 parity harness, §4.6 schema growth, §3.1 inventory rows for MIDI and transport).
- Files: `ProjectSerializer.java` — embed the MidiClip element in the track serializer (`:268` onward) via `serializeMidiClip` (`:1062`); `ProjectDeserializer.java` — new readers for the MIDI clip element, `midi-effect-chain`, and the pre/post-roll attributes (`ProjectSerializer.java:252-254` names them); new parity-harness + round-trip test family under `daw-core` test sources.
- The harness is the persistence instance of the repo's conformance-sentinel pattern (marker + source-derived contract + failure naming the drifted item); it becomes the permanent wall story 334 builds its schema additions against.
- Cross-refs: **188 — Project Version Migration Registry** (landed; carries the version bump), **151 — MIDI Arpeggiator Built-In Plugin** (processing side), **334** (Stage 6 extends the harness), **346** (Book 5 editing parity), **063** (this story closes part of its serialization scope; 329/334 close the rest).
- Design principles bound here: §2.1 (a fact that does not round-trip does not exist), §2.9 (parity enforced by a harness, not memory); rejection §9.3 (write-only persistence — never waive the harness).

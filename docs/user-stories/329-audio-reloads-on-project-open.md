---
title: "Audio Reloads on Project Open"
labels: ["bug", "persistence", "project-load", "audio"]
---

# Audio Reloads on Project Open

## Motivation

Reopening a saved project restores metadata, not music. The deserializer rebuilds every `AudioClip` from name, position, duration, and the persisted source path only — `ProjectDeserializer.java:539` constructs the clip and never calls `setAudioData`; `AudioClip.setAudioData` (`AudioClip.java:327`) is called by importers, recording, and clip-edit actions, never by any load path. Both consumers of a clip's audio read the in-memory array: the render loop resolves `clip.getAudioData()` (`RenderPipeline.java:1069`) and **skips the clip entirely** when it is null or empty (`RenderPipeline.java:894-895`), and the arrangement waveform draws from the same array (`ClipOverlayRenderer.java:152`). A reopened project therefore *plays silence and renders empty clips* — every clip, every time, even when the source WAV is sitting right there on disk.

Worse, the deserializer already *knows* when sources are gone: it collects every missing path during load (`ProjectDeserializer.java:532`, `:535`) into an accessor (`ProjectDeserializer.java:133`) whose only callers are round-trip tests. The production open path (`ProjectLifecycleController.java:844`) never consults it and shows an unconditional "Opened project: …" `SUCCESS` notification (`ProjectLifecycleController.java:866`) for a project whose audio may be wholly gone.

For a studio engineer this breaks the single most basic promise a DAW makes: yesterday's session reopens today as a silent shell of clip outlines, with a green success toast on top. This is the loudest blocker in the whole persistence audit.

## Goals

- Implement the clip rehydrator (design book §4.3): after deserialization, every `AudioClip` that carries a source reference but no sample data is queued for rehydration on background virtual threads — decode the source file, populate the clip's audio data via `setAudioData`, and publish per-clip completion to the FX thread through the established `FxDispatcher` marshalling seam so waveforms appear as data lands.
- Playback readiness is a **per-clip** fact, not a whole-project gate: a 40-track project must not block its window (or the FX thread) behind 40 decodes. Rehydration is eager-but-async — lazy decode-on-first-read is explicitly rejected (§4.3) because the render pipeline's clip skip is silent, so a lazy miss during playback would reproduce the silent-clip symptom for the first bars.
- Surface the deserializer's already-collected missing-file list as a **missing-assets report**: one warning surface per open with count + paths and a relink affordance (locate file / locate folder / skip), mirroring the archive dialog's per-asset pattern; the report links the archive consolidation flow (story 189) as the recovery-of-last-resort, not duplicating it.
- The open notification becomes **outcome-qualified**: plain success only when every referenced asset resolved; otherwise "opened with N missing assets" (design book §5.3 step 8, rejection §9.11).
- Path semantics (design book §3.3): asset references inside `project.daw` normalise **project-relative** (forward-slash) on the next save; absolute paths remain accepted on read for backward compatibility; relinked paths are re-persisted project-relative when the asset lives under the project directory, and stay absolute when it does not (shared sample libraries).

## Goals — Tests

- **Round-trip playback test**: import audio → save → close → reopen → the clip's audio data is non-empty, the render pipeline no longer skips it, and the waveform renders identically to the pre-save state (the first row of the book's §5.1 round-trip contract goes green).
- **Missing-source test**: delete a clip's source file → reopen → the missing-assets report appears exactly once with the correct count and paths, and the open notification is qualified — never the unconditional `SUCCESS` toast.
- **Relink test**: relinking a missing asset restores its playback/waveform in the open session and re-persists the new reference project-relative on the next save.
- **No-FX-stall test**: opening a many-clip project performs no decode on the FX thread; per-clip completions arrive through the dispatcher seam (coalesced), and the window is interactive before rehydration completes.
- **Path-normalisation test**: a legacy absolute path pointing inside the project directory loads correctly and is rewritten project-relative on the next save; a reference outside the project directory stays absolute.

## Non-Goals

- Serializing state that is never written today (MIDI, tempo map, takes, plugin descriptors) — stories 330 and 334 own the write-side inventory.
- Making recorded audio exist durably under the project directory with project-relative segment paths — `RECORDING_RELIABILITY_DESIGN_BOOK.md` story 323 owns capture-to-disk; this story reloads whatever the write side persisted.
- Wiring the engine so reloaded audio is *heard* through the transport — `AUDIO_ENGINE_WIRING_DESIGN_BOOK.md` story 314.
- Waveform rendering correctness beyond "no longer empty" (source-offset truth after trim/split/slip) — `INTERACTION_COMPLETENESS_DESIGN_BOOK.md` story 342 consumes the data this story rehydrates.
- The archive consolidation flow itself — story 189 (landed); the relink surface links to it.
- Atomicity of the save that persists relinked paths — story 331.

## Technical Notes

- Implements **Stage 1 — Audio Reloads on Project Open** of `docs/design/PERSISTENCE_INTEGRITY_DESIGN_BOOK.md` (§4.3 clip rehydrator + missing-assets surface, §3.3 path semantics, §5.3 open contract steps 4/5/8).
- Files: `ProjectDeserializer.java` (the `:133` missing-files accessor becomes a production input); a new load-side rehydration service in the open pipeline; `ProjectLifecycleController.java` — `loadProjectFromPath` (`:844`) queues rehydration, surfaces the report, and qualifies the `:866` announcement. Decoding reuses the same decode path the importers already use to fill `AudioClip.audioData`; FX marshalling reuses the story-289 `FxDispatcher` seam with per-clip coalescing keys.
- Completes the audio-reload and relink halves of **story 063 — Complete Project Serialization Including Clips, Mixer, and Automation** (existing, unimplemented; its remaining serialization scope lands in stories 330/334).
- Cross-refs: **189** (archive consolidation, linked from the relink surface), **314** (Book 1 engine wiring — complementary, ordered either way per book §7.2), **342** (Book 5 waveform truth gets real data), **323** (Book 2 produces the durable recorded segments this story's reader consumes), **331** (atomic writes for the re-persisted paths).
- Research backing: `research-daw` §3 (project file format) — portable project directories are only portable when references travel with the folder (book §3.3 rationale, Appendix B).

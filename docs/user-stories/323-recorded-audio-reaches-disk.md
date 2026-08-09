---
title: "Recorded Audio Reaches Disk: Segment Flush and Project-Relative Storage"
labels: ["bug", "recording", "reliability", "persistence"]
---

# Recorded Audio Reaches Disk: Segment Flush and Project-Relative Storage

## Motivation

No recorded sample ever reaches disk. `RecordingSession` is the capture buffer and it is RAM only: `recordAudioData` appends frames into a growing in-memory `[channel][sample]` array (`RecordingSession.java:201-240`), and the class contains zero file I/O — a grep for file-writing calls across the recording package matches only the metronome settings JSON store (`MetronomeSettingsStore.java:155`). The class javadoc promises the opposite: *"Each segment is stored as a separate file … A crash only risks the current segment, not the entire session"* (`RecordingSession.java:20-25`). Both sentences are false:

- `startNewSegment` constructs only a `RecordingSegment` metadata record naming `segment-NNN.wav`; no file is ever created (`RecordingSession.java:395-405`).
- `finalizeCurrentSegment` marks the in-memory record complete with a **wall-clock-estimated** sample count (`RecordingSession.java:407-423`) — even the metadata is approximate.
- `RecordingPipeline.stop()` stamps each recorded clip's source path from `getSegments().getFirst()` — the path of a file that never existed, and only the *first* segment even when rotation produced several (`RecordingPipeline.java:306-308`). Playback after stop works only because the full take is attached to the clip in RAM (`RecordingPipeline.java:317-319`).
- `ProjectSerializer.buildClipElement` persists only the `source-file` attribute (`ProjectSerializer.java:376-378`); on reload the nonexistent path is flagged in `missingFiles` (`ProjectDeserializer.java:529-537`). A recorded take is unrecoverable after any restart.

And the fictional path is not even inside the project: `TransportController.onRecord` targets `Files.createTempDirectory("daw-recording-")` — the OS temp directory, wiped on cleanup or reboot (`TransportController.java:433`) — while the status bar claims *"auto-save active"* (`TransportController.java:484-485`). Meanwhile `ProjectManager` creates an `audio/` directory in every project, documented as *"recorded audio files"*, that nothing ever writes into (`ProjectManager.java:32,39,160`).

For a studio engineer the consequence is absolute: **every take of every session is unconditionally lost** — on a crash, on a device failure, on OS temp cleanup, or at the latest on project reload. This is THE data-loss story and the single highest-leverage change in `docs/design/RECORDING_RELIABILITY_DESIGN_BOOK.md`: the session invariant (book §2.1) — *everything the performer played is on disk, inside the project, within seconds of being played* — is currently inverted.

## Goals

- Introduce the capture-to-disk skeleton of book §4.1-4.4: a preallocated `CaptureRing` the recording callback hands raw device blocks to (with slot headers stamping start frame, transport beat position, and gate flags), a dedicated `capture-flush` platform thread (`CaptureFlushService`) that drains the ring strictly in order, routes per armed track (reusing the existing pipeline routing/gating logic as-is for this stage), and a `SegmentWriter` that streams real WAV segments to disk.
- Recorded audio lands under `<project>/audio/takes/<UTC-timestamp>_take-NNNN/<trackId>/segment-NNN.wav` per the book §3.3 layout — the `audio/` directory `ProjectManager` already creates. The OS temp-directory root (`TransportController.java:433`) is deleted; the OS temp directory never holds session audio (book §2.6).
- Each take directory carries a `take.manifest` sidecar written by the flush thread at take start and rewritten at each rotation/seal: format, start position, compensation frames, ordered per-track segment lists, seal status — so a recovery scan can rebuild a take without the project file (book §3.3).
- Segment lifecycle per book §3.4: segments stream as `segment-NNN.wav.part` with a provisional self-describing header (fixed 44-byte data offset; sample count derivable from file length), are force-flushed to storage every 5 seconds (configurable), and seal via patch-header + atomic rename — the rename is the commit point. Exact sample counts replace `finalizeCurrentSegment`'s wall-clock estimates.
- Stop finalizes atomically: seal all active segments, write the manifest's final seal status, then build clips that reference **every** segment of the take in manifest order — the `getFirst()`-only stamping (`RecordingPipeline.java:306-308`) is deleted.
- Serialized clip references are **project-relative** (`audio/takes/…/segment-NNN.wav`); absolute paths for anything under the project root are forbidden (book §9.5). The serialization schema change is coordinated with `PERSISTENCE_INTEGRITY_DESIGN_BOOK.md` (stories 329/334 own reload and take persistence).
- The existing 30-minute / 500 MB segment-rotation caps are retained; rotation becomes a flush-thread act producing real files (book §5.3).
- A disk-headroom watch on the flush thread (cached free-space figure, refreshed on a slow tick): crossing the low-water mark raises a warning, and exhaustion seals the take cleanly with everything captured so far intact — never a throw out of a write loop (book §4.3). Until the Book 4 notification stories land, warnings degrade to logging (book §6.3 ordering note).
- The transport status line during recording tells the truth: the §1.1 *"auto-save active"* claim becomes accurate, because audio is continuously flushed.
- Explicitly allowed this stage: the in-RAM `capturedAudio` growth may remain, since it feeds today's post-stop playback attach — story 324 (Stage 2) removes it and makes the callback side RT-safe.

## Goals — Tests

- A crash-durability test records ~2 minutes of simulated capture and hard-terminates the writer mid-take: the sealed and `.part` segments under `<project>/audio/takes/` contain the captured audio, and a reader using only the §3.4 grammar (sample count = (length − data offset) ÷ frame size) recovers playable audio.
- A rotation test records past a rotation boundary and asserts the finalized clip references **all** segments, in manifest order — never only `segment-000.wav`.
- A round-trip test saves and reopens a project with a recorded take: serialized clip references are project-relative, resolve inside the project directory, and are flagged missing only if the files were actually deleted.
- A filesystem-level test asserts the seal is atomic: no observable `.wav` ever has a provisional (streaming-sentinel) header — a `.wav` is either fully sealed or still a `.part`.
- A force-cadence test asserts un-forced data is bounded by the configured cadence (default 5 s) per active segment — the book §2.1 risk window.
- A manifest test asserts the sidecar carries the ordered segment list, format, start position, and seal status, and is rewritten at rotation and seal.
- A storage-location test asserts the record path roots at the project's `audio/` directory and that no capture-path code references `Files.createTempDirectory` (the temp-root call site is gone).
- A disk-exhaustion test simulates free-space exhaustion mid-take: the take seals cleanly with all previously flushed audio intact and readable.

## Non-Goals

- **RT-safety of the callback side** — removing the on-callback allocation/copy growth, the bounded UI mirror, the stop fence, and format truth are story 324 (Stage 2). This stage may leave the existing in-RAM accumulation in place.
- **Record-state guards** (double-press, input-open failure, mid-record settings apply) — story 325 (Stage 3).
- **Multi-channel routing width and per-track device honouring** — story 326 (Stage 4); this stage reuses today's routing logic unchanged.
- **Device-loss detection and take rescue** — story 327 (Stage 5); note that its rescue *is* this stage's seal path applied early, and `IncompleteTakeStore` re-rooting happens there.
- **Count-in, punch creation, loop-take lanes, comping** — story 328 (Stage 6); the gating engine is reused as-is here.
- **Reloading persisted audio on project open and surfacing missing files** — story 329 (`PERSISTENCE_INTEGRITY_DESIGN_BOOK.md`), which this story unblocks by giving it real files; take-stack persistence is story 334.
- **When the recovery scan runs** — story 332 owns invoking recovery on every open path; this story defines the on-disk grammar (`.part` + manifest) the scan reads.
- **Streamed/paged clip playback** — post-stop playback keeps today's in-RAM clip model; paging belongs to the engine's playback architecture (`AUDIO_ENGINE_WIRING_DESIGN_BOOK.md`) and story 329's reload seam.
- **Production notification injection** — story 339 (`FAILURE_SURFACING_DESIGN_BOOK.md`); this stage degrades user-facing warnings to logging where the injected manager is absent.

## Technical Notes

- Implements **Stage 1 — Recorded Audio Reaches Disk: Segment Flush and Project-Relative Storage** of `docs/design/RECORDING_RELIABILITY_DESIGN_BOOK.md` (§8). The behavioural contracts bound here: §3.3 (on-disk layout), §3.4 (segment lifecycle), §5.3 (segment lifecycle contract), §4.2-4.4 (ring, flush service, writer), §2.1/§2.5/§2.6 (principles).
- New classes in the `daw-core` recording package: `CaptureRing`, `CaptureFlushService`, `SegmentWriter`, `TakeManifest`, `TrackCapture` (book §3.1). Files to touch: `RecordingSession.java` (segment metadata gains real files; estimates → exact counts), `RecordingPipeline.java` (stop/finalize references every segment; `:306-308` deleted), `TransportController.java` (`:433` temp root deleted; project-rooted output; truthful status line), `ProjectSerializer.java`/`ProjectDeserializer.java` (project-relative references), `ProjectManager.java` (the existing `audio/` directory becomes real).
- Model `CaptureRing` on the proven house idiom `AudioBlockRing`, and the `capture-flush` thread on `AsioBufferSwitchShim`'s `asio-input-drain` thread (stories 311-312; book §1.9, Appendix A) — park when dry with a bounded backstop, drain strictly in order.
- `SegmentWriter` is a distinct class from `WavExporter`: export is one-shot known-length, capture is append-unknown-length with partial-file states (book §4.4's rejected alternative).
- Ring slot headers carry frame/beat/gate snapshots stamped on the callback so flush-side gating stays sample-accurate (book §4.2); per-track RT-side routing was explicitly rejected (book §9).
- Subsumes the write-to-disk goal of story **007** (Complete Recording Workflow with Monitoring and Count-In) and the capture-integration disk goal of story **060** (Complete Recording Pipeline with Audio Capture and Clip Creation) — cross-reference both rather than re-filing.
- Cross-refs: story **324** (RT-safe completion on this seam), **327** (rescue = this seal early), **329/332/334** (`PERSISTENCE_INTEGRITY_DESIGN_BOOK.md` hand-offs, book §6.4), **331** (shared temp-file + atomic-move idiom).
- Research backing: SKILL `research-daw` §3 (real-time audio: lock-free hand-off, dedicated I/O threads, never block the callback).

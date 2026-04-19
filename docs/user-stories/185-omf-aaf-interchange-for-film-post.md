---
title: "OMF / AAF Interchange Export for Film Post-Production"
labels: ["enhancement", "export", "interop", "film-post"]
---

# OMF / AAF Interchange Export for Film Post-Production

## Motivation

Film and television audio handoff between editorial and post is handled via OMF (Open Media Framework) or AAF (Advanced Authoring Format) files: every clip position, fade, gain, and reference to source media is captured in a single file that any post DAW can ingest. Pro Tools and Nuendo dominate the film-post market largely because they speak OMF/AAF fluently. A DAW that cannot produce OMF/AAF is effectively excluded from professional post workflows.

## Goals

- Add `AafWriter` in `com.benesquivelmusic.daw.core.export.aaf` emitting AAF 1.2 files. AAF is the modern preference over the older OMF.
- Implement as a pure-Java writer over the minimal subset required: timeline composition, source references (embedded or external), per-clip position/length/source-offset, fade-in/fade-out with curve types, per-clip gain.
- Add an OMF 2.0 fallback writer for older workflows.
- `AafExportService` assembles the export: gather all `AudioClip`s, their source assets, fades, and gains; emit AAF with audio either embedded (self-contained) or referenced (smaller).
- Pre-compose the timeline at a single frame rate (23.976, 24, 25, 29.97, 30 fps) selected by the user; the start TC is user-configurable.
- `AafExportDialog`: frame-rate selector, embed vs reference toggle, start TC input, per-track inclusion list.
- Validate the output by re-reading via a bundled AAF verifier (test dependency).
- Tests: a 3-track session with known fades round-trips through AAF and the re-read state matches within position/length precision (1 sample); frame-rate conversion preserves timeline length.

## Non-Goals

- Video reference embedding (video track goes in a separate video-export story).
- AAF import — a larger, separate story.
- Reel-by-reel stem handoff with metadata beyond per-clip attributes.

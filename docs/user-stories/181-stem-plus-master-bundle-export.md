---
title: "Stem + Master Bundle Export (Single-Click Deliverable Package)"
labels: ["enhancement", "export", "deliverables"]
---

# Stem + Master Bundle Export (Single-Click Deliverable Package)

## Motivation

Delivering a mix to a mastering engineer or film-music supervisor requires a bundle: the mastered stereo, the stems (drums, bass, keys, vocals, FX), a metadata JSON describing channel counts and sample rate, and often a PDF track-sheet. Doing this with the current DAW requires running `StemExporter` once, running `WavExporter` once, building the metadata by hand, and zipping. Every DAW has a one-shot bundler: Studio One's "Mixdown to Stems," Pro Tools' "Bounce Mix," Cubase's "Audio Mixdown" with stem option.

## Goals

- Add `DeliverableBundle` record in `com.benesquivelmusic.daw.sdk.export`: `record DeliverableBundle(Path zipOutput, MasterFormat master, List<StemSpec> stems, BundleMetadata metadata, boolean includeTrackSheet)`.
- Add `BundleExportService` in `com.benesquivelmusic.daw.core.export` that orchestrates: master render → stem renders → metadata JSON build → optional track-sheet PDF → zip assembly — all on a virtual thread (story 205).
- `BundleMetadata` record carries: session tempo, key, sample rate, bit depth, LUFS-I, true-peak, per-stem format and channel layout, project title, engineer, and a rendered-at timestamp.
- Track sheet PDF (generated via a minimal text-based PDF writer) lists per-stem file names + peak/RMS/LUFS measurements so the mastering engineer can see the per-stem levels at a glance.
- `BundleExportDialog` in `daw-app.ui.export`: stem selection checkboxes, master format dropdown, track-sheet toggle, output folder/zip name; a progress bar with per-step status.
- Presets: "Master + Stems for Mastering," "Master Only," "Stems Only with Reference Master," "Streaming Delivery Bundle" (each fills in sensible defaults).
- Tests: exporting a 4-stem session produces a zip with 5 WAVs + metadata.json (+ PDF if selected); the metadata measurements match the live meter within 0.2 LUFS; zip is valid per the ZIP specification.

## Non-Goals

- Cloud upload to SoundCloud / Disco / Dropbox.
- Encryption / password-protected archives.
- Watermarking stems.

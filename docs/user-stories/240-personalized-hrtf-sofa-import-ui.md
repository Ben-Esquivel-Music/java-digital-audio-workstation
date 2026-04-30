---
title: "Personalized HRTF SOFA Import UI: Import Action, Coverage Preview, Profile Picker"
labels: ["enhancement", "spatial", "binaural", "hrtf", "ui"]
---

# Personalized HRTF SOFA Import UI: Import Action, Coverage Preview, Profile Picker

## Motivation

Story 174 — "Personalized Binaural HRTF Import via SOFA Files" — enables users to import their personally measured HRTF (head-related transfer function) data in the AES SOFA (AES69-2020) file format so binaural monitoring uses their own ear/head geometry. Generic HRTFs work but personalized ones dramatically improve externalization and height-cue accuracy. The core is implemented and tested:

- `daw-core/src/main/java/com/benesquivelmusic/daw/core/spatial/binaural/SofaFileReader.java` (NetCDF/SOFA parser).
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/spatial/binaural/HrtfProfileLibrary.java` (profile registry + persistence under `~/.daw/hrtf/`).
- `SofaFileReaderTest`, `HrtfProfileLibraryTest`.

But:

```
$ grep -rn 'SofaFileReader\|HrtfProfileLibrary' daw-app/src/main/
(no matches)
```

There is no "Import SOFA…" menu item, no profile picker in the binaural-monitoring chain, no coverage-preview dialog. Users with a measured HRTF file have no way to load it into the DAW. The feature ships as a tested core library nobody can reach.

## Goals

- "Import SOFA…" action accessible from:
  - The binaural-monitoring plugin view (the existing `BinauralMonitorPlugin` view from story 105 / 018).
  - Settings → "HRTF Profiles…" (a small dialog managing the user's library).
- Import flow:
  - File picker filtered to `.sofa`.
  - Validation via `SofaFileReader`: report schema conformance, sample rate, measurement count, hemisphere coverage. Reject malformed files with a clear error sourced from the reader's `ImportResult` (already structured for this).
  - On accept: profile is copied into `~/.daw/hrtf/<sanitised-name>.sofa`, registered with `HrtfProfileLibrary`, and immediately available in the binaural plugin's profile combo.
  - If the SOFA file's sample rate differs from the session, resample at load time via the story-126 / 227 `SampleRateConverter`.
- Coverage-preview widget: a small hemisphere visualization inside the import dialog showing the measurement positions as dots over a unit-sphere wireframe. Sparse coverage warns the user ("Profile has only 64 measurements; aliasing artifacts may be audible at unmeasured directions").
- `BinauralMonitorPluginView` (or the existing binaural setup dialog) shows a profile combo with both factory-bundled and user-imported profiles, grouped. The active profile selection persists per-project in `ProjectSerializer`; if the project references a profile not present on the loading machine, fall back to the highest-matching factory profile and surface a one-shot warning.
- "Manage HRTF Profiles…" dialog: list / preview / delete user-imported profiles. Factory profiles are read-only.
- Tests:
  - Headless test: feed a known AES69 conformance SOFA file through the import flow, assert it appears in `HrtfProfileLibrary.list()` and is selectable in the binaural plugin.
  - Test confirms a corrupted SOFA file is rejected with the expected error string.
  - Test confirms a 44.1 kHz SOFA file imported into a 48 kHz session is resampled at load.

## Non-Goals

- SOFA file *authoring* (recording your own HRTFs in the DAW) — import only.
- Real-time HRTF synthesis from head measurements.
- Head-tracking integration (a separate story that consumes the loaded profile).
- Multi-source SOFA files (HOA / Ambisonic source representations) — single-source binaural impulse responses only.

## Technical Notes

- Files: new `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/HrtfProfileImportDialog.java`, new `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/HrtfProfileBrowserDialog.java`, the existing binaural plugin view (find it under `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/`), `daw-core/src/main/java/com/benesquivelmusic/daw/core/persistence/ProjectSerializer.java` (persist active profile reference).
- `SofaFileReader.ImportResult` already structures success / failure paths.
- `HrtfProfileLibrary.Kind` enum already exists for factory / user grouping.
- Reference original story: **174 — Personalized HRTF SOFA Import**.

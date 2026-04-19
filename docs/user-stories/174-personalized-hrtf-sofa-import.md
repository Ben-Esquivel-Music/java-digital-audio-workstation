---
title: "Personalized Binaural HRTF Import via SOFA Files"
labels: ["enhancement", "spatial", "binaural", "hrtf"]
---

# Personalized Binaural HRTF Import via SOFA Files

## Motivation

Story 018 provides binaural monitoring with generic HRTFs. Individual ear/head geometry varies widely; a personal HRTF measured or computed for the user dramatically improves externalization and height cue accuracy. The AES SOFA file format (AES69-2020) is the open standard for HRTF data. Services like Genelec Aural ID, 3D Tune-In, and SonicVR distribute personalized HRTFs as SOFA files. Supporting SOFA import makes the DAW usable for serious binaural mixing work.

## Goals

- Add `SofaFileReader` in `com.benesquivelmusic.daw.core.spatial.binaural` parsing the NetCDF-based SOFA format via a bundled minimal NetCDF reader (FFM binding to `libnetcdf` or a pure-Java implementation).
- Add `PersonalizedHrtfProfile` record in `com.benesquivelmusic.daw.sdk.spatial`: `record PersonalizedHrtfProfile(String name, int measurementCount, double sampleRate, float[][] leftImpulses, float[][] rightImpulses, double[][] measurementPositionsSpherical)`.
- `HrtfProfileLibrary` gains "Import SOFA..." action; imported profiles appear in the binaural monitoring chooser alongside generic profiles.
- Validation on import: confirm schema conformance, sample-rate compatibility, and measurement coverage (warn if sparse); reject malformed files with a clear error.
- Resample imported impulses to the session sample rate at load time via the story-126 SRC.
- Persist imported profiles under `~/.daw/hrtf/`; project files reference profiles by name so the session is portable across machines that have the same profile.
- UI: SOFA import dialog with preview of measurement coverage (hemisphere coverage visualization).
- Tests: an AES69 conformance SOFA file imports without error; a known position (azimuth=0, elevation=0) produces symmetric L/R impulse responses; sample-rate mismatch triggers SRC at load.

## Non-Goals

- SOFA file *authoring* (recording your own HRTFs) — this is import only.
- Real-time HRTF synthesis from head measurements.
- Head-tracking integration (a separate story that consumes this profile).

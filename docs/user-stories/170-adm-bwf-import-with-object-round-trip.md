---
title: "ADM BWF Import with Object Round-Trip (Complements Export Story 026)"
labels: ["enhancement", "export", "import", "spatial", "immersive", "atmos"]
---

# ADM BWF Import with Object Round-Trip (Complements Export Story 026)

## Motivation

Story 026 covers ADM BWF *export* for Dolby Atmos deliverables. The inverse — *importing* an ADM BWF file and reconstructing the object-based session — is equally important: mastering engineers routinely receive ADM BWF masters from mix engineers for QC and post-production. Without ADM import, the DAW cannot be the final stage of an immersive-audio delivery chain. Dolby Atmos Renderer, Nuendo, Pro Tools Ultimate all read ADM BWF; this is table stakes for immersive work.

ADM BWF is a WAV file with embedded XML (the `axml` chunk) describing object positions, bed channels, and metadata over time. Parsing it and reconstructing tracks + automation is a specification-driven task.

## Goals

- Add `AdmBwfImporter` in `com.benesquivelmusic.daw.core.audioimport` that parses the `axml` chunk using the EBU Tech 3285 Supplement 5 (ADM) schema.
- Produce, for each ADM `audioObject`: a `Track` with `SpatialClip`(s) containing the object's audio and an `AutomationLane` for the object's `position` (x/y/z) + `size` + `gain` time-stamped values.
- Bed channels materialize as a multi-channel `Track` mapped to the session's bed layout (5.1.4, 7.1.4, etc.); if the session has no bed, create one.
- Unmatched ADM metadata (author, contentDescription, custom tags) preserved in `Project.metadata` for later export.
- `AdmImportDialog`: preview pane showing objects, bed layout, and expected track count; options for "merge objects into single track" or "one track per object."
- Integration with `AudioFileImporter.importFile()` — `.wav` files with an `axml` chunk are routed through `AdmBwfImporter`.
- Persist imported data via `ProjectSerializer` (already supports spatial).
- Export (story 026) round-trip: import → export → import produces bit-identical session state within schema-expressible precision.
- Tests: a reference ADM BWF from Dolby's test set round-trips through import/export and produces the same object-count, bed-channel-count, and automation point count (counts; values may have float precision drift).

## Non-Goals

- ADM Serial parsing from live broadcast streams.
- Non-ADM immersive formats (MPEG-H, Sony 360RA) — separate future stories.
- Parsing vendor-extension XML namespaces beyond the core schema.

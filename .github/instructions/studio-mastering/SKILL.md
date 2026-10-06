---
name: studio-mastering
description: "Plan and verify stereo mastering, restoration, loudness, true peak, sequencing, metadata, sample-rate conversion, dithering, and delivery. Use for final program preparation and export validation."
---

# Studio Mastering

## Sources and requirements

Read the relevant sections of [mastering research](../../../docs/research/mastering-techniques.md) and [AES mixing/mastering research](../../../docs/research/aes-research-papers.md#mixing-and-mastering). Use the existing research-mastering skill for deeper discovery.

Treat the local notes' platform loudness values as historical context. For a specified destination, verify current requirements from that destination and applicable standards. Distinguish playback normalization, submission requirements, and the artistic loudness choice. Do not silently substitute broadcast or immersive requirements for a stereo music master.

## Mastering work

Establish format, destination, client intent, reference, source peak/loudness measurements, and whether stereo, stems, or an album is being delivered. Evaluate the monitoring environment and use level-matched comparisons.

- Separate problems that need a mix revision from changes suited to mastering. Choose EQ, dynamics, restoration, and imaging from the actual issue; the reference chain is flexible.
- Measure integrated, short-term, and momentary loudness as relevant; report channel layout, gating/measurement method, true peak, and loudness range when useful. A sample-peak meter cannot establish true peak.
- Inspect limiter artifacts, transients, low-frequency modulation, stereo stability, and codec overshoots. Preserve an unprocessed reference and account for any loudness penalty in listening comparisons.
- Sequence an album with musical transitions, spacing, fades, and relative loudness in context. Avoid forcing every track to the same integrated LUFS.
- Perform final bit-depth quantization after the processing and sample-rate-conversion stages. Apply suitable dither for the actual reduction; document prior dither and noise shaping, avoid needless repetition, and do not describe floating-point export as integer bit-depth reduction.
- Reopen rendered deliverables and verify sample rate, sample format/bit depth, channel map, duration, start/end, fades, clipping, metadata, and decoded playback. Meter the delivered file, not only the live chain.
- Check destination-specific metadata, naming, identifiers, and packaging. Research notes proposing DDP, ADM, or codec support do not prove that the DAW implements those formats.

## Deliverable

Provide the master or an actionable chain, along with a compact delivery table of required format, measured loudness/peak, metadata, and checks actually performed. Label estimated values and unresolved requirements. Use [studio-immersive](../studio-immersive/SKILL.md) for spatial masters and renderer-specific metadata.

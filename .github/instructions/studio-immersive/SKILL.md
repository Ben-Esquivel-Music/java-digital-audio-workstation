---
name: studio-immersive
description: "Design and evaluate immersive studio production and monitoring with speaker layouts, object-based audio, Ambisonics, HRTFs, binaural rendering, and fold-downs. Use for spatial audio workflows and compatibility."
---

# Studio Immersive Audio

## Sources and conventions

Read [immersive audio research](../../../docs/research/immersive-audio-mixing.md) and [AES spatial research](../../../docs/research/aes-research-papers.md#spatial-audio-and-immersive-sound); use research-immersive for deeper references. The local overview contains simplified format examples. Verify actual bed/object limits, renderer support, and delivery specifications with current primary documentation when needed.

Establish the production format, renderer/version, speaker coordinates, channel order, coordinate axes, units, and monitoring paths. Distinguish channel-based surround, object metadata, scene-based Ambisonics, and a two-channel binaural render. These are not interchangeable file labels.

## Spatial engineering

- Define the artistic scene and stable listener reference before configuring automation. Record position, distance, spread, motion, and coordinate transforms explicitly.
- For full-sphere Ambisonics, channel count is `(order + 1)^2`. State normalization and ordering, such as SN3D/N3D and ACN/FuMa. Inspect actual metadata; WXYZ ordering cannot be assumed for every four-channel file.
- Separate A-format microphone capsules from encoded B-format signals. Use microphone-specific calibration and encoding rather than relabeling channels.
- Validate speaker mapping with an identifying test signal per channel. Measure level, delay, polarity, crossover/bass management, and room response; route acoustic correction to [studio-acoustics](../studio-acoustics/SKILL.md).
- Treat HRTF selection, SOFA coordinate conventions, headphone equalization, interpolation, and head tracking as parts of the renderer. Distinguish 3DoF rotation from 6DoF position plus rotation and account for end-to-end tracking latency.
- Check elevation, front/back confusion, externalization, timbre, and movement in binaural monitoring. A generic HRTF's outcome may differ by listener.
- Evaluate the intended speaker array, binaural path, stereo fold-down, and mono compatibility. Document downmix coefficients and LFE treatment rather than silently summing channels.
- Keep master audio, object metadata, renderer settings, binaural metadata, and delivery container requirements aligned. An ADM BWF container alone does not establish destination compliance or licensed renderer equivalence.

## Result

Produce a scene/routing plan, channel and coordinate mapping, monitoring matrix, and compatibility checks. Clearly identify renderer assumptions and any path that has not been auditioned or measured. For implementation, use [studio-dsp-modeling](../studio-dsp-modeling/SKILL.md) and inspect current DAW capability before promising export/render support.

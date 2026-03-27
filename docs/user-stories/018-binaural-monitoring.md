---
title: "Binaural Monitoring with HRTF Profile Selection"
labels: ["enhancement", "spatial-audio", "monitoring", "ui"]
---

# Binaural Monitoring with HRTF Profile Selection

## Motivation

The `DefaultBinauralRenderer`, `HrtfInterpolator`, `PartitionedConvolver`, and `SofaFileParser` classes provide binaural rendering capabilities. However, there is no UI for enabling binaural monitoring, selecting HRTF profiles, or switching between speaker and binaural monitoring modes. Most consumers listen to music on headphones, so validating spatial mixes binaurally is critical. The research documents highlight the measurable spectral and imaging differences between stereo and binaural renders, making A/B comparison essential for quality assurance.

## Goals

- Add a binaural monitoring toggle in the transport bar or monitoring section
- Allow users to select from built-in HRTF profiles for different head sizes
- Support importing custom HRTF profiles from SOFA files using `SofaFileParser`
- Provide A/B switching between speaker rendering and binaural rendering
- Display the current monitoring mode (Speakers / Binaural) clearly in the UI
- Apply the `BinauralExternalizationProcessor` for improved headphone spatialization
- Support fold-down monitoring preview (7.1.4 → 5.1 → stereo → mono)

## Non-Goals

- Custom HRTF measurement workflow
- Real-time head tracking via external hardware
- Speaker calibration and room correction

## Description

Add a processor that converts standard stereo mixes into binaural audio optimized for headphone playback. This enables monitoring stereo mixes on headphones with a more speaker-like spatial presentation, and also enables converting existing stereo content to binaural for immersive distribution.

## AES Research References

- [An Efficient Method for Producing Binaural Mixes of Classical Music from a Primary Stereo Mix](docs/research/AES/An_Efficient_Method_for_Producing_Binaural_Mixes_of_Classical_Music_from_a_Primary_Stereo_Mix.pdf) (2018) — Efficient stereo-to-binaural conversion using HRTF-filtered virtual speakers at ±30° with optional early reflections; validates perceptual equivalence to dedicated binaural recordings
- [Digital Signal Processing Issues in the Context of Binaural and Transaural Stereophony](docs/research/AES/Digital_Signal_Processing_Issues_in_the_Context_of_Binaural_and_Transaural_Stereophony.pdf) (1995) — Foundational DSP framework for binaural/transaural conversion covering HRTF convolution, crosstalk cancellation, and head-tracking compensation

## Implementation Approach

- New class `StereoToBinauralConverter` in `daw-core/…/spatial/binaural/`
- Virtual speaker positioning: place left and right channels at configurable azimuth (default ±30°) using HRTF from existing `SofaFileParser`/`HrtfInterpolator`
- HRTF convolution using the existing `PartitionedConvolver` for zero-latency processing
- Optional early reflection modeling (3–5 reflections) for enhanced externalization
- Optional room simulation via `FdnRoomSimulator` for ambient tail
- Parameters: speaker azimuth, distance, room influence, early reflections on/off
- Output: binaural stereo suitable for headphone playback

## Extends

`HrtfInterpolator`, `PartitionedConvolver`, `SofaFileParser`

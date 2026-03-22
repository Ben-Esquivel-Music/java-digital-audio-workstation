## Description

Add a psychoacoustic bass enhancement processor that generates harmonics of low-frequency content to create the perception of bass on playback systems with limited low-frequency reproduction. Uses the psychoacoustic "missing fundamental" effect — when harmonics of a fundamental are present, the brain perceives the fundamental even when it is absent.

## AES Research References

- [Advances in Perceptual Bass Extension for Music and Cinematic Content](docs/research/AES/Advances_in_Perceptual_Bass_Extension_for_Music_and_Cinematic_Content.pdf) (2023) — State-of-the-art perceptual bass extension algorithms for music and cinema; evaluates harmonic generation techniques and crossover strategies
- [Physiological measurement of the arousing effect of bass amplification in music](docs/research/AES/Physiological_measurement_of_the_arousing_effect_of_bass_amplification_in_music.pdf) (2024) — Physiological evidence that bass presence affects listener arousal; supports the value of bass enhancement features

## Implementation Approach

- New class `BassExtensionProcessor implements AudioProcessor` in `daw-core/…/dsp/`
- Low-frequency isolation via `BiquadFilter` lowpass/bandpass at configurable crossover (40–120 Hz)
- Harmonic generation: half-wave rectification or polynomial waveshaping of the isolated bass signal to generate 2nd, 3rd, and 4th harmonics
- Bandpass filtering of generated harmonics to suppress sub-harmonic artifacts and high-order distortion
- Mixing harmonics back with the original signal at configurable level
- Parameters: crossover frequency, harmonic order, harmonic level, dry/wet mix

## Extends

`BiquadFilter`, `AudioProcessor`

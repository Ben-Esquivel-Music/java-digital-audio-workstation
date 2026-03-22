## Description

Implement first-order antiderivative antialiasing (ADAA) as an alternative to oversampling for alias suppression in nonlinear processors. ADAA computes the antiderivative of the waveshaping function and uses finite differences to approximate the band-limited output — achieving effective alias reduction at zero additional oversampling cost.

## AES Research References

- [Antiderivative Antialiasing Techniques in Nonlinear Wave Digital Structures](docs/research/AES/Antiderivative_Antialiasing_Techniques_in_Nonlinear_Wave_Digital_Structures.pdf) (2021) — Extends ADAA to wave digital filter (WDF) structures, demonstrating first-order and second-order ADAA for diode clippers and guitar distortion circuits
- [Intermodulation Distortion Analysis of a Guitar Distortion Pedal With a Starving Circuit](docs/research/AES/Intermodulation_Distortion_Analysis_of_a_Guitar_Distortion_Pedal_With_a_Starving_Circuit.pdf) (2021) — Intermodulation analysis of guitar distortion circuits relevant to antialiasing requirements

## Implementation Approach

- New utility class `AdaaWaveshaper` in `daw-core/…/dsp/`
- Computes antiderivatives analytically for common transfer functions (tanh, hard-clip, soft-clip)
- First-order ADAA: `y[n] = (F(x[n]) - F(x[n-1])) / (x[n] - x[n-1])` where F is the antiderivative
- Handles the ill-conditioned case `x[n] ≈ x[n-1]` with L'Hôpital fallback
- Can be combined with the `WaveshaperProcessor` as a zero-latency alternative to oversampling

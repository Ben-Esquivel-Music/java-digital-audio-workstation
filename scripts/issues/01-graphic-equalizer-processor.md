## Description

Add a graphic equalizer processor supporting octave and third-octave band configurations with optional linear-phase mode. Unlike the existing `ParametricEqProcessor` which provides fully configurable parametric bands, a graphic EQ offers fixed-frequency band sliders familiar to audio engineers for broad tonal shaping — particularly useful for live monitoring correction and quick tonal adjustments.

## AES Research References

- [Linear-Phase Octave Graphic Equalizer](docs/research/AES/Linear-Phase_Octave_Graphic_Equalizer.pdf) (2022) — Presents a linear-phase graphic EQ design using symmetric FIR filters, achieving flat-sum magnitude response across octave bands without phase distortion
- [Design of a Digitally Controlled Graphic Equalizer](docs/research/AES/Design_of_a_Digitally_Controlled_Graphic_Equalizer.pdf) (2017) — Details minimum-phase graphic EQ design with optimal band interaction characteristics
- [Parametric Equalization](docs/research/AES/Parametric_Equalization.pdf) (2012) — Foundational reference for filter design applicable to both parametric and graphic implementations

## Implementation Approach

- New class `GraphicEqProcessor implements AudioProcessor` in `daw-core/…/dsp/`
- Fixed ISO octave center frequencies (31.5 Hz – 16 kHz for octave, 25 Hz – 20 kHz for third-octave)
- Each band backed by a biquad peak filter from the existing `BiquadFilter` (minimum-phase mode)
- Linear-phase mode converts the combined biquad response to a symmetric FIR via the existing `LinearPhaseFilter`
- Gain range: ±12 dB per band with configurable Q/bandwidth

## Extends

`BiquadFilter`, `LinearPhaseFilter`, `AudioProcessor`

## Description

Add a frequency-dependent air absorption filter that models the high-frequency attenuation of sound traveling through air. This is essential for realistic distance rendering in spatial audio — distant sources should sound progressively duller as high frequencies are absorbed by the atmosphere.

## AES Research References

- [Digital Filter for Modeling Air Absorption in Real Time](docs/research/AES/Digital_Filter_for_Modeling_Air_Absorption_in_Real_Time.pdf) (2013) — Presents efficient IIR filter designs that accurately model frequency-dependent atmospheric absorption as a function of distance, temperature, and humidity
- [A General Overview of Methods for Generating Room Impulse Responses](docs/research/AES/A_General_Overview_of_Methods_for_Generating_Room_Impulse_Responses.pdf) (2024) — Includes air absorption as a component of physically-based room impulse response generation

## Implementation Approach

- New class `AirAbsorptionFilter` in `daw-core/…/spatial/`
- Models ISO 9613-1 atmospheric absorption coefficients as a function of distance, temperature (°C), and relative humidity (%)
- Implemented as a cascade of low-order IIR filters with coefficients derived from the ISO standard absorption curves
- Distance parameter controls filter cutoff — increasing distance progressively rolls off high frequencies
- Integrates with the existing `InverseSquareAttenuation` distance model for complete distance rendering

## Extends

`BiquadFilter`, `InverseSquareAttenuation`

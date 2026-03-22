## Description

Add a comprehensive test signal generator for system calibration, measurement, and plugin testing. Includes sine sweeps, pink/white noise, impulse responses, and multi-tone stimuli — essential tools that every professional DAW should provide natively.

## AES Research References

- [A New Electronic Audio Sweep-Frequency Generator](docs/research/AES/A_New_Electronic_Audio_Sweep-Frequency_Generator.pdf) (1949) — The seminal AES paper on sweep-frequency signal generation; foundational reference for logarithmic and linear frequency sweep design
- [Excitation Stimuli For Simultaneous Deconvolution of Room Responses](docs/research/AES/Excitation_Stimuli_For_Simultaneous_Deconvolution_of_Room_Responses.pdf) (2023) — Evaluates different excitation stimuli (exponential sweeps, MLS, multi-tones) for room response measurement; recommends exponential sine sweeps for best SNR and distortion separation
- [A New Approach to Impulse Response Measurements at High Sampling Rates](docs/research/AES/A_New_Approach_to_Impulse_Response_Measurements_at_High_Sampling_Rates.pdf) (2014) — Improved impulse response measurement techniques at high sample rates
- [Use of Repetitive Multi-Tone Sequences to Estimate Nonlinear Response of a Loudspeaker to Music](docs/research/AES/Use_of_Repetitive_Multi-Tone_Sequences_to_Estimate_Nonlinear_Response_of_a_Loudspeaker_to_Music.pdf) (2017) — Multi-tone test signals for nonlinear system characterization

## Implementation Approach

- New class `TestSignalGenerator` in `daw-core/…/analysis/`
- Signal types:
  - **Sine sweep**: logarithmic or linear, configurable start/end frequency, duration, and fade-in/out
  - **White noise**: flat power spectral density
  - **Pink noise**: 1/f spectral slope using Voss-McCartney algorithm (pure Java, no lookup tables)
  - **Impulse**: single-sample or windowed Dirac impulse
  - **Multi-tone**: configurable set of simultaneous sinusoids at specified frequencies and levels
  - **Silence**: calibrated digital silence for noise floor measurement
- All generated as `float[]` or `float[][]` (stereo) at configurable sample rate and bit depth
- Applications: room measurement, plugin testing, speaker calibration, signal chain verification

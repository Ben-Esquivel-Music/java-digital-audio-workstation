## Description

Add a hearing loss simulation processor that models common hearing impairments for monitoring purposes. Enables audio engineers to preview how their mixes will sound to listeners with typical age-related hearing loss or noise-induced hearing damage — promoting accessible audio production.

## AES Research References

- [Investigation of a Real-Time Hearing Loss Simulation for use in Audio Production](docs/research/AES/Investigation_of_a_Real-Time_Hearing_Loss_Simulation_for_use_in_Audio_Production.pdf) (2020) — Presents a real-time hearing loss simulator designed specifically for audio production; models audiogram-based hearing loss with frequency-dependent gain reduction, loudness recruitment, and reduced frequency selectivity
- [Developing plugins for your ears](docs/research/AES/Developing_plugins_for_your_ears.pdf) (2021) — Discusses the development of hearing-related audio plugins, including hearing loss awareness tools

## Implementation Approach

- New class `HearingLossSimulator implements AudioProcessor` in `daw-core/…/dsp/`
- Audiogram-based model: configurable hearing threshold per octave band (250 Hz – 8 kHz)
- Frequency-dependent gain reduction using parallel `BiquadFilter` bands
- Loudness recruitment simulation: reduced dynamic range at affected frequencies using per-band compression
- Presets for common hearing profiles: mild high-frequency loss, moderate age-related (presbycusis), noise-induced hearing loss
- Parameters: per-band hearing threshold, recruitment level, broadened auditory filter simulation
- Operates as a monitoring-only insert (bypassed on export)

## Extends

`BiquadFilter`, `CompressorProcessor`, `AudioProcessor`

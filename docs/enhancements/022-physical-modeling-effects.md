# Enhancement: Physical Modeling Audio Effects (Spring Reverb, Leslie Speaker)

## Summary

Implement physically modeled audio effects based on AES research: a spring reverb model incorporating helix angle, damping, and magnetic bead coupling parameters, and a Leslie (rotary speaker) effect with amplitude modulation, frequency modulation, and Doppler simulation. Physical modeling produces more realistic, parameter-rich effects than generic algorithmic approaches while being computationally more efficient than convolution-based methods.

## Motivation

AES research presents detailed physical models for two iconic audio effects: (1) a spring reverb tank that models the actual physical behavior of helical springs including helix angle, damping characteristics, and magnetic bead coupling, and (2) an analog pseudo-Leslie effect with high repeatability that models the amplitude modulation, frequency modulation, and Doppler characteristics of a rotating speaker. These physical modeling approaches produce richer, more authentic effects than standard algorithmic reverb or chorus, and are computationally cheaper than neural network approaches while offering meaningful parameter control tied to physical quantities.

## Research Sources

- [AES Research Papers](../research/aes-research-papers.md) — "Physical Modeling of a Spring Reverb Tank Incorporating Helix Angle, Damping, and Magnetic Bead Coupling" — detailed spring reverb physical model
- [AES Research Papers](../research/aes-research-papers.md) — "Analog Pseudo Leslie Effect with High Grade of Repeatability" — physical modeling of rotary speaker with AM, FM, and Doppler
- [Audio Development Tools](../research/audio-development-tools.md) — "Cloud Seed: Algorithmic reverb for huge, endless spaces — reverb reference"
- [Research README](../research/README.md) — "Physical modeling of spring reverb and Leslie effects offers computationally efficient alternatives to convolution"
- [AES Research Papers](../research/aes-research-papers.md) — "Sound Matching an Analogue Levelling Amplifier Using the Newton-Raphson Method" — differentiable DSP technique for analog emulation

## Sub-Tasks

### Spring Reverb
- [ ] Research and implement the spring reverb dispersive delay line model (frequency-dependent propagation delay)
- [ ] Implement helix angle parameter affecting dispersion characteristics
- [ ] Implement damping model with frequency-dependent loss
- [ ] Implement magnetic bead coupling model for driving and pickup transducer behavior
- [ ] Add user-controllable parameters: spring tension, decay time, damping, mix (dry/wet), pre-delay
- [ ] Create `SpringReverbProcessor` class implementing `AudioProcessor`

### Leslie Speaker Effect
- [ ] Implement amplitude modulation from rotating horn and drum
- [ ] Implement frequency modulation (Doppler effect) from rotational movement
- [ ] Implement speed control: slow (chorale) ↔ fast (tremolo) with acceleration/deceleration modeling
- [ ] Implement horn and drum crossover split (horn handles highs, drum handles lows)
- [ ] Add user-controllable parameters: speed, acceleration, horn/drum balance, distance, mix
- [ ] Create `LeslieProcessor` class implementing `AudioProcessor`

### Shared
- [ ] Implement differentiable DSP optimization for parameter fitting against reference recordings (optional advanced feature)
- [ ] Add unit tests for spring reverb impulse response characteristics (dispersion, decay)
- [ ] Add unit tests for Leslie effect modulation depth and frequency accuracy
- [ ] Add unit tests for parameter range validation and edge cases
- [ ] Document physical model parameters and their relationship to real-world physical quantities

## Affected Modules

- `daw-core` (new `dsp/SpringReverbProcessor`, new `dsp/LeslieProcessor`)
- `daw-sdk` (AudioProcessor interface — already exists, used by new processors)

## Priority

**Medium** — Enriches the effect library; can be implemented incrementally

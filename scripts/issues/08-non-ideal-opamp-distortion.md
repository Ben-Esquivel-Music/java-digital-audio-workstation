## Description

Add a virtual analog distortion processor that models non-ideal operational amplifier behavior — including slew-rate limiting, input offset voltage, and finite open-loop gain. These non-idealities produce the characteristic warmth and soft saturation of analog distortion circuits that pure mathematical waveshaping cannot replicate.

## AES Research References

- [Non-Ideal Operational Amplifier Emulation in Digital Model of Analog Distortion Effect Pedal](docs/research/AES/Non-Ideal_Operational_Amplifier_Emulation_in_Digital_Model_of_Analog_Distortion_Effect_Pedal.pdf) (2022) — Details a digital model of a diode-clipping distortion pedal incorporating non-ideal op-amp characteristics; demonstrates that modeling these imperfections significantly improves perceptual accuracy
- [Sound Matching an Analogue Levelling Amplifier Using the Newton-Raphson Method](docs/research/AES/Sound_Matching_an_Analogue_Levelling_Amplifier_Using_the_Newton-Raphson_Method.pdf) (2025) — Newton-Raphson optimization for matching analog amplifier characteristics; provides efficient parameter estimation techniques

## Implementation Approach

- New class `AnalogDistortionProcessor implements AudioProcessor` in `daw-core/…/dsp/`
- Slew-rate limiting: clamp the sample-to-sample rate of change based on configurable slew rate
- Finite open-loop gain model: frequency-dependent gain rolloff using a first-order lowpass in the feedback path
- Diode-clipper nonlinearity: `sinh`-based approximation of the Shockley diode equation
- Tone control: post-distortion tilt EQ for tonal shaping
- Parameters: drive, tone, slew rate, asymmetry, output level

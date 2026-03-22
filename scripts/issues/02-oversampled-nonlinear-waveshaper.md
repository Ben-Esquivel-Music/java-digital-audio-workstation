## Description

Add a waveshaping distortion/saturation processor with configurable oversampling (2×, 4×, 8×) to suppress aliasing artifacts caused by nonlinear transfer functions. Essential for clean saturation, tape emulation, and distortion effects where aliasing foldback audibly degrades quality.

## AES Research References

- [Oversampling for Nonlinear Waveshaping: Choosing the Right Filters](docs/research/AES/Oversampling_for_Nonlinear_Waveshaping__Choosing_the_Right_Filters.pdf) (2019) — Evaluates anti-aliasing filter designs (IIR elliptic, FIR half-band polyphase) for oversampled waveshaping; recommends minimum-phase IIR for lowest latency and FIR half-band polyphase for best rejection
- [Antiderivative Antialiasing Techniques in Nonlinear Wave Digital Structures](docs/research/AES/Antiderivative_Antialiasing_Techniques_in_Nonlinear_Wave_Digital_Structures.pdf) (2021) — Alternative antialiasing technique that can complement or replace oversampling

## Implementation Approach

- New class `WaveshaperProcessor implements AudioProcessor` in `daw-core/…/dsp/`
- Built-in transfer functions: soft-clip (tanh), hard-clip, tube saturation, tape saturation
- Polyphase FIR half-band upsampler/downsampler pair for 2× oversampling, cascadable for 4×/8×
- Configurable drive, mix (wet/dry), and output gain
- Uses `DspUtils` for coefficient computation

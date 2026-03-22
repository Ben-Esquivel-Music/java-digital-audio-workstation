# AES Research-Driven Feature Enhancements

> Feature enhancement issues derived from analysis of 345 AES research papers in `docs/research/AES/`.
> All enhancements are **pure-Java** implementations requiring no external dependencies.
> Where native code is unavoidable, the **Foreign Function & Memory (FFM) API** (JEP 454) is recommended instead of JNI.
>
> See also: [AES Research Papers Analysis](aes-research-papers.md) · [AES PDF Catalog by Decade](aes-pdf-catalog.md)

---

## Issue Index

| # | Category | Title | Priority |
|---|----------|-------|----------|
| 1 | DSP | [Graphic Equalizer Processor (Octave / Third-Octave)](#1-graphic-equalizer-processor-octave--third-octave) | High |
| 2 | DSP | [Oversampled Nonlinear Waveshaper](#2-oversampled-nonlinear-waveshaper) | High |
| 3 | DSP | [Antiderivative Antialiasing for Distortion Effects](#3-antiderivative-antialiasing-for-distortion-effects) | Medium |
| 4 | DSP | [Velvet-Noise Reverb Processor](#4-velvet-noise-reverb-processor) | High |
| 5 | DSP | [Directional Feedback Delay Network Reverb](#5-directional-feedback-delay-network-reverb) | Medium |
| 6 | DSP | [Perceptual Bass Extension Processor](#6-perceptual-bass-extension-processor) | Medium |
| 7 | DSP | [Air Absorption Filter for Distance Modeling](#7-air-absorption-filter-for-distance-modeling) | Medium |
| 8 | DSP | [Non-Ideal Op-Amp Distortion Model](#8-non-ideal-op-amp-distortion-model) | Low |
| 9 | DSP | [Audio Peak Reduction via Chirp Spreading](#9-audio-peak-reduction-via-chirp-spreading) | Medium |
| 10 | Analysis | [Sines / Transients / Noise Decomposition](#10-sines--transients--noise-decomposition) | High |
| 11 | Analysis | [Phase Alignment and Polarity Detection](#11-phase-alignment-and-polarity-detection) | High |
| 12 | Analysis | [Lossless Audio Integrity Checker](#12-lossless-audio-integrity-checker) | Medium |
| 13 | Analysis | [Lossy Compression Artifact Detection](#13-lossy-compression-artifact-detection) | Medium |
| 14 | Analysis | [Multitrack Mix Feature Analysis](#14-multitrack-mix-feature-analysis) | Medium |
| 15 | Analysis | [Fractional-Octave Spectrum Smoothing](#15-fractional-octave-spectrum-smoothing) | Medium |
| 16 | Analysis | [Coherence-Based Distortion Indicator](#16-coherence-based-distortion-indicator) | Low |
| 17 | Analysis | [Transient Detection for Adaptive Block Switching](#17-transient-detection-for-adaptive-block-switching) | Medium |
| 18 | Spatial | [Binaural Externalization Processing](#18-binaural-externalization-processing) | High |
| 19 | Spatial | [Stereo-to-Binaural Conversion](#19-stereo-to-binaural-conversion) | High |
| 20 | Spatial | [2D-to-3D Ambience Upmixer](#20-2d-to-3d-ambience-upmixer) | Medium |
| 21 | Spatial | [Ambisonic Enhancement via Time-Frequency Masking](#21-ambisonic-enhancement-via-time-frequency-masking) | Medium |
| 22 | Spatial | [Spatial Room Impulse Response Tail Resynthesis](#22-spatial-room-impulse-response-tail-resynthesis) | Low |
| 23 | Spatial | [Panning Table Synthesis for Irregular Speaker Layouts](#23-panning-table-synthesis-for-irregular-speaker-layouts) | Medium |
| 24 | Spatial | [Stereo-to-Mono Down-Mix Optimizer](#24-stereo-to-mono-down-mix-optimizer) | Medium |
| 25 | Utility | [Audio Test Signal Generator Suite](#25-audio-test-signal-generator-suite) | Medium |
| 26 | Utility | [Hearing Loss Simulation for Accessible Monitoring](#26-hearing-loss-simulation-for-accessible-monitoring) | Low |
| 27 | Mastering | [Intelligent Gap Filling / Bandwidth Extension](#27-intelligent-gap-filling--bandwidth-extension) | Low |

---

## DSP & Effects

### 1. Graphic Equalizer Processor (Octave / Third-Octave)

**Category:** DSP · **Priority:** High · **Pure Java:** Yes

**Description:**
Add a graphic equalizer processor supporting octave and third-octave band configurations with optional linear-phase mode. Unlike the existing `ParametricEqProcessor` which provides fully configurable parametric bands, a graphic EQ offers fixed-frequency band sliders familiar to audio engineers for broad tonal shaping — particularly useful for live monitoring correction and quick tonal adjustments.

**AES Research References:**
- [Linear-Phase Octave Graphic Equalizer](AES/Linear-Phase_Octave_Graphic_Equalizer.pdf) (2022) — Presents a linear-phase graphic EQ design using symmetric FIR filters, achieving flat-sum magnitude response across octave bands without phase distortion
- [Design of a Digitally Controlled Graphic Equalizer](AES/Design_of_a_Digitally_Controlled_Graphic_Equalizer.pdf) (2017) — Details minimum-phase graphic EQ design with optimal band interaction characteristics
- [Parametric Equalization](AES/Parametric_Equalization.pdf) (2012) — Foundational reference for filter design applicable to both parametric and graphic implementations

**Implementation Approach:**
- New class `GraphicEqProcessor implements AudioProcessor` in `daw-core/…/dsp/`
- Fixed ISO octave center frequencies (31.5 Hz – 16 kHz for octave, 25 Hz – 20 kHz for third-octave)
- Each band backed by a biquad peak filter from the existing `BiquadFilter` (minimum-phase mode)
- Linear-phase mode converts the combined biquad response to a symmetric FIR via the existing `LinearPhaseFilter`
- Gain range: ±12 dB per band with configurable Q/bandwidth

**Extends:** `BiquadFilter`, `LinearPhaseFilter`, `AudioProcessor`

---

### 2. Oversampled Nonlinear Waveshaper

**Category:** DSP · **Priority:** High · **Pure Java:** Yes

**Description:**
Add a waveshaping distortion/saturation processor with configurable oversampling (2×, 4×, 8×) to suppress aliasing artifacts caused by nonlinear transfer functions. Essential for clean saturation, tape emulation, and distortion effects where aliasing foldback audibly degrades quality.

**AES Research References:**
- [Oversampling for Nonlinear Waveshaping: Choosing the Right Filters](AES/Oversampling_for_Nonlinear_Waveshaping__Choosing_the_Right_Filters.pdf) (2019) — Evaluates anti-aliasing filter designs (IIR elliptic, FIR half-band polyphase) for oversampled waveshaping; recommends minimum-phase IIR for lowest latency and FIR half-band polyphase for best rejection
- [Antiderivative Antialiasing Techniques in Nonlinear Wave Digital Structures](AES/Antiderivative_Antialiasing_Techniques_in_Nonlinear_Wave_Digital_Structures.pdf) (2021) — Alternative antialiasing technique that can complement or replace oversampling

**Implementation Approach:**
- New class `WaveshaperProcessor implements AudioProcessor` in `daw-core/…/dsp/`
- Built-in transfer functions: soft-clip (tanh), hard-clip, tube saturation, tape saturation
- Polyphase FIR half-band upsampler/downsampler pair for 2× oversampling, cascadable for 4×/8×
- Configurable drive, mix (wet/dry), and output gain
- Uses `DspUtils` for coefficient computation

---

### 3. Antiderivative Antialiasing for Distortion Effects

**Category:** DSP · **Priority:** Medium · **Pure Java:** Yes

**Description:**
Implement first-order antiderivative antialiasing (ADAA) as an alternative to oversampling for alias suppression in nonlinear processors. ADAA computes the antiderivative of the waveshaping function and uses finite differences to approximate the band-limited output — achieving effective alias reduction at zero additional oversampling cost.

**AES Research References:**
- [Antiderivative Antialiasing Techniques in Nonlinear Wave Digital Structures](AES/Antiderivative_Antialiasing_Techniques_in_Nonlinear_Wave_Digital_Structures.pdf) (2021) — Extends ADAA to wave digital filter (WDF) structures, demonstrating first-order and second-order ADAA for diode clippers and guitar distortion circuits
- [Intermodulation Distortion Analysis of a Guitar Distortion Pedal With a Starving Circuit](AES/Intermodulation_Distortion_Analysis_of_a_Guitar_Distortion_Pedal_With_a_Starving_Circuit.pdf) (2021) — Intermodulation analysis of guitar distortion circuits relevant to antialiasing requirements

**Implementation Approach:**
- New utility class `AdaaWaveshaper` in `daw-core/…/dsp/`
- Computes antiderivatives analytically for common transfer functions (tanh, hard-clip, soft-clip)
- First-order ADAA: `y[n] = (F(x[n]) - F(x[n-1])) / (x[n] - x[n-1])` where F is the antiderivative
- Handles the ill-conditioned case `x[n] ≈ x[n-1]` with L'Hôpital fallback
- Can be combined with the `WaveshaperProcessor` as a zero-latency alternative to oversampling

---

### 4. Velvet-Noise Reverb Processor

**Category:** DSP · **Priority:** High · **Pure Java:** Yes

**Description:**
Add an efficient reverb processor based on velvet-noise sequences. Velvet noise is a sparse random signal with values restricted to {−1, 0, +1}, enabling convolution via simple additions and subtractions instead of multiplications. This yields a reverb with the perceptual quality of convolution reverb at a fraction of the computational cost — critical for real-time use with virtual threads.

**AES Research References:**
- [Efficient Velvet-Noise Convolution in Multicore Processors](AES/Efficient_Velvet-Noise_Convolution_in_Multicore_Processors.pdf) (2024) — Presents parallelized velvet-noise convolution achieving significant speedup via segment-based multicore processing; directly applicable to Java virtual threads
- [Computationally-Efficient Simulation of Late Reverberation for Inhomogeneous Boundary Conditions and Coupled Rooms](AES/Computationally-Efficient_Simulation_of_Late_Reverberation_for_Inhomogeneous_Boundary_Conditions_and_Coupled_Rooms.pdf) (2023) — Efficient late reverberation techniques complementary to velvet-noise approaches
- [The Effects of Reverberation on the Emotional Characteristics of Musical Instruments](AES/The_Effects_of_Reverberation_on_the_Emotional_Characteristics_of_Musical_Instruments.pdf) (2015) — Perceptual validation that reverb characteristics significantly affect perceived instrument quality

**Implementation Approach:**
- New class `VelvetNoiseReverbProcessor implements AudioProcessor` in `daw-core/…/dsp/`
- Generate velvet-noise sequences with configurable density (pulses per second)
- Sparse convolution: only process non-zero impulse positions (additions/subtractions, no multiplications)
- Parallelizable segment processing using `Executors.newVirtualThreadPerTaskExecutor()` for late reverb tail
- Parameters: decay time, density, early/late mix, damping
- Complements the existing `ReverbProcessor` (Schroeder-Moorer) and `SpringReverbProcessor` as a third reverb algorithm

---

### 5. Directional Feedback Delay Network Reverb

**Category:** DSP · **Priority:** Medium · **Pure Java:** Yes

**Description:**
Extend the existing `FdnRoomSimulator` with directional output capabilities. A directional FDN assigns spatial direction to each delay line output, enabling reverb that preserves the spatial distribution of reflections — essential for immersive audio production where reverb should surround the listener rather than collapse to a single point.

**AES Research References:**
- [Directional Feedback Delay Network](AES/Directional_Feedback_Delay_Network.pdf) (2019) — Proposes assigning directional weights to FDN delay-line outputs and rendering them via Ambisonics encoding, producing spatially distributed reverberation
- [Designing Directional Reverberators for Spatial Sound Reproduction](AES/Designing_Directional_Reverberators_for_Spatial_Sound_Reproduction.pdf) (2024) — Methods for designing reverberators that produce direction-dependent decay and spectral characteristics
- [Object-Based Reverberation for Spatial Audio](AES/Object-Based_Reverberation_for_Spatial_Audio.pdf) (2017) — Framework for treating reverb as a spatial object with position and spread metadata

**Implementation Approach:**
- New class `DirectionalFdnProcessor` in `daw-core/…/spatial/room/`
- Each of the N FDN delay-line outputs is assigned a spherical direction (azimuth, elevation)
- Output is encoded to first-order Ambisonics (W, X, Y, Z) using the existing `AmbisonicEncoder`
- Directional diffusion: late reflections arrive from distributed directions, not a single point
- Compatible with the existing `FdnRoomSimulator` architecture (Householder matrix, allpass diffusers)
- Parameters: room size, decay, damping, directional spread

**Extends:** `FdnRoomSimulator`, `AmbisonicEncoder`

---

### 6. Perceptual Bass Extension Processor

**Category:** DSP · **Priority:** Medium · **Pure Java:** Yes

**Description:**
Add a psychoacoustic bass enhancement processor that generates harmonics of low-frequency content to create the perception of bass on playback systems with limited low-frequency reproduction. Uses the psychoacoustic "missing fundamental" effect — when harmonics of a fundamental are present, the brain perceives the fundamental even when it is absent.

**AES Research References:**
- [Advances in Perceptual Bass Extension for Music and Cinematic Content](AES/Advances_in_Perceptual_Bass_Extension_for_Music_and_Cinematic_Content.pdf) (2023) — State-of-the-art perceptual bass extension algorithms for music and cinema; evaluates harmonic generation techniques and crossover strategies
- [Physiological measurement of the arousing effect of bass amplification in music](AES/Physiological_measurement_of_the_arousing_effect_of_bass_amplification_in_music.pdf) (2024) — Physiological evidence that bass presence affects listener arousal; supports the value of bass enhancement features

**Implementation Approach:**
- New class `BassExtensionProcessor implements AudioProcessor` in `daw-core/…/dsp/`
- Low-frequency isolation via `BiquadFilter` lowpass/bandpass at configurable crossover (40–120 Hz)
- Harmonic generation: half-wave rectification or polynomial waveshaping of the isolated bass signal to generate 2nd, 3rd, and 4th harmonics
- Bandpass filtering of generated harmonics to suppress sub-harmonic artifacts and high-order distortion
- Mixing harmonics back with the original signal at configurable level
- Parameters: crossover frequency, harmonic order, harmonic level, dry/wet mix

**Extends:** `BiquadFilter`, `AudioProcessor`

---

### 7. Air Absorption Filter for Distance Modeling

**Category:** DSP · **Priority:** Medium · **Pure Java:** Yes

**Description:**
Add a frequency-dependent air absorption filter that models the high-frequency attenuation of sound traveling through air. This is essential for realistic distance rendering in spatial audio — distant sources should sound progressively duller as high frequencies are absorbed by the atmosphere.

**AES Research References:**
- [Digital Filter for Modeling Air Absorption in Real Time](AES/Digital_Filter_for_Modeling_Air_Absorption_in_Real_Time.pdf) (2013) — Presents efficient IIR filter designs that accurately model frequency-dependent atmospheric absorption as a function of distance, temperature, and humidity
- [A General Overview of Methods for Generating Room Impulse Responses](AES/A_General_Overview_of_Methods_for_Generating_Room_Impulse_Responses.pdf) (2024) — Includes air absorption as a component of physically-based room impulse response generation

**Implementation Approach:**
- New class `AirAbsorptionFilter` in `daw-core/…/spatial/`
- Models ISO 9613-1 atmospheric absorption coefficients as a function of distance, temperature (°C), and relative humidity (%)
- Implemented as a cascade of low-order IIR filters with coefficients derived from the ISO standard absorption curves
- Distance parameter controls filter cutoff — increasing distance progressively rolls off high frequencies
- Integrates with the existing `InverseSquareAttenuation` distance model for complete distance rendering

**Extends:** `BiquadFilter`, `InverseSquareAttenuation`

---

### 8. Non-Ideal Op-Amp Distortion Model

**Category:** DSP · **Priority:** Low · **Pure Java:** Yes

**Description:**
Add a virtual analog distortion processor that models non-ideal operational amplifier behavior — including slew-rate limiting, input offset voltage, and finite open-loop gain. These non-idealities produce the characteristic warmth and soft saturation of analog distortion circuits that pure mathematical waveshaping cannot replicate.

**AES Research References:**
- [Non-Ideal Operational Amplifier Emulation in Digital Model of Analog Distortion Effect Pedal](AES/Non-Ideal_Operational_Amplifier_Emulation_in_Digital_Model_of_Analog_Distortion_Effect_Pedal.pdf) (2022) — Details a digital model of a diode-clipping distortion pedal incorporating non-ideal op-amp characteristics; demonstrates that modeling these imperfections significantly improves perceptual accuracy
- [Sound Matching an Analogue Levelling Amplifier Using the Newton-Raphson Method](AES/Sound_Matching_an_Analogue_Levelling_Amplifier_Using_the_Newton-Raphson_Method.pdf) (2025) — Newton-Raphson optimization for matching analog amplifier characteristics; provides efficient parameter estimation techniques

**Implementation Approach:**
- New class `AnalogDistortionProcessor implements AudioProcessor` in `daw-core/…/dsp/`
- Slew-rate limiting: clamp the sample-to-sample rate of change based on configurable slew rate
- Finite open-loop gain model: frequency-dependent gain rolloff using a first-order lowpass in the feedback path
- Diode-clipper nonlinearity: `sinh`-based approximation of the Shockley diode equation
- Tone control: post-distortion tilt EQ for tonal shaping
- Parameters: drive, tone, slew rate, asymmetry, output level

---

### 9. Audio Peak Reduction via Chirp Spreading

**Category:** DSP · **Priority:** Medium · **Pure Java:** Yes

**Description:**
Add a peak reduction processor that reduces crest factor (peak-to-average ratio) by spreading signal peaks across time using ultra-short chirp modulation. Unlike traditional limiting which clips or compresses peaks, chirp spreading redistributes peak energy without audible artifacts — enabling louder masters without distortion.

**AES Research References:**
- [Audio Peak Reduction Using Ultra-Short Chirps](AES/Audio_Peak_Reduction_Using_Ultra-Short_Chirps.pdf) (2022) — Presents a method for reducing audio signal peaks by convolving with ultra-short chirps; demonstrates measurable crest factor reduction with inaudible artifacts

**Implementation Approach:**
- New class `ChirpPeakReducer implements AudioProcessor` in `daw-core/…/dsp/`
- Detect peaks above a configurable threshold using envelope following
- Apply ultra-short chirp convolution (1–5 ms) to spread peak energy temporally
- Configurable chirp bandwidth and duration
- Operates before the limiter in the mastering chain for transparent loudness maximization
- Parameters: threshold, chirp duration, chirp bandwidth, mix

**Extends:** `AudioProcessor`, complements `LimiterProcessor`

---

## Analysis & Metering

### 10. Sines / Transients / Noise Decomposition

**Category:** Analysis · **Priority:** High · **Pure Java:** Yes

**Description:**
Add a spectral decomposition engine that separates an audio signal into three components: sinusoidal (tonal), transient (percussive), and noise (stochastic). This three-way decomposition enables advanced editing workflows — independently processing or visualizing the tonal, rhythmic, and noise content of a signal.

**AES Research References:**
- [Enhanced Fuzzy Decomposition of Sound Into Sines, Transients, and Noise](AES/Enhanced_Fuzzy_Decomposition_of_Sound_Into_Sines,_Transients,_and_Noise.pdf) (2023) — Proposes a fuzzy STN decomposition using median filtering of spectrograms with soft masking for artifact-free separation; the "enhanced" variant improves temporal resolution for transients while preserving tonal continuity
- [Transient Detection Methods for Audio Coding](AES/Transient_Detection_Methods_for_Audio_Coding.pdf) (2023) — Complements STN decomposition with specialized transient detection for block-switching decisions

**Implementation Approach:**
- New class `StnDecomposer` in `daw-core/…/analysis/`
- STFT analysis using the existing `FftUtils` with configurable window size and overlap
- Horizontal (time) median filtering of the spectrogram to extract tonal component
- Vertical (frequency) median filtering to extract transient component
- Residual (original − tonal − transient) yields the noise component
- Soft (fuzzy) masking using Wiener-type gain functions to avoid binary artifacts
- Returns three separate `float[][]` buffers for sines, transients, and noise
- Applications: transient shaping, de-noising, harmonic editing, visualization

**Extends:** `FftUtils`

---

### 11. Phase Alignment and Polarity Detection

**Category:** Analysis · **Priority:** High · **Pure Java:** Yes

**Description:**
Add an automated phase alignment and polarity detection tool for multitrack sessions. Phase issues between microphones recording the same source (e.g., drum kit, stereo miking) cause comb-filtering and tonal degradation. This tool detects inter-track time offsets and polarity inversions, and recommends corrections.

**AES Research References:**
- [Detection of phase alignment and polarity in drum tracks](AES/Detection_of_phase_alignment_and_polarity_in_drum_tracks.pdf) (2023) — Presents algorithms for automated detection of phase misalignment and polarity inversion in multi-microphone drum recordings using cross-correlation and spectral coherence
- [Spectral and spatial perceptions of comb-filtering for sound reinforcement applications](AES/Spectral_and_spatial_perceptions_of_comb-filtering_for_sound_reinforcement_applications..pdf) (2022) — Perceptual consequences of phase/comb-filtering artifacts; validates the importance of phase alignment tools

**Implementation Approach:**
- New class `PhaseAlignmentAnalyzer` in `daw-core/…/analysis/`
- Cross-correlation between track pairs to find the optimal time offset (in samples)
- Polarity detection: compare cross-correlation peak at offset 0 vs. inverted signal
- Spectral coherence measurement to quantify the severity of phase cancellation per frequency band
- Returns per-pair results: optimal delay (samples), polarity recommendation (normal/inverted), coherence score
- Integration with `CorrelationMeter` for real-time display
- Applications: drum overhead alignment, stereo mic correction, DI/amp phase matching

**Extends:** `CorrelationMeter`, `FftUtils`

---

### 12. Lossless Audio Integrity Checker

**Category:** Analysis · **Priority:** Medium · **Pure Java:** Yes

**Description:**
Add an analysis tool that detects whether a supposedly lossless audio file (WAV, FLAC) has been upconverted from a lossy source (MP3, AAC) or upsampled from a lower sample rate. This is essential for mastering engineers who need to verify source material integrity before processing.

**AES Research References:**
- [Lossless Audio Checker: A Software for the Detection of Upscaling, Upsampling, and Transcoding in Lossless Musical Tracks](AES/Lossless_Audio_Checker__A_Software_for_the_Detection_of_Upscaling,_Upsampling,_and_Transcoding_in_Lossless_Musical_Tracks.pdf) (2015) — Presents detection algorithms for identifying upscaled, upsampled, and transcoded audio using spectral analysis; detects the characteristic spectral cutoff of lossy codecs and the spectral gaps from upsampling

**Implementation Approach:**
- New class `LosslessIntegrityChecker` in `daw-core/…/analysis/`
- Spectral cutoff detection: identify sharp high-frequency rolloff characteristic of lossy codecs (e.g., MP3 ~16 kHz, AAC ~18 kHz)
- Upsampling detection: detect mirrored spectral content or null energy above the original Nyquist frequency
- Bit-depth analysis: detect if lower bits are zero-padded (indicating upscaling from lower bit depth)
- Returns a report: likely original format, detected cutoff frequency, confidence score
- Uses the existing `FftUtils` and `SpectrumAnalyzer`

**Extends:** `FftUtils`, `SpectrumAnalyzer`

---

### 13. Lossy Compression Artifact Detection

**Category:** Analysis · **Priority:** Medium · **Pure Java:** Yes

**Description:**
Add analysis capabilities to detect and classify lossy compression artifacts in audio files. This tool identifies the telltale statistical signatures of MP3/AAC encoding — complementing the lossless integrity checker with more detailed forensic analysis.

**AES Research References:**
- [MP3 compression classification through audio analysis statistics](AES/MP3_compression_classification_through_audio_analysis_statistics.pdf) (2022) — Develops statistical features for classifying MP3 compression levels from audio analysis; identifies spectral band energy ratios and temporal envelope characteristics unique to different MP3 bitrates
- [Comparing the Effect of Audio Coding Artifacts on Objective Quality Measures and on Subjective Ratings](AES/Comparing_the_Effect_of_Audio_Coding_Artifacts_on_Objective_Quality_Measures_and_on_Subjective_Ratings.pdf) (2018) — Correlates objective measurements with perceived quality degradation from coding artifacts

**Implementation Approach:**
- New class `CompressionArtifactDetector` in `daw-core/…/analysis/`
- Spectral band energy ratio analysis across critical bands
- Pre-echo detection in the time domain (characteristic of block-based codecs)
- "Birdie" artifact detection: isolated narrowband tonal artifacts from codec quantization
- Statistical classification of probable encoding format and bitrate
- Returns: detected codec type, estimated bitrate, artifact locations (time/frequency), severity score
- Uses existing `FftUtils` and `SpectrumAnalyzer`

**Extends:** `FftUtils`, `SpectrumAnalyzer`

---

### 14. Multitrack Mix Feature Analysis

**Category:** Analysis · **Priority:** Medium · **Pure Java:** Yes

**Description:**
Add a multitrack analysis engine that extracts low-level audio features from each track in a session and provides statistical summary across the mix. Enables comparison of mix characteristics across different versions or against reference mixes — supporting "how does my mix compare?" workflows.

**AES Research References:**
- [Variation in Multitrack Mixes: Analysis of Low-level Audio Signal Features](AES/Variation_in_Multitrack_Mixes__Analysis_of_Low-level_Audio_Signal_Features.pdf) (2016) — Defines a set of low-level audio features for characterizing multitrack mixes: spectral centroid, spectral flux, RMS level, crest factor, spectral spread, and stereo width; applies these to analyze variation across multiple mixes of the same song
- [Exploring trends in audio mixes and masters: Insights from a dataset analysis](AES/Exploring_trends_in_audio_mixes_and_masters__Insights_from_a_dataset_analysis.pdf) (2024) — Large-scale analysis of mix/master trends using audio features; provides benchmarks for feature values across genres

**Implementation Approach:**
- New class `MixFeatureAnalyzer` in `daw-core/…/analysis/`
- Per-track feature extraction: RMS level, peak level, crest factor, spectral centroid, spectral spread, spectral flux, stereo width, loudness (LUFS)
- Session-level aggregate statistics: feature distributions, per-band energy ratios, dynamic range
- Comparison mode: compare two mixes feature-by-feature with delta reporting
- Leverages existing `SpectrumAnalyzer`, `LevelMeter`, `LoudnessMeter`, `CorrelationMeter`
- Returns structured `MixFeatureReport` record with per-track and aggregate metrics

**Extends:** `SpectrumAnalyzer`, `LevelMeter`, `LoudnessMeter`, `CorrelationMeter`

---

### 15. Fractional-Octave Spectrum Smoothing

**Category:** Analysis · **Priority:** Medium · **Pure Java:** Yes

**Description:**
Enhance the `SpectrumAnalyzer` with log-frequency-symmetric fractional-octave smoothing. Standard rectangular or triangular smoothing in the linear frequency domain produces asymmetric smoothing on log-frequency displays, over-smoothing high frequencies relative to low frequencies. This enhancement preserves visual accuracy of spectral displays.

**AES Research References:**
- [A Generalized Method for Fractional-Octave Smoothing of Transfer Functions that Preserves Log-Frequency Symmetry](AES/A_Generalized_Method_for_Fractional-Octave_Smoothing_of_Transfer_Functions_that_Preserves_Log-Frequency_Symmetry.pdf) (2017) — Presents a generalized method using variable-width rectangular or Gaussian windows that scale with frequency, producing visually symmetric smoothing on log-frequency axes; applicable to 1/3-octave, 1/6-octave, and arbitrary fractional-octave widths

**Implementation Approach:**
- New method `smoothFractionalOctave(double[] magnitudes, double sampleRate, double octaveFraction)` in `SpectrumAnalyzer` or new utility class `SpectrumSmoother`
- Variable-width smoothing window that scales proportionally to center frequency
- Supports 1/1, 1/3, 1/6, 1/12, 1/24 octave smoothing
- Operates on magnitude spectrum in dB domain for correct log-frequency behavior
- Preserves existing raw spectrum output; smoothing is an optional post-processing step

**Extends:** `SpectrumAnalyzer`, `FftUtils`

---

### 16. Coherence-Based Distortion Indicator

**Category:** Analysis · **Priority:** Low · **Pure Java:** Yes

**Description:**
Add a coherence function measurement between input and output of a signal chain to detect and quantify nonlinear distortion. Coherence drops below 1.0 at frequencies where distortion is present — providing a frequency-dependent distortion map that is more informative than scalar THD measurements.

**AES Research References:**
- [Coherence as an Indicator of Distortion for Wide-Band Audio Signals such as M-Noise and Music](AES/Coherence_as_an_Indicator_of_Distortion_for_Wide-Band_Audio_Signals_such_as_M-Noise_and_Music.pdf) (2019) — Demonstrates that magnitude-squared coherence between input and output provides a reliable, frequency-dependent distortion indicator for wideband signals including music; superior to traditional THD+N for non-stationary signals

**Implementation Approach:**
- New class `CoherenceAnalyzer` in `daw-core/…/analysis/`
- Welch's method: average cross-spectral density and auto-spectral densities over overlapping segments
- Magnitude-squared coherence: `γ²(f) = |Sxy(f)|² / (Sxx(f) · Syy(f))`
- Requires paired input/output buffers (pre/post effect chain)
- Returns per-frequency coherence values (0.0 = fully distorted, 1.0 = perfectly linear)
- Applications: effect chain quality assessment, master bus distortion monitoring

**Extends:** `FftUtils`

---

### 17. Transient Detection for Adaptive Block Switching

**Category:** Analysis · **Priority:** Medium · **Pure Java:** Yes

**Description:**
Add a specialized transient detector optimized for real-time block-switching decisions in the audio engine. When a transient is detected, the engine can switch to shorter processing blocks to improve temporal resolution — reducing pre-echo and smearing artifacts in time-frequency processing.

**AES Research References:**
- [Transient Detection Methods for Audio Coding](AES/Transient_Detection_Methods_for_Audio_Coding.pdf) (2023) — Evaluates multiple transient detection methods (spectral flux, temporal energy ratio, attack time analysis) for audio coding block-switching; recommends combined spectral-temporal approaches for lowest missed-detection rate
- [Real-Time Audio Pattern Detection for Smart Musical Instruments](AES/Real-Time_Audio_Pattern_Detection_for_Smart_Musical_Instruments.pdf) (2026) — Real-time audio pattern detection applicable to live input monitoring and adaptive processing

**Implementation Approach:**
- New class `TransientDetector` in `daw-core/…/analysis/`
- Dual-domain detection: temporal energy ratio (short/long window energy ratio) combined with spectral flux
- Low-latency design: operates on individual audio blocks without look-ahead
- Binary output: transient detected (switch to short block) or not (continue with long block)
- Configurable sensitivity threshold
- Complements the existing `OnsetDetector` which is designed for offline analysis; this is optimized for real-time block-by-block decisions

**Extends:** `OnsetDetector` (shared algorithms), `FftUtils`

---

## Spatial Audio

### 18. Binaural Externalization Processing

**Category:** Spatial · **Priority:** High · **Pure Java:** Yes

**Description:**
Add a binaural externalization processor that improves the perceived spatial quality of headphone monitoring. Binaural audio often suffers from in-head localization (sound appears inside the head rather than outside). Externalization processing adds subtle early reflections and HRTF-based crosstalk to move the perceived sound image outside the listener's head.

**AES Research References:**
- [Binaural Externalization Processing - from Stereo to Object-Based Audio](AES/Binaural_Externalization_Processing_-_from_Stereo_to_Object-Based_Audio.pdf) (2022) — Comprehensive externalization processing framework applicable from stereo to object-based audio; details early reflection synthesis, HRTF filtering, and crossfeed techniques for improved headphone monitoring
- [Spectral and Spatial Discrepancies Between Stereo and Binaural Spatial Masters in Headphone Playback: A Perceptual and Technical Analysis](AES/Spectral_and_Spatial_Discrepancies_Between_Stereo_and_Binaural_Spatial_Masters_in_Headphone_Playback__A_Perceptual_and_Technical_Analysis.pdf) (2025) — Documents measurable spectral and spatial differences between stereo and binaural masters on headphones; informs the corrections that externalization must apply
- [On the Differences in Preferred Headphone Response for Spatial and Stereo Content](AES/On_the_Differences_in_Preferred_Headphone_Response_for_Spatial_and_Stereo_Content.pdf) (2022) — Headphone response preferences differ for spatial vs. stereo content, informing target curves for externalized monitoring

**Implementation Approach:**
- New class `BinauralExternalizationProcessor` in `daw-core/…/spatial/binaural/`
- Crossfeed filter: frequency-dependent inter-channel bleed with ITD delay (simulating loudspeaker crosstalk)
- Early reflection synthesis: 2–4 short delays with directional HRTF filters using existing `HrtfInterpolator`
- Room coloration: subtle low-order FDN reverb (reusing `FdnRoomSimulator` components) to add environmental context
- Parameters: crossfeed level, room size, externalization amount, headphone compensation EQ
- Integrates with the existing `DefaultBinauralRenderer` monitoring path

**Extends:** `DefaultBinauralRenderer`, `HrtfInterpolator`, `BiquadFilter`

---

### 19. Stereo-to-Binaural Conversion

**Category:** Spatial · **Priority:** High · **Pure Java:** Yes

**Description:**
Add a processor that converts standard stereo mixes into binaural audio optimized for headphone playback. This enables monitoring stereo mixes on headphones with a more speaker-like spatial presentation, and also enables converting existing stereo content to binaural for immersive distribution.

**AES Research References:**
- [An Efficient Method for Producing Binaural Mixes of Classical Music from a Primary Stereo Mix](AES/An_Efficient_Method_for_Producing_Binaural_Mixes_of_Classical_Music_from_a_Primary_Stereo_Mix.pdf) (2018) — Efficient stereo-to-binaural conversion using HRTF-filtered virtual speakers at ±30° with optional early reflections; validates perceptual equivalence to dedicated binaural recordings
- [Digital Signal Processing Issues in the Context of Binaural and Transaural Stereophony](AES/Digital_Signal_Processing_Issues_in_the_Context_of_Binaural_and_Transaural_Stereophony.pdf) (1995) — Foundational DSP framework for binaural/transaural conversion covering HRTF convolution, crosstalk cancellation, and head-tracking compensation

**Implementation Approach:**
- New class `StereoToBinauralConverter` in `daw-core/…/spatial/binaural/`
- Virtual speaker positioning: place left and right channels at configurable azimuth (default ±30°) using HRTF from existing `SofaFileParser`/`HrtfInterpolator`
- HRTF convolution using the existing `PartitionedConvolver` for zero-latency processing
- Optional early reflection modeling (3–5 reflections) for enhanced externalization
- Optional room simulation via `FdnRoomSimulator` for ambient tail
- Parameters: speaker azimuth, distance, room influence, early reflections on/off
- Output: binaural stereo suitable for headphone playback

**Extends:** `HrtfInterpolator`, `PartitionedConvolver`, `SofaFileParser`

---

### 20. 2D-to-3D Ambience Upmixer

**Category:** Spatial · **Priority:** Medium · **Pure Java:** Yes

**Description:**
Add an upmixer that extracts the ambient/diffuse component from a stereo or surround signal and spatially distributes it into height channels, creating an immersive 3D sound field from 2D source material. This enables existing stereo content to be expanded into immersive formats like 7.1.4 Atmos beds.

**AES Research References:**
- [2D-to-3D Ambience Upmixing based on Perceptual Band Allocation](AES/2D-to-3D_Ambience_Upmixing_based_on_Perceptual_Band_Allocation.pdf) (2015) — Proposes perceptual band allocation (PBA) for distributing ambient content to height channels based on psychoacoustic criteria; allocates frequency bands to overhead speakers where they contribute most to perceived envelopment
- [Perceptual Band Allocation (PBA) for the Rendering of Vertical Image Spread with a Vertical 2D Loudspeaker Array](AES/Perceptual_Band_Allocation_%28PBA%29_for_the_Rendering_of_Vertical_Image_Spread_with_a_Vertical_2D_Loudspeaker_Array.pdf) (2016) — Extends PBA theory for vertical spread rendering; applicable to height channel distribution
- [The Effect of Temporal and Directional Density on Listener Envelopment](AES/The_Effect_of_Temporal_and_Directional_Density_on_Listener_Envelopment.pdf) (2023) — Demonstrates that distributing reflections across more directions increases perceived envelopment

**Implementation Approach:**
- New class `AmbienceUpmixer` in `daw-core/…/spatial/`
- Direct/ambient separation using mid-side decomposition and decorrelation analysis
- Perceptual band allocation: divide ambient signal into frequency bands using `CrossoverFilter`
- Assign bands to height channels based on PBA criteria (higher frequencies to overhead for maximum envelopment)
- Decorrelation of assigned bands to prevent spatial collapse (allpass-based decorrelators)
- Configurable target layout: 5.1.4, 7.1.4, or custom height channel positions
- Parameters: ambient extraction amount, height level, PBA frequency allocation, decorrelation amount

**Extends:** `CrossoverFilter`, `MidSideEncoder`, `MidSideDecoder`

---

### 21. Ambisonic Enhancement via Time-Frequency Masking

**Category:** Spatial · **Priority:** Medium · **Pure Java:** Yes

**Description:**
Enhance first-order Ambisonics (FOA) signals to achieve apparent higher-order spatial resolution using time-frequency masking. FOA recordings (4 channels) have limited spatial resolution — this technique applies directional analysis and masking in the STFT domain to sharpen spatial images, complementing the existing `AsdmProcessor` (Ambisonic Spatial Decomposition Method).

**AES Research References:**
- [Enhancement of Ambisonics Signals using time-frequency masking](AES/Enhancement_of_Ambisonics_Signals_using_time-frequency_masking.pdf) (2020) — Proposes STFT-domain directional analysis of FOA signals followed by time-frequency masking to enhance spatial resolution; demonstrated improvement in perceptual spatial quality without requiring higher-order microphones
- [Four-Directional Ambisonic Spatial Decomposition Method With Reduced Temporal Artifacts](AES/Four-Directional_Ambisonic_Spatial_Decomposition_Method_With_Reduced_Temporal_Artifacts.pdf) (2022) — Improved ASDM variant reducing temporal artifacts; complementary technique for FOA enhancement

**Implementation Approach:**
- New class `AmbisonicEnhancer` in `daw-core/…/spatial/ambisonics/`
- STFT analysis of all 4 FOA channels (W, X, Y, Z) using `FftUtils`
- Per time-frequency tile: estimate DOA (direction of arrival) from intensity vector analysis using existing `SphericalHarmonics`
- Generate directional masking weights: amplify time-frequency tiles that are strongly directional, attenuate diffuse tiles
- Re-encode enhanced directional signal to FOA using `AmbisonicEncoder`
- Parameters: enhancement strength, diffuse/direct threshold, temporal smoothing
- Complements the existing `AsdmProcessor` with a different enhancement algorithm

**Extends:** `FftUtils`, `SphericalHarmonics`, `AmbisonicEncoder`, `AsdmProcessor`

---

### 22. Spatial Room Impulse Response Tail Resynthesis

**Category:** Spatial · **Priority:** Low · **Pure Java:** Yes

**Description:**
Add the ability to resynthesize the late reverb tail of a spatial room impulse response (RIR) with configurable anisotropic multi-slope decay. This enables extending or modifying measured room impulse responses — for example, extending the decay time of a short RIR or adjusting the directional decay balance without re-measuring the room.

**AES Research References:**
- [Resynthesis of Spatial Room Impulse Response Tails With Anisotropic Multi-Slope Decays](AES/Resynthesis_of_Spatial_Room_Impulse_Response_Tails_With_Anisotropic_Multi-Slope_Decays.pdf) (2022) — Presents a method for resynthesizing the late reverb tail of multichannel RIRs with independent decay slopes per direction; uses shaped noise filtered to match the original RIR spectral and temporal envelope
- [Computationally-Efficient Simulation of Late Reverberation for Inhomogeneous Boundary Conditions and Coupled Rooms](AES/Computationally-Efficient_Simulation_of_Late_Reverberation_for_Inhomogeneous_Boundary_Conditions_and_Coupled_Rooms.pdf) (2023) — Efficient late reverberation methods for rooms with non-uniform surfaces; relevant to non-isotropic decay modeling

**Implementation Approach:**
- New class `SpatialRirResynthesizer` in `daw-core/…/spatial/room/`
- Analyze measured RIR: extract energy decay curve per frequency band per spatial direction
- Resynthesize late tail using shaped Gaussian noise filtered to match spectral envelope
- Independent decay time per direction (anisotropic) and per frequency band
- Crossfade resynthesized tail with measured early reflections at a configurable mixing time
- Compatible with SOFA-format impulse responses via existing `SofaFileParser`

**Extends:** `FftUtils`, `SofaFileParser`, `FdnRoomSimulator`

---

### 23. Panning Table Synthesis for Irregular Speaker Layouts

**Category:** Spatial · **Priority:** Medium · **Pure Java:** Yes

**Description:**
Add panning table synthesis to extend the existing `VbapPanner` for irregular and non-standard speaker layouts. VBAP requires triangulation of speaker positions which can fail or produce artifacts for non-ideal layouts. Panning table synthesis pre-computes a lookup table that smoothly interpolates panning gains for any source position, handling arbitrary speaker placements gracefully.

**AES Research References:**
- [Emulating Vector Base Amplitude Panning Using Panningtable Synthesis](AES/Emulating_Vector_Base_Amplitude_Panning_Using_Panningtable_Synthesis.pdf) (2023) — Proposes a panning table synthesis method that emulates VBAP behavior while handling irregular and degenerate speaker layouts; pre-computes gain tables at configurable angular resolution for efficient real-time lookup
- [Multichannel Compensated Amplitude Panning, An Adaptive Object-Based Reproduction Method](AES/Multichannel_Compensated_Amplitude_Panning,_An_Adaptive_Object-Based_Reproduction_Method.pdf) (2019) — Compensated panning for object-based reproduction with non-uniform speaker arrangements
- [Immersive Audio Reproduction and Adaptability for Irregular Loudspeaker Layouts Using Modified EBU ADM Renderer](AES/Immersive_Audio_Reproduction_and_Adaptability_for_Irregular_Loudspeaker_Layouts_Using_Modified_EBU_ADM_Renderer.pdf) (2024) — Modified ADM renderer for irregular layouts; validates the need for flexible panning algorithms

**Implementation Approach:**
- New class `PanningTableSynthesizer` in `daw-core/…/spatial/panner/`
- Pre-compute panning gain table at configurable angular resolution (e.g., 1° azimuth × 1° elevation)
- For each grid point: compute VBAP gains using existing `VbapPanner`, or use nearest-neighbor interpolation for degenerate triangulations
- Runtime: bilinear interpolation of the pre-computed table for sub-degree source positions
- Handles irregular layouts: missing speakers, non-symmetric arrangements, height-only arrays
- Integrates as an alternative panning strategy alongside `VbapPanner`

**Extends:** `VbapPanner`, `SpeakerLayout`

---

### 24. Stereo-to-Mono Down-Mix Optimizer

**Category:** Spatial · **Priority:** Medium · **Pure Java:** Yes

**Description:**
Add an optimized stereo-to-mono down-mix algorithm that minimizes phase cancellation artifacts. Simple L+R summing causes destructive cancellation of out-of-phase content, reducing bass and losing spatial elements. This optimizer detects and compensates for these issues, producing a mono mix that better represents the original stereo signal.

**AES Research References:**
- [Low Complexity Methods for Robust Stereo-to-Mono Down-mixing](AES/Low_Complexity_Methods_for_Robust_Stereo-to-Mono_Down-mixing.pdf) (2022) — Presents low-complexity methods for robust mono downmixing: polarity-adaptive summing, time-aligned summing, and energy-preserving summing that avoid destructive cancellation while maintaining low computational cost

**Implementation Approach:**
- New class `MonoDownMixOptimizer` in `daw-core/…/dsp/`
- Three modes:
  - **Standard sum**: simple `(L + R) / 2` (baseline)
  - **Polarity-adaptive**: per-band polarity detection using cross-correlation; invert side component in bands with negative correlation before summing
  - **Energy-preserving**: ensure mono RMS matches the average of L and R RMS levels by applying frequency-dependent gain compensation
- Mono compatibility score output: quantify how much energy is lost in standard summing vs. optimized
- Uses existing `CrossoverFilter` for multiband processing and `CorrelationMeter` for correlation analysis
- Applications: mono compatibility check, podcast/voice mono export, broadcast compatibility

**Extends:** `CrossoverFilter`, `CorrelationMeter`, `MidSideEncoder`

---

## Utility

### 25. Audio Test Signal Generator Suite

**Category:** Utility · **Priority:** Medium · **Pure Java:** Yes

**Description:**
Add a comprehensive test signal generator for system calibration, measurement, and plugin testing. Includes sine sweeps, pink/white noise, impulse responses, and multi-tone stimuli — essential tools that every professional DAW should provide natively.

**AES Research References:**
- [A New Electronic Audio Sweep-Frequency Generator](AES/A_New_Electronic_Audio_Sweep-Frequency_Generator.pdf) (1949) — The seminal AES paper on sweep-frequency signal generation; foundational reference for logarithmic and linear frequency sweep design
- [Excitation Stimuli For Simultaneous Deconvolution of Room Responses](AES/Excitation_Stimuli_For_Simultaneous_Deconvolution_of_Room_Responses.pdf) (2023) — Evaluates different excitation stimuli (exponential sweeps, MLS, multi-tones) for room response measurement; recommends exponential sine sweeps for best SNR and distortion separation
- [A New Approach to Impulse Response Measurements at High Sampling Rates](AES/A_New_Approach_to_Impulse_Response_Measurements_at_High_Sampling_Rates.pdf) (2014) — Improved impulse response measurement techniques at high sample rates
- [Use of Repetitive Multi-Tone Sequences to Estimate Nonlinear Response of a Loudspeaker to Music](AES/Use_of_Repetitive_Multi-Tone_Sequences_to_Estimate_Nonlinear_Response_of_a_Loudspeaker_to_Music.pdf) (2017) — Multi-tone test signals for nonlinear system characterization

**Implementation Approach:**
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

---

### 26. Hearing Loss Simulation for Accessible Monitoring

**Category:** Utility · **Priority:** Low · **Pure Java:** Yes

**Description:**
Add a hearing loss simulation processor that models common hearing impairments for monitoring purposes. Enables audio engineers to preview how their mixes will sound to listeners with typical age-related hearing loss or noise-induced hearing damage — promoting accessible audio production.

**AES Research References:**
- [Investigation of a Real-Time Hearing Loss Simulation for use in Audio Production](AES/Investigation_of_a_Real-Time_Hearing_Loss_Simulation_for_use_in_Audio_Production.pdf) (2020) — Presents a real-time hearing loss simulator designed specifically for audio production; models audiogram-based hearing loss with frequency-dependent gain reduction, loudness recruitment, and reduced frequency selectivity
- [Developing plugins for your ears](AES/Developing_plugins_for_your_ears.pdf) (2021) — Discusses the development of hearing-related audio plugins, including hearing loss awareness tools

**Implementation Approach:**
- New class `HearingLossSimulator implements AudioProcessor` in `daw-core/…/dsp/`
- Audiogram-based model: configurable hearing threshold per octave band (250 Hz – 8 kHz)
- Frequency-dependent gain reduction using parallel `BiquadFilter` bands
- Loudness recruitment simulation: reduced dynamic range at affected frequencies using per-band compression
- Presets for common hearing profiles: mild high-frequency loss, moderate age-related (presbycusis), noise-induced hearing loss
- Parameters: per-band hearing threshold, recruitment level, broadened auditory filter simulation
- Operates as a monitoring-only insert (bypassed on export)

**Extends:** `BiquadFilter`, `CompressorProcessor`, `AudioProcessor`

---

## Mastering & Export

### 27. Intelligent Gap Filling / Bandwidth Extension

**Category:** Mastering · **Priority:** Low · **Pure Java:** Yes

**Description:**
Add a bandwidth extension processor that restores high-frequency content lost to lossy compression or band-limited recording. Uses spectral analysis to detect the cutoff frequency and generates plausible high-frequency content via harmonic extrapolation and noise shaping — improving perceived quality of degraded source material.

**AES Research References:**
- [Perceptually Controlled Selection of Alternatives for High-Frequency Content in Intelligent Gap Filling](AES/Perceptually_Controlled_Selection_of_Alternatives_for_High-Frequency_Content_in_Intelligent_Gap_Filling.pdf) (2025) — Presents perceptually-controlled bandwidth extension using alternative high-frequency candidates selected for tonal alignment with the original content; demonstrates improved quality over simple spectral band replication
- [Sound Board: High-Resolution Audio](AES/Sound_Board__High-Resolution_Audio.pdf) (2015) — Context on high-resolution audio and the perceptual significance of bandwidth

**Implementation Approach:**
- New class `BandwidthExtender implements AudioProcessor` in `daw-core/…/dsp/`
- Cutoff detection: use spectral analysis to identify the high-frequency rolloff point (from `LosslessIntegrityChecker`)
- Spectral band replication (SBR): mirror and transpose spectral content from below the cutoff to fill the gap above
- Harmonic extrapolation: generate harmonics of detected tonal components above the cutoff
- Noise shaping: shape white noise to match the spectral envelope extrapolation above the cutoff
- Perceptual filtering: post-filter generated content to match expected spectral slope
- Parameters: target bandwidth, generation method (SBR / harmonic / noise), intensity, blend

**Extends:** `FftUtils`, `SpectrumAnalyzer`, `BiquadFilter`

---

## Notes

### Implementation Order Recommendation

**Phase 1 — Immediate Value** (High Priority)
1. [#10 Sines/Transients/Noise Decomposition](#10-sines--transients--noise-decomposition) — Unlocks advanced editing workflows
2. [#11 Phase Alignment and Polarity Detection](#11-phase-alignment-and-polarity-detection) — Essential multitrack recording tool
3. [#1 Graphic Equalizer Processor](#1-graphic-equalizer-processor-octave--third-octave) — Standard mixing/mastering tool
4. [#2 Oversampled Nonlinear Waveshaper](#2-oversampled-nonlinear-waveshaper) — Foundation for quality distortion/saturation effects
5. [#4 Velvet-Noise Reverb Processor](#4-velvet-noise-reverb-processor) — Efficient reverb alternative leveraging virtual threads
6. [#18 Binaural Externalization Processing](#18-binaural-externalization-processing) — Improved headphone monitoring
7. [#19 Stereo-to-Binaural Conversion](#19-stereo-to-binaural-conversion) — Essential for immersive audio workflow

**Phase 2 — Enhanced Capabilities** (Medium Priority)
8. [#6 Perceptual Bass Extension](#6-perceptual-bass-extension-processor)
9. [#7 Air Absorption Filter](#7-air-absorption-filter-for-distance-modeling)
10. [#9 Audio Peak Reduction](#9-audio-peak-reduction-via-chirp-spreading)
11. [#14 Multitrack Mix Feature Analysis](#14-multitrack-mix-feature-analysis)
12. [#15 Fractional-Octave Spectrum Smoothing](#15-fractional-octave-spectrum-smoothing)
13. [#12 Lossless Audio Integrity Checker](#12-lossless-audio-integrity-checker)
14. [#13 Lossy Compression Artifact Detection](#13-lossy-compression-artifact-detection)
15. [#17 Transient Detection for Block Switching](#17-transient-detection-for-adaptive-block-switching)
16. [#20 2D-to-3D Ambience Upmixer](#20-2d-to-3d-ambience-upmixer)
17. [#21 Ambisonic Enhancement](#21-ambisonic-enhancement-via-time-frequency-masking)
18. [#23 Panning Table Synthesis](#23-panning-table-synthesis-for-irregular-speaker-layouts)
19. [#24 Stereo-to-Mono Down-Mix Optimizer](#24-stereo-to-mono-down-mix-optimizer)
20. [#25 Audio Test Signal Generator](#25-audio-test-signal-generator-suite)
21. [#3 Antiderivative Antialiasing](#3-antiderivative-antialiasing-for-distortion-effects)
22. [#5 Directional FDN Reverb](#5-directional-feedback-delay-network-reverb)

**Phase 3 — Advanced Features** (Low Priority)
23. [#8 Non-Ideal Op-Amp Distortion](#8-non-ideal-op-amp-distortion-model)
24. [#16 Coherence-Based Distortion Indicator](#16-coherence-based-distortion-indicator)
25. [#22 Spatial RIR Tail Resynthesis](#22-spatial-room-impulse-response-tail-resynthesis)
26. [#26 Hearing Loss Simulation](#26-hearing-loss-simulation-for-accessible-monitoring)
27. [#27 Bandwidth Extension](#27-intelligent-gap-filling--bandwidth-extension)

### Design Principles

All enhancements follow these principles:
- **Pure Java**: No external native libraries required; all algorithms implemented in Java
- **No external dependencies**: Built on existing DAW SDK interfaces and core DSP utilities
- **FFM over JNI**: Where native code would be beneficial for performance-critical paths (none required for these issues), use the Foreign Function & Memory API (JEP 454, final in JDK 22) instead of JNI
- **Existing architecture**: All processors implement `AudioProcessor`, use `BiquadFilter`/`FftUtils` as building blocks, and integrate with the existing effects chain and mastering pipeline
- **Virtual thread compatible**: Real-time processors avoid `synchronized` blocks; batch analysis tasks use `Executors.newVirtualThreadPerTaskExecutor()` where parallelism helps

## Description

Enhance first-order Ambisonics (FOA) signals to achieve apparent higher-order spatial resolution using time-frequency masking. FOA recordings (4 channels) have limited spatial resolution — this technique applies directional analysis and masking in the STFT domain to sharpen spatial images, complementing the existing `AsdmProcessor` (Ambisonic Spatial Decomposition Method).

## AES Research References

- [Enhancement of Ambisonics Signals using time-frequency masking](docs/research/AES/Enhancement_of_Ambisonics_Signals_using_time-frequency_masking.pdf) (2020) — Proposes STFT-domain directional analysis of FOA signals followed by time-frequency masking to enhance spatial resolution; demonstrated improvement in perceptual spatial quality without requiring higher-order microphones
- [Four-Directional Ambisonic Spatial Decomposition Method With Reduced Temporal Artifacts](docs/research/AES/Four-Directional_Ambisonic_Spatial_Decomposition_Method_With_Reduced_Temporal_Artifacts.pdf) (2022) — Improved ASDM variant reducing temporal artifacts; complementary technique for FOA enhancement

## Implementation Approach

- New class `AmbisonicEnhancer` in `daw-core/…/spatial/ambisonics/`
- STFT analysis of all 4 FOA channels (W, X, Y, Z) using `FftUtils`
- Per time-frequency tile: estimate DOA (direction of arrival) from intensity vector analysis using existing `SphericalHarmonics`
- Generate directional masking weights: amplify time-frequency tiles that are strongly directional, attenuate diffuse tiles
- Re-encode enhanced directional signal to FOA using `AmbisonicEncoder`
- Parameters: enhancement strength, diffuse/direct threshold, temporal smoothing
- Complements the existing `AsdmProcessor` with a different enhancement algorithm

## Extends

`FftUtils`, `SphericalHarmonics`, `AmbisonicEncoder`, `AsdmProcessor`

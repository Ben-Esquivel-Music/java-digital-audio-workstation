## Description

Add a binaural externalization processor that improves the perceived spatial quality of headphone monitoring. Binaural audio often suffers from in-head localization (sound appears inside the head rather than outside). Externalization processing adds subtle early reflections and HRTF-based crosstalk to move the perceived sound image outside the listener's head.

## AES Research References

- [Binaural Externalization Processing - from Stereo to Object-Based Audio](docs/research/AES/Binaural_Externalization_Processing_-_from_Stereo_to_Object-Based_Audio.pdf) (2022) — Comprehensive externalization processing framework applicable from stereo to object-based audio; details early reflection synthesis, HRTF filtering, and crossfeed techniques for improved headphone monitoring
- [Spectral and Spatial Discrepancies Between Stereo and Binaural Spatial Masters in Headphone Playback: A Perceptual and Technical Analysis](docs/research/AES/Spectral_and_Spatial_Discrepancies_Between_Stereo_and_Binaural_Spatial_Masters_in_Headphone_Playback__A_Perceptual_and_Technical_Analysis.pdf) (2025) — Documents measurable spectral and spatial differences between stereo and binaural masters on headphones; informs the corrections that externalization must apply
- [On the Differences in Preferred Headphone Response for Spatial and Stereo Content](docs/research/AES/On_the_Differences_in_Preferred_Headphone_Response_for_Spatial_and_Stereo_Content.pdf) (2022) — Headphone response preferences differ for spatial vs. stereo content, informing target curves for externalized monitoring

## Implementation Approach

- New class `BinauralExternalizationProcessor` in `daw-core/…/spatial/binaural/`
- Crossfeed filter: frequency-dependent inter-channel bleed with ITD delay (simulating loudspeaker crosstalk)
- Early reflection synthesis: 2–4 short delays with directional HRTF filters using existing `HrtfInterpolator`
- Room coloration: subtle low-order FDN reverb (reusing `FdnRoomSimulator` components) to add environmental context
- Parameters: crossfeed level, room size, externalization amount, headphone compensation EQ
- Integrates with the existing `DefaultBinauralRenderer` monitoring path

## Extends

`DefaultBinauralRenderer`, `HrtfInterpolator`, `BiquadFilter`

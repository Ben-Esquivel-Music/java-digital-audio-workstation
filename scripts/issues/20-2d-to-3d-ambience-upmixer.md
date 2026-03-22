## Description

Add an upmixer that extracts the ambient/diffuse component from a stereo or surround signal and spatially distributes it into height channels, creating an immersive 3D sound field from 2D source material. This enables existing stereo content to be expanded into immersive formats like 7.1.4 Atmos beds.

## AES Research References

- [2D-to-3D Ambience Upmixing based on Perceptual Band Allocation](docs/research/AES/2D-to-3D_Ambience_Upmixing_based_on_Perceptual_Band_Allocation.pdf) (2015) — Proposes perceptual band allocation (PBA) for distributing ambient content to height channels based on psychoacoustic criteria; allocates frequency bands to overhead speakers where they contribute most to perceived envelopment
- [Perceptual Band Allocation (PBA) for the Rendering of Vertical Image Spread with a Vertical 2D Loudspeaker Array](docs/research/AES/Perceptual_Band_Allocation_%28PBA%29_for_the_Rendering_of_Vertical_Image_Spread_with_a_Vertical_2D_Loudspeaker_Array.pdf) (2016) — Extends PBA theory for vertical spread rendering; applicable to height channel distribution
- [The Effect of Temporal and Directional Density on Listener Envelopment](docs/research/AES/The_Effect_of_Temporal_and_Directional_Density_on_Listener_Envelopment.pdf) (2023) — Demonstrates that distributing reflections across more directions increases perceived envelopment

## Implementation Approach

- New class `AmbienceUpmixer` in `daw-core/…/spatial/`
- Direct/ambient separation using mid-side decomposition and decorrelation analysis
- Perceptual band allocation: divide ambient signal into frequency bands using `CrossoverFilter`
- Assign bands to height channels based on PBA criteria (higher frequencies to overhead for maximum envelopment)
- Decorrelation of assigned bands to prevent spatial collapse (allpass-based decorrelators)
- Configurable target layout: 5.1.4, 7.1.4, or custom height channel positions
- Parameters: ambient extraction amount, height level, PBA frequency allocation, decorrelation amount

## Extends

`CrossoverFilter`, `MidSideEncoder`, `MidSideDecoder`

---
name: research-immersive
description: Consult immersive audio research when implementing spatial audio features (3D panning, Dolby Atmos, Ambisonics, binaural rendering, HRTF, head tracking, immersive mastering). Based on Berklee Online spatial audio analysis and AES papers.
argument-hint: [spatial audio feature or technique]
allowed-tools: Read Grep Glob
---

# Research: Immersive Audio Mixing Techniques

Consult the immersive audio research to inform implementation of **$ARGUMENTS**.

## Primary Source

Read `docs/research/immersive-audio-mixing.md` for the full immersive audio analysis based on Berklee Online spatial audio resources.

## Key Reference Sections

Search the immersive audio research for the relevant feature:

### Categories of Immersive Production
1. **Hyper-Realistic Reproduction** — Lifelike acoustic environment capture
2. **Remixing Classic Stereo/Mono** — Expanding 2D works into immersive formats
3. **Stereo with Spatial Awareness** — Stereo-compatible spatial production
4. **Music Created for Immersive** — Native 3D composition and production

### Core Techniques (Section Index)
1. **3D Staging and Pan Automation** — X/Y/Z positioning, distance modeling, motion automation
2. **Object-Based Mixing (Dolby Atmos)** — Bed channels vs. objects, metadata generation, ADM BWF export
3. **Binaural and Headphone Mixing** — HRTF profiles, SOFA file import, head tracking, A/B monitoring
4. **Ambisonics** — FOA (4-channel B-format), HOA (higher-order), encoding/decoding, VR/360
5. **Immersive Mastering and Delivery** — Atmos master files, Apple Spatial Audio, loudness standards
6. **Workflow and Compatibility** — Built-in renderer, fold-down monitoring, session templates

### Advanced Topics
- Sound design for 360/VR environments
- Post-production for immersive media
- Reverb and spatial effects in 3D (convolution, object-specific, height-channel)

### Open Source Spatial Audio Tools
- SAF, OpenAL Soft, Steam Audio, SpatGRIS, libmysofa, ambiX, Mesh2HRTF, HOAC

### Implementation Priority
- **High:** 3D panner, binaural renderer, bed + object tracks, fold-down monitoring
- **Medium:** Ambisonic encoding/decoding, ADM BWF export, Apple Spatial Audio, immersive loudness
- **Lower:** Head tracking, 360/VR workflow, multichannel convolution reverb, custom HRTF generation

## Instructions

1. Read `docs/research/immersive-audio-mixing.md` and find sections relevant to **$ARGUMENTS**
2. Check the AES Conference Papers table at the bottom for relevant spatial audio research
3. Cross-reference with `docs/research/audio-development-tools.md` Spatial Audio Tools section
4. Cross-reference with `docs/research/aes-feature-enhancements.md` Spatial category (#18-#24)
5. Provide implementation guidance including:
   - The spatial audio technique and its purpose
   - Object-based vs. scene-based approach considerations
   - Recommended open source tools/libraries for JNI integration
   - Format compatibility requirements (Atmos, Apple Spatial, Ambisonics)
   - Relevant AES papers for algorithm details

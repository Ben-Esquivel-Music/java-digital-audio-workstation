---
name: research-aes
description: Search 345 AES (Audio Engineering Society) research papers for algorithms, techniques, and academic references. Covers spatial audio, DSP, mixing/mastering, ML/AI, acoustics, audio quality, loudspeakers, recording, and codec design. Papers span 1949-2026.
argument-hint: [topic, algorithm, or technique]
allowed-tools: Read Grep Glob
---

# Research: AES Conference Papers

Search the AES research paper collection for information about **$ARGUMENTS**.

## Primary Sources

1. Read `docs/research/aes-research-papers.md` for the analyzed catalog of 43 key papers organized by topic
2. Read `docs/research/aes-pdf-catalog.md` for the complete decade-organized catalog of all 345 PDFs

## Research Categories (from aes-research-papers.md)

### Spatial Audio and Immersive Sound (11 papers)
Ambisonics, binaural rendering, HRTF, 3DoF/6DoF head tracking, crosstalk cancellation, panning optimization, spatial composition

### Audio Effects Modeling and DSP (8 papers)
Differentiable DSP, analog modeling, spring reverb physical modeling, Leslie effect, waveshaping, mix graph reconstruction

### Mixing and Mastering (6 papers)
RLHF adaptive mixing, semantic auto-EQ, neural EQ, room treatment, room equalization, mix analysis

### Audio Quality and Perceptual Evaluation (6 papers)
Quality metrics toolbox, ODAQ dataset, speech quality evaluation, virtual listener panels, dynamic sound zones

### Machine Learning and AI in Audio (4 papers)
Neural audio compression, sound effect synthesis, audio pattern detection, distortion restoration

### Acoustics and Room Modeling (3 papers)
RoomAcoustiC++ (open-source), sound field visualization, room acoustic variation perception

### Loudspeaker Design and Measurement (5 papers)
Power estimation, metamaterial absorbers, flat-panel woofers, sound field radiation, nonlinear losses

### Recording Techniques (2 papers)
Microphone preference study, sound reinforcement system design

### Audio Codec and Streaming (2 papers)
AC-4 next-gen codec, development tools for audio codecs

## AES PDF Collection (345 papers, 1949-2026)
All PDFs are in `docs/research/AES/` directory. The catalog spans:
- 1940s-1950s: Foundational audio engineering (amplifiers, circuits, recording)
- 1960s-1990s: Loudspeaker design, noise reduction, spectral recording
- 2000s-2010s: Spatial audio, signal processing, perceptual evaluation
- 2020s: ML/AI audio, differentiable DSP, immersive production, neural codecs

## Instructions

1. Grep `docs/research/aes-research-papers.md` for keywords related to **$ARGUMENTS**
2. If found, read the relevant category section for paper summaries and DAW relevance ratings
3. Grep `docs/research/aes-pdf-catalog.md` for additional papers by title keywords
4. Check `docs/research/aes-feature-enhancements.md` for any feature specs derived from these papers
5. For each relevant paper, provide:
   - Paper title and PDF filename in `docs/research/AES/`
   - Key topics and algorithms covered
   - DAW relevance rating (High/Medium/Low)
   - How the research applies to the current implementation task
6. If the user needs to read a specific paper, note the PDF path for direct reading

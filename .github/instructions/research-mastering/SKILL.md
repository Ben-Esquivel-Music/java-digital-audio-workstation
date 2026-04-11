---
name: research-mastering
description: Consult mastering techniques research when implementing mastering features (EQ, compression, limiting, loudness metering, stereo imaging, dithering, deliverables). Based on Berklee Online professional mastering workflow analysis and AES papers.
argument-hint: [mastering feature or technique]
allowed-tools: Read Grep Glob
---

# Research: Professional Mastering Techniques

Consult the mastering research to inform implementation of **$ARGUMENTS**.

## Primary Source

Read `docs/research/mastering-techniques.md` for the full mastering techniques analysis based on Berklee Online professional mastering resources.

## Key Reference Sections

Search the mastering research for the relevant technique:

### Core Mastering Techniques (Section Index)
1. **Listening Environment Optimization** — Room correction, monitor calibration, reference monitoring simulation
2. **Critical Listening and Ear Training** — A/B comparison, reference track matching, ear fatigue management
3. **Equalization (EQ) and Tonal Balancing** — Parametric EQ, linear-phase EQ, Mid/Side EQ, spectrum analysis
4. **Dynamics Processing** — Single-band compression, multiband compression, limiting, parallel compression
5. **Noise Reduction and Restoration** — Spectral noise reduction, de-clicking, hum removal, clipping recovery
6. **Album Sequencing and Fades** — Track ordering, crossfades, gap timing, DDP/Red Book export
7. **Stereo Imaging** — Mid/Side encoding, stereo width, correlation metering, bass mono-ification
8. **Loudness Standards and Metering** — LUFS metering, true peak, platform targets (Spotify -14, Apple -16)
9. **Deliverables and Metadata** — Multi-format export, dithering, ISRC codes, DDP images
10. **Mastering Signal Chain** — Gain staging -> corrective EQ -> compression -> tonal EQ -> stereo imaging -> limiting -> dithering

### Genre and Format Tables
- Genre-specific loudness targets (Pop/EDM, Rock, Jazz/Classical, Hip-Hop)
- Format specifications (CD, streaming, hi-res, vinyl)

### Implementation Priority
- **High Priority:** Parametric EQ, compressor, limiter, LUFS meter, stereo imager, multi-format export
- **Medium Priority:** Mastering chain presets, A/B tools, album assembly, metadata editor
- **Lower Priority:** Spectral noise reduction, room correction, DDP export, vinyl mastering

## Instructions

1. Read `docs/research/mastering-techniques.md` and find sections relevant to **$ARGUMENTS**
2. Check the AES Conference Papers table at the bottom for relevant research papers
3. Cross-reference with `docs/research/aes-feature-enhancements.md` for any matching feature specs
4. Provide implementation guidance including:
   - The mastering technique and its purpose
   - Standard signal chain position and parameter ranges
   - Genre-specific considerations
   - Recommended implementation approach for Java
   - Relevant AES papers for algorithm details

---
name: research
description: Search all audio research documentation (mastering, immersive audio, tools, DAW architecture, AES papers) for a given topic. Use when implementing audio features, choosing libraries, or making design decisions informed by research.
argument-hint: [topic or feature name]
allowed-tools: Read Grep Glob
---

# Research: Search Audio Research Documentation

Search the project's research documentation in `docs/research/` for information relevant to **$ARGUMENTS**.

## Research Sources

Read the research index first to understand available resources:

1. Read `docs/research/README.md` for the full research overview and key findings
2. Search across ALL research documents for the topic:
   - `docs/research/mastering-techniques.md` — Professional mastering workflow, EQ, dynamics, loudness, deliverables
   - `docs/research/immersive-audio-mixing.md` — Spatial audio, Dolby Atmos, Ambisonics, binaural rendering, 3D mixing
   - `docs/research/open-source-daw-tools.md` — DAW platforms, architectures, plugin systems, design patterns
   - `docs/research/audio-development-tools.md` — Comprehensive tool catalog: DSP, synthesis, spatial audio, ML, I/O
   - `docs/research/aes-research-papers.md` — 43 AES conference papers analyzed: spatial audio, DSP, mixing, ML, acoustics
   - `docs/research/aes-pdf-catalog.md` — Complete catalog of 345 AES PDFs organized by decade (1949-2026)
   - `docs/research/aes-feature-enhancements.md` — 27 pure-Java feature enhancement issues derived from AES research

## Instructions

1. Grep all markdown files in `docs/research/` for keywords related to **$ARGUMENTS**
2. Read the most relevant sections from matching documents
3. Synthesize findings into a concise summary covering:
   - **What the research says** about this topic
   - **Recommended tools/libraries** (if applicable)
   - **Implementation approach** suggested by the research
   - **AES papers** that provide relevant algorithms or techniques
   - **Priority level** from the research roadmap (Immediate / Near-Term / Future)
4. If relevant AES PDFs exist in `docs/research/AES/`, note their filenames for deeper reading
5. Cross-reference with the implementation priority sections in each research document

---
title: "Fold-Down Monitoring Preview (Immersive to Stereo to Mono)"
labels: ["enhancement", "spatial-audio", "monitoring", "ui"]
---

# Fold-Down Monitoring Preview (Immersive to Stereo to Mono)

## Motivation

The `FoldDownRenderer` class exists in the spatial/objectbased package for rendering immersive formats down to simpler channel configurations. When working on spatial audio mixes, engineers must verify that the mix translates well when played back on different systems — from a full 7.1.4 Atmos system to a 5.1 surround setup, to stereo speakers, and even mono (Bluetooth speakers, phone speakers). Without fold-down preview, engineers cannot check compatibility without physically switching monitoring systems. This is a critical quality assurance step in immersive audio production.

## Goals

- Add a monitoring format selector in the transport bar or monitoring section
- Support switching between monitoring modes: 7.1.4, 5.1, Stereo, Mono
- Use the existing `FoldDownRenderer` to perform real-time fold-down rendering
- Provide instant switching (no audible gap or latency when changing modes)
- Show the current monitoring mode clearly in the UI
- Apply appropriate fold-down coefficients per the ITU-R BS.775 standard
- Allow configuring custom fold-down coefficients for non-standard setups

## Non-Goals

- Speaker management or delay compensation for physical speaker setups
- Bass management (LFE routing and subwoofer crossover)
- Loudness compensation when switching between formats

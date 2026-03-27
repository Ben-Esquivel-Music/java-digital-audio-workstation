---
title: "Spectrum Analyzer with Real-Time FFT Display"
labels: ["enhancement", "metering", "analysis", "ui"]
---

# Spectrum Analyzer with Real-Time FFT Display

## Motivation

The `SpectrumAnalyzer`, `FftUtils`, and `SpectrumDisplay` classes exist, providing the foundation for spectrum analysis. However, the current spectrum display is a basic visualization tile. Professional DAWs offer a full-featured spectrum analyzer with configurable FFT size, window type, averaging, peak hold, and frequency/dB axis labels. Engineers use spectrum analyzers constantly during mixing and mastering to identify problem frequencies, verify EQ decisions, and ensure balanced spectral content. The analyzer should be usable as both an inline visualization tile and a resizable floating window.

## Goals

- Provide a high-resolution spectrum display with dB (vertical) and Hz (horizontal) axes
- Support configurable FFT sizes (1024, 2048, 4096, 8192)
- Support windowing functions (Hanning, Hamming, Blackman-Harris)
- Add spectrum averaging with configurable smoothing
- Add peak hold display (shows the maximum level reached at each frequency)
- Color-code frequency ranges (sub-bass, bass, mids, high-mids, highs)
- Support pre/post EQ display overlay to visualize EQ impact
- Allow opening the analyzer as a floating resizable window
- Support stereo mode showing left and right channels overlaid or split

## Non-Goals

- Spectrogram (waterfall) display (separate feature)
- Third-party analyzer plugin support
- Matching EQ based on spectrum comparison (AI feature)

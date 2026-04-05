---
title: "Spectrum Analyzer Built-In Plugin"
labels: ["enhancement", "plugins", "analyzer", "ui"]
---

# Spectrum Analyzer Built-In Plugin

## Motivation

The DAW already has spectrum analysis capabilities and a story for a full spectrum analyzer (story 023). However, by packaging the spectrum analyzer as a `BuiltInDawPlugin`, it becomes a discoverable, launchable tool in the Plugins menu rather than a feature buried in a specific view. Users working on mixing or mastering sessions frequently want to open a spectrum analyzer on demand — for example, to check the frequency content of a specific track or the master bus — without navigating away from their current view.

A spectrum analyzer plugin would appear in the Plugins menu under the "Analyzers" category and open in its own floating window. It would tap into the audio engine's output bus (or a selected track's output) and display a real-time FFT frequency spectrum with configurable resolution, windowing, and display options.

## Goals

- Create a `SpectrumAnalyzerPlugin` class in `daw-core` that implements `BuiltInDawPlugin`
- The class has a public no-arg constructor for reflective instantiation
- `getDescriptor()` returns a `PluginDescriptor` with id `"com.benesquivelmusic.daw.spectrum-analyzer"`, name `"Spectrum Analyzer"`, type `ANALYZER`
- `getMenuLabel()` returns `"Spectrum Analyzer"`
- `getCategory()` returns `BuiltInPluginCategory.ANALYZER`
- `activate()` opens a floating window with a real-time FFT spectrum display
- The analyzer receives audio data from the selected audio source (master bus by default, with an option to switch to individual track outputs)
- Display configurable FFT size (1024, 2048, 4096, 8192 bins), windowing function (Hanning, Hamming, Blackman-Harris), and frequency scale (linear or logarithmic)
- Show frequency (Hz) and amplitude (dB) axes with labeled gridlines
- Support peak-hold overlay (decaying peak markers) and an average spectrum trace
- `deactivate()` stops the FFT processing and hides the window
- `dispose()` releases all audio tapping resources
- Add the `SpectrumAnalyzerPlugin` to the `BuiltInDawPlugin` permits clause

## Non-Goals

- Replacing any existing spectrum analysis UI in other views — this is an independent plugin window
- Spectrogram (waterfall) display (future enhancement to this plugin)
- Multi-channel surround spectrum analysis (stereo only initially)
- Plugin parameter automation (the analyzer has no audible output to automate)

---
title: "Signal Generator Built-In Plugin"
labels: ["enhancement", "plugins", "utility", "dsp"]
---

# Signal Generator Built-In Plugin

## Motivation

Audio engineers and developers frequently need test signals for calibration, troubleshooting, and measurement — sine waves for frequency response testing, white/pink noise for room analysis, sweep tones for impulse response capture, and square/triangle waves for signal chain verification. Currently, users must generate test audio externally and import it, or rely on third-party tools. A built-in signal generator plugin available from the Plugins menu would provide instant access to these essential test signals without leaving the DAW.

This is a standard utility found in professional DAW environments (Pro Tools Signal Generator, Logic Pro Test Oscillator, Reaper ReaSynth). It is particularly useful when combined with the DAW's existing spectrum analyzer and LUFS metering tools for end-to-end signal chain validation.

## Goals

- Create a `SignalGeneratorPlugin` class in `daw-core` that implements `BuiltInDawPlugin`
- The class has a public no-arg constructor for reflective instantiation
- `getDescriptor()` returns a `PluginDescriptor` with id `"com.benesquivelmusic.daw.signal-generator"`, name `"Signal Generator"`, type `INSTRUMENT`
- `getMenuLabel()` returns `"Signal Generator"`
- `getCategory()` returns `BuiltInPluginCategory.UTILITY`
- `activate()` opens a floating window with signal type and parameter controls
- Support the following waveform types: sine, square, triangle, sawtooth, white noise, and pink noise
- Provide a frequency control (20 Hz – 20,000 Hz) with a text input for precise values and a logarithmic slider
- Provide an amplitude control (−∞ dB to 0 dB) with a default of −18 dBFS to avoid accidental loud output
- Support frequency sweep mode: linear or logarithmic sweep from a start frequency to an end frequency over a configurable duration (useful for impulse response measurement)
- Route the generated signal to the master bus or a selected track output
- Include a mute/unmute toggle and a "panic" button that immediately silences output
- `deactivate()` stops signal generation and silences output
- `dispose()` releases all audio resources
- Add the `SignalGeneratorPlugin` to the `BuiltInDawPlugin` permits clause

## Non-Goals

- Multi-channel signal generation (stereo only — mono signal duplicated to both channels)
- Arbitrary waveform editing or drawing custom waveforms
- MIDI-triggered tone generation (this is a utility, not a playable instrument)
- Recording the generated signal to a clip (users can use the existing recording workflow to capture it)

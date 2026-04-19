---
title: "Input Gain Staging and Clip-Indicator Meters on Armed Tracks"
labels: ["enhancement", "recording", "metering", "ui"]
---

# Input Gain Staging and Clip-Indicator Meters on Armed Tracks

## Motivation

When a track is armed, the user needs to confirm the input level is hot enough to record well without clipping. Today the mixer's output meters only show post-fader, post-processing levels, which is useless during tracking because the input signal (pre-any-processing) is what might clip the A/D converter. Pro Tools, Logic, and every hardware console show a dedicated input meter on each armed channel with a sticky clip indicator that latches until manually cleared. Without this, engineers work blind: either too quietly (losing bits) or clipping the interface (destroying takes).

## Goals

- Add `InputLevelMeter` record in `com.benesquivelmusic.daw.sdk.audio.analysis`: `record InputLevelMeter(double peakDbfs, double rmsDbfs, boolean clippedSinceReset, long lastClipFrameIndex)`.
- Add `InputLevelMonitor` in `com.benesquivelmusic.daw.core.audio.analysis` that taps the input signal per armed track ahead of any processing and computes peak + RMS per render block.
- Extend `MixerChannelStrip` with a second meter column visible only when the track is armed: green/yellow/red LED-style bars plus a latching red clip LED above.
- Clicking the clip LED resets the `clippedSinceReset` flag for that track; `Alt+click` resets all tracks.
- Detect inter-sample peaks using 4× oversampling before the clip decision so true peaks near 0 dBFS are flagged even if sample peaks are below.
- Persist nothing — this is runtime-only state.
- Arrangement-view track headers show a miniature clip indicator mirroring the mixer's state so the user sees it while looking at the timeline.
- Tests: a synthetic signal with a single inter-sample peak at +0.5 dBTP is flagged; a signal at -0.1 dBFS does not trigger a clip; reset clears the flag.

## Non-Goals

- Input gain automation — input gain is set by the interface hardware, not the DAW.
- Trim controls that scale input signal before capture (feature-flag out-of-scope for this story).
- Long-term level statistics (that is the job of LUFS stories 014/166).

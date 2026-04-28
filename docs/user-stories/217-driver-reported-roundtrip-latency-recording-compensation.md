---
title: "Driver-Reported Round-Trip Latency for Recorded-Take Time Alignment"
labels: ["enhancement", "audio-engine", "recording", "dsp"]
---

# Driver-Reported Round-Trip Latency for Recorded-Take Time Alignment

## Motivation

When the user records a vocal while monitoring a click track, the recorded take is offset from the grid by the interface's input + output latency: the click leaves the DAW, travels through the buffer to the speakers, the singer hears it and sings, the microphone captures it, the signal travels back through the buffer to the DAW, and the DAW writes it at the *current* sample position — which is later than the bar line that prompted the singer. Story 124's PDC handles plugin latency inside the graph; this is a separate round-trip caused by the driver's own input and output buffer pipelines. Without compensation every take is offset by 5–25 ms (depending on buffer size) and overdubs sit late against the bed.

Pro Tools and Logic both call `ASIOGetLatencies(int* inputLatency, int* outputLatency)` at stream open and shift the recorded buffer by `inputLatency + outputLatency` so the wave aligns with the bar where the user played. CoreAudio exposes this via `kAudioDevicePropertyLatency` and `kAudioStreamPropertyLatency` plus `kAudioDevicePropertySafetyOffset`. WASAPI exposes it via `IAudioClient::GetStreamLatency` plus the device period. JACK provides `jack_port_get_total_latency`. All four are authoritative sources that are accurate to the sample for a given buffer-size + sample-rate combination.

Story 057 wires playback to hardware; story 060 wires recording capture. Neither shifts the captured frames.

## Goals

- Add `record RoundTripLatency(int inputFrames, int outputFrames, int safetyOffsetFrames)` to `com.benesquivelmusic.daw.sdk.audio`. Total compensation is the sum of all three.
- Extend `AudioBackend` (story 130) with `RoundTripLatency reportedLatency()` that each backend populates from its native API at stream open.
- `RecordingPipeline` reads `reportedLatency()` once per opened stream and stores it in `RecordingSession.compensationFrames`. When a recorded buffer arrives at sample position `n`, the resulting `AudioClip` is written with start position `n - compensationFrames` so the take aligns with the cue the singer heard.
- Add an "Apply latency compensation to recorded takes" toggle in `AudioSettingsDialog` (default on) — useful to disable for diagnostic listening or for users wired through a hardware monitor mixer who already pre-compensate.
- Show the live latency in the transport bar near the buffer-size readout: "I/O 5.3 ms" computed as `(inputFrames + outputFrames + safetyOffsetFrames) / sampleRate`. Click opens an `IoLatencyDetailsPopup` showing the three components separately and the source ("reported by driver").
- When the buffer size changes (story 213) or the device changes (story 214 reconnect), re-query `reportedLatency()` and update the indicator.
- Add an opt-in "Latency calibration" tool: plays an impulse from output, captures from a designated input (loopback or measurement mic), measures the actual round-trip, and reports the delta vs the driver's reported value. If the delta is greater than 64 frames, surface a notification ("Driver-reported latency may be off by N samples") and offer to apply the measured override per device.
- Persist the calibration override per device in `~/.daw/audio-settings.json`.
- Tests: a `MockAudioBackend` reporting `RoundTripLatency(64, 128, 16)` produces takes whose start position is shifted by 208 frames; toggling compensation off leaves takes uncompensated; calibration override replaces the reported value when present.

## Non-Goals

- Per-track compensation that varies by physical input — driver reports a single round-trip number; per-channel offsets are out of scope.
- Compensating MIDI input timing the same way (MIDI uses a different transport; covered separately).
- Compensating the *monitor* path (audible click vs played-back click) — that is what the playhead schedules into output and is already correct.
- Subsample compensation. Snap to nearest frame.

---
title: "Plugin and Track Latency Compensation Telemetry Panel"
labels: ["enhancement", "audio-engine", "plugins", "ui"]
---

# Plugin and Track Latency Compensation Telemetry Panel

## Motivation

Story 090 introduces plugin delay compensation (PDC) as a feature, but it makes latency an invisible mechanism. In practice engineers need to *see* the latency numbers: which track has 2048 samples of lookahead from a mastering limiter, which send is adding 64 samples of oversampling latency, and whether the total session PDC is approaching the interface's monitoring tolerance. Pro Tools has its "Time Adjuster" view, Cubase has "Constrain Delay Compensation," and Reaper surfaces PDC per track in its performance meter. Without visibility, users cannot diagnose the "why is this snare hitting late?" class of bug that always emerges on mix sessions with many plugins.

`MixerEngine`, `InsertEffectRack`, and the PDC wiring from story 090 already track latency internally. This story surfaces that data.

## Goals

- Add `LatencyTelemetry` record in `com.benesquivelmusic.daw.sdk.audio`: `record LatencyTelemetry(String nodeId, NodeKind kind, int samples, int reportedBy)` where `NodeKind` is a sealed enum (`PLUGIN`, `TRACK`, `BUS`, `SEND`, `MASTER`).
- Add `LatencyTelemetryCollector` in `com.benesquivelmusic.daw.core.audio.performance` that snapshots the live graph's reported latencies each render cycle and publishes the snapshot on a `Flow.Publisher`.
- New `LatencyTelemetryPanel` in `daw-app` showing a tree view (Tracks → Inserts → Sends) with sample counts, millisecond equivalents, and the bar graph proportional to total PDC.
- Highlight nodes that changed latency since last report (plugin bypassed, oversampling toggled) with a subtle flash animation.
- Add a "Constrain Delay Compensation" global toggle that bypasses lookahead-heavy plugins above a user-configurable sample threshold (default 256) for low-latency tracking.
- Surface total session PDC in the transport bar next to the xruns counter.
- Tests: PDC snapshot matches the sum of enabled processors' reported latencies; constrain-mode correctly bypasses plugins above threshold and restores them on disable.

## Non-Goals

- Re-implementing PDC itself (that is story 090).
- Plugin-reported latency correction for plugins that lie about their latency — this story trusts the reported value.
- Automatic compensation for external hardware inserts (story 092 territory).

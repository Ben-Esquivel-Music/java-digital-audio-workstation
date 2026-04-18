# daw-acoustics Integration

The `daw-acoustics` module is a standalone acoustics library — room simulation,
Feedback Delay Network (FDN) reverb, diffraction modelling, graphic EQ and
related DSP primitives. It is integrated into the audio pipeline through
**adapter classes that live in `daw-core`**, not by modifying `daw-acoustics`
itself.

## Module boundary

```
┌──────────────────────┐      depends on         ┌─────────────────────┐
│      daw-app         │  ─────────────────────▶ │      daw-core       │
└──────────────────────┘                         │                     │
                                                 │  ┌───────────────┐  │
                                                 │  │   adapters    │  │
                                                 │  │  (plugins +   │  │
                                                 │  │  processors)  │  │
                                                 │  └──────┬────────┘  │
                                                 └─────────┼───────────┘
                                                           │ depends on
                                                           ▼
                                                 ┌─────────────────────┐
                                                 │   daw-acoustics     │
                                                 │  (standalone lib)   │
                                                 └─────────────────────┘
```

* `daw-acoustics` has **no dependencies** on `daw-core` or `daw-app`. It
  depends only on `daw-sdk` for common interface contracts (e.g.
  `RoomSimulator`, `ImpulseResponse`) and remains a reusable, standalone
  library with its own test suite.
* `daw-core` has a Maven dependency on `daw-acoustics` and hosts the
  adapter classes that wrap acoustics processors as
  `com.benesquivelmusic.daw.sdk.audio.AudioProcessor` implementations
  usable in the mixer insert chain.

## Adapter classes

| Adapter | Package | Wraps |
|---------|---------|-------|
| [`AcousticReverbProcessor`](../daw-core/src/main/java/com/benesquivelmusic/daw/core/dsp/acoustics/AcousticReverbProcessor.java) | `daw-core/.../dsp/acoustics` | `daw-acoustics` Householder FDN with room-dimension-aware delay lines and frequency-dependent T60 absorption |
| [`BinauralMonitoringProcessor`](../daw-core/src/main/java/com/benesquivelmusic/daw/core/spatial/binaural/BinauralMonitoringProcessor.java) | `daw-core/.../spatial/binaural` | `daw-acoustics` FDN with spherical-Fibonacci reverb sources panned to stereo for headphone monitoring |

Both adapters implement `AudioProcessor` and are therefore usable as
mixer insert effects, via the spatial panner, or in any signal chain that
accepts the SDK `AudioProcessor` contract.

## Built-in plugins

The adapters are exposed as first-class built-in plugins, added to the
sealed `BuiltInDawPlugin` permits clause:

| Plugin | ID |
|--------|-----|
| [`AcousticReverbPlugin`](../daw-core/src/main/java/com/benesquivelmusic/daw/core/plugin/AcousticReverbPlugin.java) | `com.benesquivelmusic.daw.builtin.acoustic-reverb` |
| [`BinauralMonitorPlugin`](../daw-core/src/main/java/com/benesquivelmusic/daw/core/plugin/BinauralMonitorPlugin.java) | `com.benesquivelmusic.daw.builtin.binaural-monitor` |

Both appear in the Plugins menu alongside existing built-in effects and can
be inserted on any mixer channel.

## What this integration does **not** do

The following are explicit non-goals (see the originating issue):

* Replace the existing `ReverbProcessor` in `daw-core`. It remains as a
  lower-CPU Schroeder–Moorer alternative.
* Real-time room-geometry editing that feeds the audio reverb. The
  telemetry visualisation in `SoundWaveTelemetryEngine` remains separate
  from audio processing.
* Ambisonics encoding/decoding in the mixer, or head-tracking for binaural
  rendering — these are separate stories.

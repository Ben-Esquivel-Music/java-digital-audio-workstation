# Java 26 Setup and GC Tuning for the DAW

This document covers the Java 26 runtime expectations for the DAW and, most
importantly, the **Garbage Collector tuning profile** used to eliminate
audio-thread dropouts. It is the companion to user-story 204
(*ZGC Tuning Profile and Buffer Pool Recycling to Eliminate Audio-Thread
GC Pauses*).

---

## 1. Required JDK

* **Java 26** (Temurin or any HotSpot-based distribution).
* See [`.github/instructions/java26-setup/SKILL.md`](../.github/instructions/java26-setup/SKILL.md)
  for the step-by-step install recipe used by CI and the Copilot agent.
* Maven 3.9.14+ (the parent POM pins `--release 26` and `--enable-preview`).

---

## 2. Why the audio thread is special

The real-time audio callback runs on a dedicated OS thread with a hard
deadline: produce the next buffer **before** the hardware needs it. At
48 kHz / 64-sample buffers this deadline is ~1.33 ms. Any GC pause that
exceeds the buffer period produces an audible dropout ("xrun").

Two things combine to cause dropouts in a pure-Java DAW:

1. **GC pauses.** The default G1 collector delivers <200 ms pauses, which
   is unacceptable for audio.
2. **Allocation on the audio thread.** Even with ZGC, an allocation on
   the audio thread can trigger a TLAB refill, a stack-walk for safepoints,
   or (worst case) a synchronous page fault — each of which blows past
   the buffer deadline.

The DAW addresses both: it ships a ZGC tuning profile (`zgc.conf`) **and**
enforces an allocation-free audio thread through buffer pooling and the
`@RealTimeSafe` contract test (see story 109 / `RealTimeSafeContractTest`).

---

## 3. The `zgc.conf` JVM options file

`zgc.conf` is packaged as a classpath resource inside `daw-app` and is
materialized into the user's settings directory by `DawLauncher` on first
run. Pass it to the JVM via `@<settings>/zgc.conf`:

```bash
java @$HOME/.config/java-daw/zgc.conf -jar daw-app.jar
```

### Flags and rationale

| Flag | Purpose |
|------|---------|
| `-XX:+UseZGC` | Enable the Z Garbage Collector (sub-millisecond pause targets up to 16 TB heap). As of JDK 24 (JEP 490) ZGC is generational by default — enabling ZGC automatically selects the generational collector (JEP 439, JDK 21). The old `-XX:+ZGenerational` flag was removed in JDK 24. |
| `-XX:-ZUncommit` | Never return heap pages to the OS. Uncommit incurs `madvise` cost; reacquiring pages can stall the audio thread. |
| `-XX:+AlwaysPreTouch` | Touch every page of the committed heap at startup so page faults never occur on the audio thread. Startup is slightly slower; steady-state is deterministic. |
| `-Xms${sessionMem}` | Fixed initial heap size — equal to `-Xmx` to disable heap expansion pauses. `${sessionMem}` is substituted by `DawLauncher` from the session configuration (`2G`, `4G`, `8G` are typical). |
| `-Xmx${sessionMem}` | Fixed maximum heap size. Combined with `-Xms` this gives a fully pre-touched, non-uncommitting heap. |

### Tradeoffs

* **Memory footprint grows.** A 4 GB pre-touched heap that never uncommits
  occupies 4 GB of RAM for the lifetime of the process. On a workstation
  this is a non-issue; on a laptop with 8 GB RAM you should size
  `${sessionMem}` conservatively (2 GB is a good default).
* **Startup is slower.** `AlwaysPreTouch` forces the JVM to touch every
  heap page at startup, which on a 4 GB heap adds ~0.5–1 s to launch.
* **Throughput vs. latency.** Generational ZGC costs a few percent of
  throughput relative to G1 in return for sub-millisecond pauses. The DAW
  does not care about throughput on the audio thread; it only cares about
  worst-case latency.

### Alternatives considered (non-goals for story 204)

* **Shenandoah** — similar pause targets via concurrent compaction.
  Generational Shenandoah (JEP 521, JDK 25) is viable, but ZGC has first-party
  support on more platforms and is easier to tune. Explicitly a non-goal.
* **JVM CPU pinning / thread isolation** — OS-level; out of scope.
* **Native JNI audio thread** — the audio thread stays on the JVM.

---

## 4. Audio-thread allocation discipline

The `zgc.conf` flags are necessary but not sufficient. The DAW further
enforces that the audio thread performs **zero heap allocation** at steady
state:

* All audio buffers are acquired from `AudioBufferPoolRegistry`, keyed by
  `(channelCount, blockSize, precision)`. The registry supports both
  on-heap pools (`AudioBufferPool`) and **off-heap FFM-backed pools**
  (`NativeAudioBufferPool`, JEP 454). FFM buffers live outside the GC
  heap — ZGC does not scan them — and are freed deterministically when
  the registry is `close()`d, eliminating both GC pressure and the
  non-determinism of finalization / `Cleaner`.
* All MIDI events are acquired from `MidiEventPool` (mutable `MidiEvent`
  holders are recycled, not re-allocated).
* All `XrunEvent` and `LatencyTelemetry` snapshots are emitted through
  per-type ring buffers that own pre-allocated mutable snapshot holders
  — the callback fills fields in-place rather than constructing a new
  record each cycle.
* `@RealTimeSafe` annotates every method on an audio-thread code path.
  The contract test (`RealTimeSafeContractTest`) fails the build if any
  `@RealTimeSafe` method is `synchronized`, declares varargs, or takes /
  returns a boxed primitive.

### `RealtimeAllocationDetector` (debug mode)

In debug builds, `RealtimeAllocationDetector` instruments the audio thread
using `com.sun.management.ThreadMXBean.getThreadAllocatedBytes(long)` and
logs any non-zero per-callback delta. It is a no-op in release builds.
Use it during plugin development — it will surface accidental autoboxing,
`toString()` calls, iterator allocation, or varargs faster than profiling.

---

## 5. Benchmarking

The target for the stress session (**64 tracks × 4 inserts, 48 kHz,
64-sample buffer**) is a **99.99th-percentile callback time strictly
under the buffer period** (1.333 ms). With `zgc.conf` enabled and the
audio thread verified allocation-free, a modern workstation comfortably
clears this target.

To reproduce locally:

```bash
# 1. Install Java 26 (see skill file).
# 2. Launch with the ZGC profile:
java @target/settings/zgc.conf -jar daw-app/target/daw-app.jar \
     --benchmark=stress --tracks=64 --inserts=4 --sample-rate=48000 --buffer=64
# 3. Compare against the baseline (default G1, no buffer pool):
java -jar daw-app/target/daw-app.jar --benchmark=stress ...
```

JFR (`-XX:StartFlightRecording=duration=60s,filename=stress.jfr`) is the
recommended tool for capturing callback timings.

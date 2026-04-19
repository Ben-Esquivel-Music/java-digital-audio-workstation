---
title: "Crash-Safe Audio Thread Plugin Isolation"
labels: ["enhancement", "audio-engine", "reliability", "plugins"]
---

# Crash-Safe Audio Thread Plugin Isolation

## Motivation

Third-party plugin code runs on the audio callback thread. A `NullPointerException`, `ArithmeticException`, or outright JVM crash inside a buggy CLAP plugin currently kills playback and in the worst case takes the whole process down with it. Every mature DAW guards against this: Reaper's "Plugin Sandbox," Bitwig's out-of-process plugin hosting, Pro Tools' DAE that catches crashes and disables the offending insert. The impact on a user who is 3 hours into a recording session and loses playback to a plugin crash is catastrophic.

The existing `ProcessorRegistry` and `InsertEffectRack` bracket each plugin invocation with a known call site, which is enough to install a structured supervisor that catches exceptions, zeros the buffer, disables the insert, and emits a notification — keeping the rest of the session alive.

## Goals

- Add `PluginInvocationSupervisor` in `com.benesquivelmusic.daw.core.plugin` that wraps each `DawPlugin.process()` call with a try/catch for `Throwable` (documented narrow exception).
- On exception: zero the output buffer for that plugin's slot, flip the slot's `bypassed` flag, record a `PluginFault(pluginId, exceptionClass, stackTrace, clock)` record, and continue processing the rest of the chain.
- Publish `PluginFault` events on a `Flow.Publisher<PluginFault>` that the UI subscribes to and surfaces via `NotificationManager` ("Plugin X was bypassed due to an error — click for details").
- Add a `PluginFaultLogDialog` with the per-fault stack trace and a "Re-enable" button that clears the bypass (if the user fixed the problem or wants to try again).
- Track per-plugin fault count; when a plugin faults > 3 times in a single session, keep it bypassed and mark it "quarantined" with an explicit re-arm action.
- Persist the fault log to `~/.daw/plugin-faults.log` for cross-session diagnostics.
- Unit tests inject a throwing processor and assert the session continues, the slot bypasses, and the fault is logged.
- Document the limitation: JVM-level crashes (OOM, segfault in JNI) are not catchable and require process-level isolation (future story).

## Non-Goals

- Out-of-process plugin hosting (a separate, much larger story).
- Automatic root-cause diagnosis or AI-driven fix suggestion.
- Recovery of already-distorted audio before the fault was detected.

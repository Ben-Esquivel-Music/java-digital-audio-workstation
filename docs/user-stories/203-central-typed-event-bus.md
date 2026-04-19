---
title: "Central Typed Event Bus with Backpressure and UI-Thread Dispatch"
labels: ["refactoring", "architecture", "events", "concurrency"]
---

# Central Typed Event Bus with Backpressure and UI-Thread Dispatch

## Motivation

Once the event hierarchy is in place (story 202), it needs a distribution mechanism. Today each emitter runs a private list of listeners; each emission is a synchronous call on the caller's thread. For audio-thread-originating events (xruns, meter updates), this forces UI code onto the audio thread — a recipe for glitches. A central event bus with typed subscriptions, backpressure, and explicit dispatch-to-UI-thread is the established solution.

## Goals

- Add `EventBus` interface in `com.benesquivelmusic.daw.sdk.event`: `<E extends DawEvent> Flow.Publisher<E> subscribe(Class<E> type)`, `publish(DawEvent event)`.
- Implementation in `com.benesquivelmusic.daw.core.event` backed by `java.util.concurrent.SubmissionPublisher` with bounded buffer (configurable default 256) and overflow strategy `DROP_OLDEST` for high-rate events, `BLOCK` for critical events.
- Per-subscription dispatch mode: `ON_CALLER_THREAD`, `ON_UI_THREAD` (routes through `Platform.runLater`), `ON_VIRTUAL_THREAD` (story 205) for work-generating subscribers.
- Typed convenience subscriptions: `bus.on(TransportEvent.Started.class, ev -> …)`.
- Emitters publish only; do not maintain their own listener lists.
- Instrumentation: per-event-type throughput counters accessible via a debug view; slow subscribers (> 1 ms average dispatch) flagged.
- Retire the ad-hoc listener interfaces from story 202's migration step by step.
- Tests: a publisher submitting 100k events drives subscribers deterministically; overflow drops oldest per config; UI-thread dispatch correctly routes onto the FX thread and not the caller thread.

## Non-Goals

- Persistent/durable event bus (Kafka, Pulsar) — in-memory only.
- Cross-process event distribution.
- Event replay facilities (beyond test utilities).

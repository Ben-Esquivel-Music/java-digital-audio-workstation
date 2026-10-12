---
title: "Stable Audio Device Identity for Hot-Plug Matching"
labels: ["enhancement", "audio-engine", "reliability", "recording", "windows"]
---

# Stable Audio Device Identity for Hot-Plug Matching

## Motivation

A device's identity in the SDK is `DeviceId(String backend, String name)` — the backend that owns it and the friendly name it reports (`DeviceId.java`). That is the key story 326 persists for a track's input device and the key story 327's device-loss chain matches on: `DefaultAudioEngineController.sameDevice(DeviceId watched, DeviceId other)` (package-private static since story 327's second fix round; `matches` before it) accepts `watched.equals(other)` and otherwise `watched.name().equals(other.name())` — a friendly-name match across backends, which story 214's Goals allowed as the fallback "when serial information is unavailable". Serial information is never available: no backend exposes one, so the fallback is the rule whenever the backend differs, and the name is the whole key whenever it does not.

Story 327 made the consequence visible. On a `DeviceArrived` that is `sameDevice` as the watched device, `onDeviceArrived` reopens the stream and ends `STOPPED`; `EngineStateBridge` posts that state to the FX thread, `TransportController.onEngineState` maps the first `STOPPED` or `RUNNING` delivered after a `DEVICE_LOST` with the stream open to `RecordCoordinator.onLostDeviceReturned`, which tests the delivered device with the same `sameDevice` and, for a match, seeks the transport to the rescued take's start anchor, tells the user *"`<device>` reconnected. Tracks are still armed; press Record to continue."* and settles the record state to IDLE. Two identical interfaces — the same model, under a driver whose device name carries no serial, as class-compliant and many vendor drivers do — are the same `DeviceId`. Lose one mid-take, and the other's arrival or re-enumeration is the lost device's return: the engine reopens on the wrong unit, the record state leaves DEVICE_LOST, the message says the lost interface is back, and the next Record records from the unit the engineer did not patch. The book's "Device returned" row (Recording Reliability book §5.2) requires *same identity*; a name is not an identity when two units share it. Story 327 documents this as its first known limitation and leaves `sameDevice` unchanged in behaviour.

## Goals

- **A stable hardware key, where the host exposes one.** Extend the device identity with the backend's stable endpoint key and, where enumerable, the unit's vendor id, product id and serial — the OS endpoint/instance identity on Windows for WASAPI, the device UID on macOS, the hardware id on ALSA/JACK — reported by the backend's device enumeration and captured in the watched identity at stream open. ASIO reports a driver name, not a unit; an ASIO identity carries whatever the OS exposes for the unit the driver opened, or nothing.
- **Additive, never a new persisted schema break.** `DeviceId(backend, name)` stays valid: a project or settings file saved before this story loads unchanged, a track's input identity from story 326 still resolves, and a device with no key is matched as today when its name is unambiguous.
- **Ambiguity stays DEVICE_LOST.** The match: equal keys match; a name-only match (no key on one side or both) is accepted only while exactly one enumerated device of that backend carries that name; two or more candidates, or a key that differs, do not match — the engine stays `DEVICE_LOST`, the record state stays DEVICE_LOST, and the remediation names the ambiguity (*"two devices named `<name>` are present"*) and offers Audio Settings so the user picks the unit explicitly.
- **The unit is nameable.** Where a key exists, the status and the notifications of the rescue (*"Audio device lost — `<device>`"*, the Review message, the reconnect INFO) name the unit — the friendly name plus a short serial suffix — so two identical interfaces are told apart on screen.
- **One matcher.** `sameDevice` — already the one package-private static predicate behind the controller's loss, arrival and format-change paths and the coordinator's return (story 327 fix round r2) — becomes a unit-tested function (or a small `DeviceIdentity` value in the SDK) used also by the per-track input resolution of story 326, so the hot-plug chain and capture routing agree on what "the same device" means.

## Goals — Tests

- **Two identical units**: two mock devices with the same name and different keys; record on A, `simulateDeviceRemoved(A)`, `simulateDeviceArrived(B)` → engine state and record state stay DEVICE_LOST, the stream stays closed, the remediation names the ambiguity; `simulateDeviceArrived(A)` → reopen, record state IDLE, armed tracks kept, transport at the rescued take's anchor.
- **Unique legacy name**: a device with no key and a unique name matches on arrival as today (the existing `DefaultAudioEngineControllerTest` and `DeviceLossRescuesTheTakeThroughTheEngineBridgeTest` keep passing unchanged).
- **Persisted identities**: a project saved with a story-326 `DeviceId(backend, name)` input identity loads, resolves and records from that device; a settings file naming the session device by name still selects it.
- **Matcher unit tests**: equal keys; equal names with one candidate; equal names with two candidates; different backends with the same name and no keys (today's cross-backend fallback) — each asserted directly against the matcher.
- **Round trip**: the extended identity serializes and parses, with and without a key, on Windows and Linux.

## Non-Goals

- **Producing `DeviceRemoved`/`DeviceArrived` on real backends** — story 338's callback-heartbeat watchdog and OS device watcher (`FAILURE_SURFACING_DESIGN_BOOK.md`); today only `MockAudioBackend` publishes them.
- **Aggregating devices or riding out the loss of one** — story 092 territory, and the Recording Reliability book §5.4 clock-domain rule.
- **Driver-reported channel names and the routing UI** — stories 092 and 215.
- **The rescue sequence itself** — story 327; this story changes what "the same device" means, not what happens when it is lost or returns.

## Technical Notes

- Files: `DeviceId.java` (daw-sdk; the record and its `defaultFor`), the backends' device enumeration (`AudioDeviceInfo` and the per-backend enumerators), `DefaultAudioEngineController.sameDevice`/`onDeviceRemoved`/`onDeviceArrived` and its `watchedDevice` (daw-app), `TransportController.onEngineState` and `RecordCoordinator.onLostDeviceReturned` (already on `sameDevice`; unchanged in behaviour, messages gain the unit's name), story 326's track input identity resolution (`CaptureRoutingPlan` and the per-track input dialog's preselection).
- Keep the matcher pure and the key optional: `Optional` for an absent key, never a sentinel string; the persisted form is additive so `ProjectSerializer`/`ProjectDeserializer` round trips of story 326 need no migration.
- The ambiguity remediation rides the controller's `NotificationManager` (the toast sink of story 327 until story 339's injection) and should offer the Audio Settings action once story 339's notification actions exist.
- Cross-refs: existing 214 (the fallback this story closes), 218 (reopen ordering), 326 (the persisted identity), 327 (the chain that depends on the match), 338 (the hardware feeds), 339 (notification injection and actions), 092/215 (routing and naming surfaces). Book: Recording Reliability §5.2 "Device returned" (guard: same identity), §5.5 step 6.

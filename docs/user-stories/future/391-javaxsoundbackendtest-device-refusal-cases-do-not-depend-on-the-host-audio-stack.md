---
title: "JavaxSoundBackendTest's Device-Refusal Cases Do Not Depend on the Host's Audio Stack"
labels: ["bug", "testing", "audio-engine", "sdk"]
---

# JavaxSoundBackendTest's Device-Refusal Cases Do Not Depend on the Host's Audio Stack

## Motivation

Five `JavaxSoundBackendTest` cases pin the Java Sound backend's rollback contract by opening the default device with a 192-channel format and expecting the open to be refused: `unopenableOutputLineFailsTheOpenAndRollsBack`, `aFailedOpenLeavesTheRungReopenableInsteadOfStuckOpen`, `openIsRefusedWhileALineFromAFailedCloseIsStillHeld`, `aLineRetainedByAFailedOutputRollbackRefusesTheNextOpenInsteadOfLeaking` and `aRefusedOutputLineReportsNoCaptureChannelsUnderEitherRequirement`. Each builds `new JavaxSoundBackend()`, which is `this(JavaSoundAccess.SYSTEM, …)`, and opens `DeviceId.defaultFor(JavaxSoundBackend.NAME)`. `resolveMixerInfo` returns `null` for the default device, so the open goes to `AudioSystem.isLineSupported` and `AudioSystem.getSourceDataLine` on the host's real default device.

The cases assume that every host refuses that open, and their comments say so: "the output-line open is refused deterministically on every machine, headless or not", and "The one full-open assertion that is host-independent". The premise is false on a Linux host whose ALSA default PCM is served by a sound server. On such a host, in story 332's Linux validation, the open was accepted and all five cases failed with "Expecting code to raise a throwable". On the same host with `ALSA_CONFIG_PATH` pointed at an empty file (no default PCM, as on the project's Linux CI runner), the class passed 57 of 57. The exact ALSA mechanism has not been verified: a plugin that converts or accepts any channel count would explain it, and desktop Linux hosts whose ALSA default is a sound-server plugin likely behave the same way, but that is untested beyond the one host.

On such a host the defect is worse than a red test. A "successful" open leaves a started line open on the developer's real default output: the assertion throws before any `close()`, so the line stays open for the rest of the test JVM.

The product is not at fault. A rung whose host accepts the format can produce sound, and `requireActualFormat` already checks the format the line actually opened. The tests assert a host-independent rollback contract through a host-dependent path, and the injectable seam that fixes them already exists in the same test class.

## Goals

- **The seam refuses, not the host.** The five cases build their backend through the existing package-private `JavaxSoundBackend(JavaSoundAccess)` constructor with a `TestJavaSoundAccess` that advertises no playback line, for example `new TestJavaSoundAccess(new Mixer.Info[0], null, null)`. Its `supportsSourceLine` then answers false (`supportsAdvertisedFormat(null, …)` is false), so `selectOutputFormat` throws the `IllegalArgumentException` that `rollBackFailedOutputOpen` wraps as "Java Sound output line unavailable: …", the same path a host with no default device takes today.
- **Every assertion stays.** Every assertion and message check of the five cases is unchanged. In the two retained-line cases, the step "once the retained line is gone the open proceeds to the device" then reaches the stub's refusal. `plantLine` and `markStreamOpen` are confirmed to work on a backend built through the seam.
- **The comments say what decides.** The comments that call the refusal host-independent, or say the open "reaches the real device", say instead that the refusal is the seam's.
- **No other case reaches the host's device by accident.** Every other `new JavaxSoundBackend()` site in the class that can reach `JavaSoundAccess.SYSTEM`'s line acquisition or open either uses the seam or is an explicitly named, opt-in host-integration case that tolerates either host outcome and closes any line it opens.

## Acceptance Criteria

1. `JavaxSoundBackendTest` passes 57 of 57 on (a) a Linux host whose ALSA default PCM is served by a sound server, (b) the same host with `ALSA_CONFIG_PATH` pointed at an empty file, (c) Windows and (d) the project's Linux CI.
2. No case leaves a line open on the host's device.
3. The five cases keep every assertion and message check.
4. `JavaxSoundBackend` itself is unchanged.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **The five refusal cases through the seam**: each builds its backend with a `TestJavaSoundAccess` that advertises no playback line and passes with its assertions unchanged, on every host of criterion 1.
- **The retained-line cases reach the stub**: in `openIsRefusedWhileALineFromAFailedCloseIsStillHeld` and `aLineRetainedByAFailedOutputRollbackRefusesTheNextOpenInsteadOfLeaking`, the open after the retained line is released fails with "output line unavailable" from the stub's refusal.
- **Fault check — the seam decides**: make the refusing access advertise the 192-channel format and hand back a stub line. The five cases turn red ("Expecting code to raise a throwable"), which proves that the seam, not the host, decides. Restore the access afterwards.
- **The sweep**: every other `new JavaxSoundBackend()` case that can reach a host line either builds through the seam or is a named, opt-in host-integration case that passes whichever way the host answers and closes any line it opened.

## Non-Goals

- Any change to `JavaxSoundBackend` itself.
- Pinning down the exact ALSA mechanism on each Linux distribution.
- Host-integration coverage of real devices beyond the opt-in cases the sweep keeps.

## Technical Notes

- Code locators: `JavaxSoundBackendTest`: the five cases, with the "deterministically on every machine" comment in `unopenableOutputLineFailsTheOpenAndRollsBack`, the "reaches the real device" comments in the two retained-line cases and the "host-independent" comment in `aRefusedOutputLineReportsNoCaptureChannelsUnderEitherRequirement`; the other `new JavaxSoundBackend()` sites (17 when this story was written, from about :1162 to :1833); `TestJavaSoundAccess` and its `supportsAdvertisedFormat`; `plantLine`; `markStreamOpen`. `JavaxSoundBackend`: the public no-argument constructor (`this(JavaSoundAccess.SYSTEM, …)`), the package-private `JavaxSoundBackend(JavaSoundAccess)`, `resolveMixerInfo` (`null` for the default device), `selectOutputFormat`, `rollBackFailedOutputOpen`, `requireActualFormat`. `JavaSoundAccess.SYSTEM` (`AudioSystem.isLineSupported`, `AudioSystem.getSourceDataLine`).
- Observed on story 332's Linux validation host: the five cases failed at the assertion that expects the device's refusal (:1004, :1030, :1323, :1427 and :1648). With `ALSA_CONFIG_PATH` pointed at an empty file, `mvn -B -pl daw-sdk test -o … "-Dtest=JavaxSoundBackendTest"` passed 57 of 57; unsetting the sound server's environment variables alone still left the five failures.
- Ruled by the studio engineer at PR 988's close-out (story 332) as a future story rather than a fix in that pull request: PR 988 does not touch `JavaxSoundBackend` or its test, and the class is green on Windows and on the project's Linux CI.
- Cross-refs: story 316 (the retain-on-failure rollback the retained-line cases pin), story 332 (where this was found; its full-suite validation).

## Status

- Deferred

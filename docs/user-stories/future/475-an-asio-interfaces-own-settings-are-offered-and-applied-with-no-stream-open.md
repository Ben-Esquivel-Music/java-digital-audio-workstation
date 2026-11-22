---
title: "An ASIO Interface's Own Settings Are Offered and Applied With No Stream Open"
labels: ["bug", "audio-engine", "asio", "ui"]
---

# An ASIO Interface's Own Settings Are Offered and Applied With No Stream Open

## Motivation

Audio Settings asks the audio driver what the interface offers: story 220 has it ask an ASIO driver for its buffer sizes and sample rates when the dialog opens, story 216 gives it a Clock Source list filled from the driver's clock sources, and story 212 gives it a button that opens the driver's control panel. Story 310 loads an ASIO driver for the lifetime of an open stream: `AsioBackend.open` loads it and `AsioBackend.close` unloads it. While no driver is loaded, `AsioCapabilityShim` answers each of those queries as if the interface had nothing to offer (`getBufferSize`, `canSampleRate`, `getSampleRate`, `setSampleRate`, `isControlPanelAvailable` and `isClockSourceAvailable` all check `AsioDriverShim.isDriverLoaded()`). So when the person opens Audio Settings with no stream open on the interface, with the transport stopped and no instrument insert keeping a stream running:

- the Clock source list is empty and unavailable, with the tooltip "This backend does not expose clock-source selection.", although the interface has clock sources;
- the Buffer Size (frames) list offers 32 to 2048 frames in powers of two (`BufferSizeRange.DEFAULT_RANGE`) instead of the sizes the driver accepts; a saved size that is not one of them is replaced by 256, with "Persisted Buffer Size (frames) <n> is unavailable for the selected device; falling back to 256.", and an Apply or an OK then saves and applies 256; a listed size the driver does not accept makes the next open on the interface fail;
- the Sample Rate (Hz) list offers the six standard rates from 44.1 to 192 kHz as available, and an Apply of another rate is refused with "Sample rate <n> Hz was rejected by the audio driver." although the driver was not asked;
- "Open driver control panel" is unavailable.

While a stream is open on the interface (during playback, for instance), Audio Settings lists the driver's clock sources and buffer sizes and the sample rates it accepts, sends a chosen clock source and an applied rate to the driver, and opens the driver's control panel on a driver that has one; in the case above it does none of these, and a session is set up with the transport stopped. So a driver that does not take a change of rate while it streams cannot be given another rate from Audio Settings at all, and one that rejects a clock change while streaming (story 222) cannot be given another clock source from it. A driver that reports 96 to 384 frames in steps of 96 (story 220's example) loses its saved buffer size to 256 at an OK, and the next Play does not open on it; and from 256, the buffer size used until one is saved, no Play opens on it either, and Audio Settings, which then lists the default sizes, offers none that it accepts. Story 338's clock badge hides its dim closed view at an Apply made with no stream open, because its read finds no driver to prove the clock (story 338, ruling E169), and story 338's owner's checks set these settings during playback.

## Goals

- With no stream open on an ASIO interface, Audio Settings shows what the driver reports, as it does while a stream runs: its clock sources with its current one, the buffer sizes and the sample rates it accepts, and an available "Open driver control panel" on a driver that offers a panel.
- A clock source chosen, or a sample rate or a buffer size applied, in Audio Settings with no stream open is what the interface runs at the next stream on it. A value the driver refuses is refused with a notice, and a notice says that the driver rejected a value only when the driver was asked.
- A saved buffer size that the driver accepts is kept when Audio Settings opens with no stream open.
- The clock badge's dim closed view stays at an Apply made with no stream open that changes neither the device, the clock source nor the rate (story 338, ruling E81).

## Constraints

- Story 310's one loaded ASIO driver per process holds, and with no stream open and Audio Settings closed no ASIO driver stays loaded, as today (a release that story 316 defers behind a driver call that has not returned, and a probe of an input's channel count, which loads the driver and releases it, aside).
- A stream that opens while Audio Settings is open (the reopen of an Apply during playback, for instance) opens as it does today.
- While a stream runs on the interface, Audio Settings behaves as it does today.
- No driver call runs on the JavaFX thread or the audio callback thread, and none waits without a bound but the control panel's, which waits for the person (story 316).
- The clock badge still keeps a closed view at a read only when the read proves it names the clock on show (story 338, ruling E81).

## Acceptance Criteria

1. With no stream open, Audio Settings on an ASIO interface lists the driver's clock sources with its current one selected, the buffer sizes it reports and the sample rates it accepts, and "Open driver control panel" is available on a driver that offers a panel.
2. A sample rate applied with no stream open reaches the driver, and the next stream on the interface runs at it; a rate the driver refuses gives "Sample rate <n> Hz was rejected by the audio driver." and changes nothing else.
3. A clock source chosen with no stream open is the interface's source at the next stream, and the clock badge names it once read.
4. A saved buffer size that the driver accepts and that is not a power of two is kept when Audio Settings opens with no stream open, with no "Persisted … falling back to …" notice, and the next stream opens at it.
5. With no stream open, an Apply of Worker Pool Size keeps the clock badge's dim closed view.
6. With no stream open and Audio Settings closed, no ASIO driver stays loaded (a release that story 316 defers behind a driver call that has not returned, and a probe of an input's channel count, which loads the driver and releases it, aside).
7. On hardware (the owner's): with the transport stopped and no stream open, the Audio Settings steps of story 338's clock checks 5 and 9 give what the Goals name, and clock check 11 keeps the dim closed view.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a Windows or a non-Windows host.

- daw-sdk tests with a fake ASIO capability shim that answers only while a driver is loaded, as `AsioCapabilityShim` does, for criteria 1, 2, 3 and 6.
- daw-app tests of Audio Settings on such a fake for criteria 1, 2 and 4, and a test of the clock badge on it for criterion 5.
- `AsioBackendCapabilityTest`, `AsioBackendClockSourceTest`, `AsioBackendDriverLifecycleTest`, `SettingsDialogTest`, `SettingsDialogApplyBehaviorTest`, `ClockStatusIndicatorTest` and `ClockBadgeDuringATakeTest` stay green.

## Non-Goals

- A rate changed in the driver's own control panel: future story 474.
- The rate an ASIO open asks of the device when no rate was applied in Audio Settings (future story 474, its Non-Goals).
- WASAPI and CoreAudio, whose streaming is not built.

## Technical Notes

- daw-sdk: `AsioBackend` (`open`, `close`, `bufferSizeRange`, `supportedSampleRates`, `setSampleRate`, `clockSources`, `openControlPanel`), `AsioCapabilityShim`, `AsioDriverShim` (`isDriverLoaded`).
- daw-app: `DeviceEnumerationTask`, `SettingsDialog` (`applyDeviceEnumerationResult`, `updateAudioUtilityDisabledState`, the sample-rate step of the audio reconfigure), `DefaultAudioEngineController` (`setSampleRate`, `clockSources`, `bufferSizeRange`, `openControlPanel`), `ClockStatusIndicator`.
- Cross-refs: stories 212, 216, 220, 222 and 310; story 338 (rulings E81 and E169, and its Known limit); future story 474.

## Status

- Deferred

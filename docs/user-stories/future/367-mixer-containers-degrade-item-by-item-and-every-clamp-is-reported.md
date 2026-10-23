---
title: "Mixer Containers Degrade Item by Item on Open, and Every Clamp Is Reported"
labels: ["bug", "persistence", "mixer", "project-load", "error-handling"]
---

# Mixer Containers Degrade Item by Item on Open, and Every Clamp Is Reported

## Motivation

Story 330 made the project reader honest about the items it reads: a MIDI note, CC lane or event, an arpeggiator setting, a MIDI effect or a mixer assignment it cannot honour is logged at WARNING, counted in the open's announcement, and — when the next save would drop it — arms the degraded-open backup. It also fixed the one known way a VCA group refused the whole project (an unreadable or NaN `master-gain-db` now opens at 0 dB with a WARNING; a repeated VCA group id is skipped with a WARNING and arms the backup). The mixer containers around those items were left as they were, and several still disappear or change without a word (`ProjectDeserializer.parseMixer`, `parseBedBus`):

- **Silent drops.** A cue bus or a VCA group whose `id` is missing or malformed is skipped by a bare `continue` (`ProjectDeserializer.java:1471`, `:1477`, `:1524`, `:1530`). A cue bus that `CueBusManager.addCueBus` rejects — a repeated id or hardware output index — is swallowed (`:1505-1509`). A bed bus with an unknown `format` skips the whole bed-bus block, routings included (`:2805-2810`).
- **A bed routing whose format differs from the bus** is skipped silently (`:2844`). Its track id was already marked as routed, so a later, valid routing for the same track is then reported as a repeat instead.
- **Clamps nobody hears about.** Channel volume and pan (`applyMixerChannelAttrs`, `:1735-1739`), a cue bus's master gain (`:1485`), a cue send's gain and pan (`:1499-1500`), and a VCA gain outside its range, ±Infinity included (`:1565`), are clamped with no log line and no report.
- **NaN reaches the mix.** `parseDoubleAttr` returns NaN for `"NaN"` (`:2315-2325`), and `clampDouble` (`:2300-2302`) is `Math.max(min, Math.min(max, value))`, which returns NaN for NaN. The range checks of `MixerChannel.setVolume` and `setPan` (`MixerChannel.java:183-186`, `:203-206`), of `CueSend`'s constructor (`CueSend.java:31-35`) and of `CueBus`'s master gain (`CueBus.java:56-59`) are comparisons, all false for NaN. So `volume="NaN"` is stored, the channel's output becomes NaN, and NaN sums into the master — and stays in any recursive filter downstream until it is reset.
- **A NaN sample rate builds the project.** The same hazard sits outside the mixer, in the project's audio format: `parseDoubleAttr` returns NaN for `<audio-format sample-rate="NaN"/>`, and `AudioFormat`'s constructor checks `sampleRate <= 0`, which is false for NaN, so `ProjectDeserializer.parseDocument` builds a project whose sample rate is NaN (`Infinity` passes the same check). The metronome takes that rate (`Metronome`'s own `sampleRate <= 0` check passes it too), and so does every insert and analyzer the open creates (`InsertEffectFactory.createSlot`, the analyzers' `PluginContext.getSampleRate`). The rest of the element goes the other way: the reader builds the format from four parsed attributes (`sample-rate`, `channels`, `bit-depth`, `buffer-size`), and `AudioFormat`'s constructor throws `IllegalArgumentException` for any of them that is not positive, so `sample-rate="0"`, `"-Infinity"` or a negative rate, and a `channels`, `bit-depth` or `buffer-size` of 0 or less, refuse the whole project (story 361's unchecked-exception path).

For an engineer each of these is a mix that reopens different from how it was saved, with nothing to say so: a headphone mix that is gone, a fader that moved, or a session that plays silence or noise from one damaged value (Persistence Integrity book §2.4, rejection §9.4).

Separately, an analyzer view reopens on its defaults when one of its settings is unusable. `AnalyzerInsertPersistence.restoreConfiguration` returns at the first saved `fft-size`, `window-type` or `reference-pitch-hz` it cannot use — not an integer or not a power of two up to 65536, not a window type, not a number or not between 415 and 466 Hz — before `spectrum.reconfigure(fftSize, window)`, so a usable `fft-size` saved next to an unusable `window-type` is discarded too, and the next save writes the defaults over both. Its one WARNING says so: `<strip>: insert <k> analyzer <plugin-id> <attribute> '<value>' <reason>; the analyzer's saved settings are not applied and its defaults are used`. Analyzer settings are meter view preferences, not the mix: they never reach the audio, and story 330 logs them only, like a CC lane's height ratio (story 330's studio rulings: round 3, Q1, for the height ratio, and the docs review's D1 for analyzer settings). The harm is the usable setting thrown away with the unusable one.

## Goals

- **Every dropped container is reported.** A cue bus, VCA group, bed bus or bed routing the reader cannot keep logs one WARNING naming the container and why, and arms the degraded-open backup (story 330's rule: a loss the next save makes is backed up first).
- **Every clamp is reported.** A value the reader changes to bring it into range logs one WARNING with the saved and the applied value, is counted in the open's report, and arms the backup (story 330's studio ruling, round 3, Q1: clamps join the backup rule once they are reported). A value mapped to a bound that means the same thing (−Infinity dB to the −120 dB VCA floor; `VcaGroup.MIN_GAIN_DB`'s Javadoc: `Treated as "−∞ dB" (silence)`) is logged only.
- **NaN never reaches the audio model.** The setters and constructors above reject NaN, as `VcaGroup` does; the reader maps NaN to the attribute's default, with a WARNING. The audio format too: `AudioFormat` rejects a sample rate that is not finite, and the reader opens an `<audio-format>` attribute `AudioFormat` would reject — a `sample-rate` that is not a positive finite number (NaN, ±Infinity, 0 or negative), or a `channels`, `bit-depth` or `buffer-size` that is not positive — at the attribute's default (44100, 2, 16, 512) with one WARNING, logged only (such a value holds nothing to lose, as with story 330's NaN VCA gain).
- **An analyzer keeps its usable settings.** An analyzer keeps every saved setting it can use and defaults only the unusable one, with one WARNING; logged only, like a CC lane's height ratio (story 330, Q1): analyzer settings are meter view preferences, so an unusable one is never counted in the open's announcement and arms no backup. The WARNING names only the setting it defaults, with its value as saved, and what it uses instead, for example `… window-type 'NOT_A_WINDOW' is not a window type; HANN is used`; today's ending, `; the analyzer's saved settings are not applied and its defaults are used`, goes with the behaviour it describes. The tuner's reference pitch is its only setting, so the tuner behaves as today, and its WARNING keeps the raw value and takes the same form.
- **A bed routing that is skipped does not block the next one.** A routing is marked as used only once it is applied.

## Acceptance Criteria

1. A file with a cue bus whose id is missing, one whose id is malformed, and two cue buses on one hardware output opens with one WARNING per dropped bus, keeps the first bus on that output, and its first save writes a degraded-open backup.
2. The same for VCA groups with a missing or malformed id, a bed bus of unknown format, and a bed routing whose format differs from its bus.
3. `volume="NaN"`, `pan="NaN"`, a NaN cue-send gain, a NaN cue-bus master gain, `<audio-format sample-rate="NaN"/>` and any other `<audio-format>` attribute `AudioFormat` would reject open at the attribute's default with a WARNING; `MixerChannel.setVolume(Double.NaN)`, `new AudioFormat(Double.NaN, 2, 16, 512)` and the others throw `IllegalArgumentException`.
4. Every clamp the reader applies to a mixer value is logged with the saved and applied value and counted in the open's report; the announcement gains at most one new clause for them, worded by the studio engineer in story 330's form (counts only).
5. A clean file opens with no WARNING and no backup, as today.
6. An analyzer saved with a usable and an unusable setting opens with the usable one applied and only the unusable one at its default, with one WARNING that names only the defaulted setting and what is used instead; nothing is counted and no backup is armed.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Dropped containers**: one fixture per container case above; the open succeeds, the WARNING text is exact, `ProjectDeserializer.losesSavedContent()` is true, and the first save writes one `project.daw.degraded.*.bak` byte-equal to the fixture.
- **NaN**: a channel with `volume="NaN"` renders a finite buffer after the open; each setter and constructor rejects NaN.
- **NaN audio format**: `<audio-format sample-rate="NaN"/>` and `sample-rate="Infinity"` each open with a 44100 Hz project, one WARNING and `losesSavedContent()` false, and an analyzer insert in the file is initialised at 44100 Hz; `new AudioFormat(Double.NaN, 2, 16, 512)` throws `IllegalArgumentException`. `sample-rate="0"`, `sample-rate="-Infinity"` and `channels="0"` open the same way at their defaults.
- **Clamps**: `volume="1.5"`, a cue send `gain="2"` and a VCA `master-gain-db="Infinity"` open clamped, each logged and counted; `ProjectDeserializer.losesSavedContent()` is true and the first save writes one `project.daw.degraded.*.bak`. A VCA `master-gain-db="-Infinity"` opens at −120 dB, logged only.
- **Analyzer settings**: a spectrum analyzer saved with `fft-size="8192"` and `window-type="NOT_A_WINDOW"` opens at 8192 with the default window. There is one WARNING, naming the strip, the insert and `window-type` with its value as saved, and ending with what is used instead (`… window-type 'NOT_A_WINDOW' is not a window type; HANN is used`); the announcement has no clause for it, `losesSavedContent()` is false, and the first save writes no `.bak`. This replaces `AnalyzerInsertPersistenceTest.aUsableSettingBesideAnUnusableOneIsNotAppliedEitherAndTheLineSaysSo`, which pins today's behaviour (the saved 8192 is not applied), and the class's `NOT_APPLIED` ending (`; the analyzer's saved settings are not applied and its defaults are used`), which every analyzer line that class asserts ends with today, goes with it.
- **Bed routing**: a routing with a mismatched format followed by a valid one for the same track keeps the valid one and reports only the mismatched one.
- **Clean**: the round-trip inventory project opens with no WARNING from the mixer readers.

## Non-Goals

- The VCA group NaN gain and repeated VCA group id: fixed in story 330.
- Return-bus structure and identity (story 364).
- Unchecked exceptions from the deserializer in general (story 361's added criterion).

## Technical Notes

- Code locators: `ProjectDeserializer.parseMixer` (cue buses, VCA groups, channel links), `parseBedBus`, `applyMixerChannelAttrs`, `clampDouble`, `parseDoubleAttr`, `losesSavedContent`, `parseDocument` (the audio format); `AudioFormat`; `UnreadProjectItem`; `MixerChannel.setVolume` / `setPan`; `CueSend`; `CueBus`; `CueBusManager.addCueBus`; `VcaGroup.MIN_GAIN_DB`; `AnalyzerInsertPersistence.restoreConfiguration` / `keepDefaults`; `OpenAnnouncement`.
- Ruled by the studio engineer in story 330: round 2, R4 future item 6, round 4, Q8's future-story bullets, and the docs review's D1 (analyzer settings are meter view preferences, logged only). The NaN sample rate was the studio engineer's adjacent finding in that review, and a later ruling widened it to the whole `<audio-format>` element: a NaN rate throws nothing (it opens, and the hazard is the NaN in the model), while a non-positive value throws today, and both are values with nothing to lose, so both open at the attribute's default here. Story 361's unchecked-exception test therefore uses a throwing migration step, not a damaged audio format.
- Design references: Persistence Integrity design book §2.4, §6.3 (the degraded-open backup), rejection §9.4; `docs/project-migrations.md`, "The Degraded-Open Backup".
- Cross-refs: story 330 (the open report and backup), story 368 (the per-item detail surface), story 361 (failed opens).

## Status

- Deferred

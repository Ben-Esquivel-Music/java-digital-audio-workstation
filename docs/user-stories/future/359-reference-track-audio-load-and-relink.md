---
title: "Reference-Track Audio Loads and Relinks With the Project"
labels: ["enhancement", "persistence", "project-load", "audio"]
---

# Reference-Track Audio Loads and Relinks With the Project

## Motivation

A project's reference tracks reopen as settings without sound. `ProjectSerializer.buildReferenceTrackManager` writes each `reference-track` element — name, `source-file`, gain offset, loop region, integrated LUFS and, for an immersive reference, its format and per-channel trims — and `ProjectDeserializer.parseReferenceTrackManager` rebuilds a `ReferenceTrack` from it. Nothing then loads audio into the project's reference tracks: in production `ReferenceTrack.setAudioData` is called only on the temporary track `MatchEqPlugin` builds for itself. A reopened reference track holds no audio whether or not its file is on disk.

Story 329 reloads clip audio on open and reports missing clip sources with a relink, but it deliberately left reference tracks out, because a relink row would restore nothing while nothing loads their audio. `MissingAssetsReport.atOpen` partitions the missing reference-track files into `missingReferenceTrackPaths()`, which never count in the open's announcement and get no row in the Missing Assets report; `OpenProjectAudio.begin` only logs them once per open as a WARNING. The reference-track `source-file` is also outside the project-relative path rule (`ProjectPaths`): it is written as held, and the deserializer checks it with `Files.exists(Path.of(sourceFile))`, so a relative one is checked against the JVM's working directory, and a reference whose project folder moved reads as missing.

## Goals

- **Reference-track audio loads with the project.** It is decoded off the FX thread through the decode story 329's rehydrator shares with the importer (`ReferenceFileLoader`), and attached on the owner thread to the live `ReferenceTrack`, under the same generation fence; the open never waits for it, and loading it is not an edit.
- **Missing reference-track files are reported and relinkable.** A missing, unreadable or too-large reference-track file is a row of the open's Missing Assets report with the same labels, is counted in the open's announcement like a clip source, and can be relinked with Locate file, Locate folder and Skip.
- **Reference-track paths follow the project-relative rule.** A reference under the project folder is saved project-relative and one outside it absolute; an absolute path inside the folder still reads and is rewritten project-relative on the next save.

## Acceptance Criteria

1. Opening a project whose reference-track file is present gives that `ReferenceTrack` the file's decoded audio, at the rate the Resolution names, without a decode on the FX thread and without marking the project dirty.
2. Opening a project whose reference-track file is missing shows one Missing Assets row for it, labelled `Missing`, and the open's announcement counts it as a missing asset; the WARNING log of reference-track paths in `OpenProjectAudio.begin` goes away.
3. Relinking that row with Locate file or Locate folder attaches the located file's audio to the reference track, marks the project dirty and is not an undo-stack entry; the next save writes the new path project-relative when it lies under the project folder and absolute outside it. Skip leaves the reference track as it was and the project clean.
4. A reference track whose file is relative to the project folder reopens with its audio after the project folder is moved or copied elsewhere.
5. A project saved before this story reopens: an absolute `source-file` inside the project folder is read and rewritten project-relative on the next save; how a relative `source-file` written by an earlier version is read is stated in the Resolution.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Round trip**: a project with a reference track whose WAV file lies under the project folder is saved and reopened; the reference track's audio equals the file's decode, and the project is clean.
- **Missing file**: the file is deleted before the reopen; the report has exactly one row for it, the announcement counts it once, and nothing is logged as an uncounted reference-track path.
- **Relink**: Locate file to a copy under the project folder, and to one outside it; the audio is attached, the project is dirty, and the saved `source-file` is project-relative in the first case and absolute in the second.
- **Moved project**: a project whose reference-track file lies under its folder is copied to another folder and opened there; the reference track has its audio and nothing is reported missing.

## Non-Goals

- Changing how reference tracks are played, compared or level-matched; this story only gives them their audio back.
- SoundFont references, which are not checked on open today (the project file holds no impulse-response references yet).
- `MatchEqPlugin`'s own copy of the sample-rate conversion, which story 329 left as it is.

## Technical Notes

- The clip side this story extends (story 329): `ClipRehydrator` and `RehydrationSession` (core), `MissingAssetsReport`, `MissingAsset`, `AssetReference`, `AssetRelinker` and `MissingAssetSearch` (core), and `OpenProjectAudio`, `MissingAssetsDialog` and `AssetRelinkFlow` (app). A report row's holder today is a clip — `MissingAsset.Holder(clipId, trackName, clipName)` — and an asset is a clip source, `AssetReference.SingleFile` or `AssetReference.Take`, so a reference track needs a holder of its own kind; the counts in `OpenAnnouncement` and the render guard's silent-clip count must not count a reference track as a silent clip.
- Code locators: `ProjectDeserializer.parseReferenceTrackManager` (the `Files.exists(Path.of(sourceFile))` check), `ProjectSerializer.buildReferenceTrackManager`, `MissingAssetsReport.atOpen` (the reference-track partition), `OpenProjectAudio.begin` (the log line that names this story), and the `ProjectPaths` class Javadoc, which records reference tracks as outside the rule and deferred here.
- Design references: Persistence Integrity design book §3.3 (path semantics) and §4.3 (the clip rehydrator and the missing-assets surface).
- Cross-refs: story 329 (clip audio reload and relink), story 323 (the project-relative rule for clip references), story 189 (archive consolidation, linked from the report).

## Status

- Deferred

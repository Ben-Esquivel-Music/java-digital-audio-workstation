---
title: "DAWproject Import and Export Use Arrangement Lanes"
labels: ["bug", "session", "interoperability", "file-io"]
---

# DAWproject Import and Export Use Arrangement Lanes

## Motivation

Story 020, "DAWproject Format Import/Export for Session Interoperability", promised session interchange through the open DAWproject standard: import a DAWproject file written by another program with its tracks, clips and mixer settings, and export the current project for use in another program. Today another program's clips do not come in, and this application's clips likely do not go out.

The open DAWproject standard keeps a project's tracks apart from its timeline. In the example `project.xml` of the standard's reference repository, `Structure` holds each `Track`, with an `id`, and only that track's `Channel`. The clips sit under `Project/Arrangement/Lanes/Lanes track="<track id>"/Clips/Clip`, one inner `Lanes` element per track. An audio clip there can itself hold a nested `Clips` timeline of audio events; in the example, each event keeps its `Audio` and `File` inside a `Warps` element.

This application reads and writes another layout. `DawProjectXmlParser.parseDocument` takes the tracks from `Structure`'s direct `Track` children (~:115-124), and `parseTrack` reads clips only from each track's own `Lanes/Clips/Clip` (~:159-171). `parseClip` (~:183-213) takes the file path from an `Audio` element that is the clip's direct child. `DawProjectXmlSerializer` writes clips in the same layout: `Structure` (~:89-95), each `Track` without an `id`, then its `Channel` and, when it has clips, `Lanes/Clips` (`buildTrackElement` ~:98-129, `buildClipElement` ~:131-155). Neither class reads or writes an `Arrangement` element: `git grep -n Arrangement -- daw-core/src/main/java/com/benesquivelmusic/daw/core/session/dawproject` finds nothing.

So a DAWproject file written by another program imports its tracks with no clips, and the import still reports success. The summary's header is `DAWproject Exchange imported successfully`, its count reads `Clips: 0`, and no warning says why (`ProjectLifecycleController.onImportSession` ~:572-611, `SessionInterchangeController.buildImportSummary`). In the other direction, this application's export likely places its clips where other programs do not look. Only this application's own exports, and files in its layout edited outside it, carry clips today. Mixer settings miss the same way: the standard's `Channel` holds `Volume`, `Pan` and `Mute` as child parameter elements, with only `solo` an attribute, but the parser reads and the serializer writes all four as `Channel` attributes (`DawProjectXmlParser.parseTrack` ~:147-150, `DawProjectXmlSerializer.buildTrackElement` ~:105-112), so another program's mixer levels import as defaults and this application's are not where other programs look. Both gaps predate story 329.

Story 329 raised the stakes. Since story 329, an imported clip's name selects its ADM bed or object when the project reopens (`DecodedAudio.AdmAudio.audioFor`), and story 329's ADM-import rule (`DecodedAudio.isAdmImport`) judges the names an import keeps. Story 329 loosened that rule for sessions that come back edited outside this application. Today only files in this application's layout reach it; this story brings another program's files to it.

## Goals

- **Read the published layout.** Import reads clips from `Arrangement/Lanes` and gives each lane's clips to the track its `track` attribute names, including an audio clip's nested `Clips` timeline of audio events.
- **Write the published layout.** Export gives each `Track` an `id` and places its clips under `Arrangement/Lanes`, in a lane whose `track` attribute names that `id`; `Structure` keeps each track and its `Channel`.
- **Keep reading the legacy layout.** A file in this application's `Structure/Track/Lanes/Clips` layout, which every export before this story wrote, still imports as it does today, so earlier exports still import.
- **Mixer settings as the standard's parameters.** Import reads a `Channel`'s `Volume`, `Pan` and `Mute` child elements and its `solo` attribute, and export writes the track's mixer settings that way; a legacy file's `volume`, `pan` and `mute` attributes on `Channel` still import.
- **Warn about skipped device chains in either layout.** Import warns that a track's device and plugin chains were skipped when its published-layout `Channel` holds `Devices`, as it warns today when a legacy `Track` holds them.

## Acceptance Criteria

1. A `project.xml` in the published layout imports. The file has two tracks, each with an `id` and a `Channel` in `Structure`. Under `Arrangement` it has one lane per track, naming that track. One lane holds an audio clip whose audio is a nested clip with `Audio` and `File`. Both tracks import, and the audio clip lands on the track its lane names, with its name, its start and duration on the timeline, and its file path. The Resolution states how a nested timeline maps onto this application's clips: which name, start, duration and source offset each resulting clip takes.
2. A file in the legacy layout, with clips under `Structure/Track/Lanes/Clips`, imports the same tracks and clips as it did before this story.
3. Exporting a project and importing the result gives the same tracks with the same clips: each clip's name, start, duration and file path, and the source offset and gain that `DawProjectRoundTripTest.shouldRoundTripSessionWithTracksAndClips` pins today. The standard's `audio` type declares neither an `offset` nor a `gain` attribute, so the Resolution states where the export writes them.
4. The exported `project.xml` places every clip under `Arrangement`, in a lane whose `track` attribute names the `id` of a `Track` in `Structure`, and no `Track` in `Structure` holds `Lanes` with clips.
5. A published-layout `Channel` with `Volume`, `Pan` and `Mute` child elements and a `solo` attribute imports those values onto its track. An export writes each track's volume, pan and mute as those child elements and its solo as the attribute, never as `volume`, `pan` or `mute` attributes, and importing the export gives the same values back. A legacy `Channel`'s attributes still import as before. The Resolution states which `unit` each `Volume` and `Pan` value is read and written in.
6. A published-layout track whose `Channel` holds `Devices` imports with the skip warning a legacy `Track` holding `Devices` gets today (`Track '<name>': Device/plugin chains are not yet supported and were skipped`), and a legacy `Track` holding `Devices` still gets it.
7. A lane with `timeUnit="seconds"` imports its clips at the right beat positions: each clip's start and duration are converted from seconds to beats with the project tempo.
8. A lane whose `track` attribute names a `Track` nested inside another `Track` in `Structure` imports onto that nested track; today `parseDocument` reads only `Structure`'s direct `Track` children.
9. A clip whose content is not audio, such as a `Clip` holding `Notes`, is not turned into an audio clip without a file: it is skipped with a warning. MIDI interchange is not in this story's scope.
10. The exported `project.xml` is valid against the published `Project.xsd`, which requires `Application` with its `name` and `version`, `Tempo` as a `realParameter` with a `unit`, and each `Audio` with `channels`, `sampleRate` and `duration`.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Published layout**: import a minimal published-layout `project.xml` through `DawProjectSessionImporter`. It has two tracks and an audio clip whose audio is a nested clip with its `Audio` inside `Warps`, as in the reference example. Both tracks import, and the clip arrives on its lane's track with its name, start, duration and file path.
- **Legacy layout**: a `project.xml` with its clips under `Structure/Track/Lanes/Clips` imports the same tracks and clips as before this story.
- **Round trip**: export a project with two tracks and clips, then import the archive. The same tracks and clips come back, offset and gain included.
- **Export layout**: in the exported archive's `project.xml`, every `Clip` sits under `Arrangement`, each lane's `track` names a `Track` `id` in `Structure`, and no `Structure/Track` holds `Lanes/Clips`.
- **Mixer settings**: a published-layout `project.xml` whose `Channel` has `Volume`, `Pan` and `Mute` child elements and `solo="true"`, each away from its default, imports those values into the track's volume, pan, mute and solo. A project exported with non-default mixer settings holds them as those child elements and the `solo` attribute, with no `volume`, `pan` or `mute` attribute, and imports back with the same values. A legacy `Channel` with `volume`, `pan`, `mute` and `solo` attributes still imports them.
- **Device skip warning**: a published-layout `project.xml` whose track's `Channel` holds `Devices` imports with that track's device-chain skip warning in `SessionImportResult.warnings()`; a legacy `Track` holding `Devices` still gets the same warning.
- **Seconds lanes**: a published-layout `project.xml` at 120 BPM whose lane has `timeUnit="seconds"` and a clip with `time="2.0"` and `duration="1.0"` imports that clip at beat 4 with a duration of 2 beats.
- **Nested track**: a published-layout `project.xml` whose lane's `track` names a `Track` nested inside another `Track` in `Structure` imports that nested track with the lane's clip on it.
- **Notes clip**: a published-layout `project.xml` with a `Clip` holding `Notes` imports no clip for it, and the result's warnings say it was skipped.
- **Schema validity**: the `project.xml` exported from a project with an audio clip validates against the published `Project.xsd`, kept as a test resource so the test runs offline.

## Non-Goals

- Plugin state. Device and plugin chains stay skipped. Today's warning (`DawProjectXmlParser.parseTrack` ~:153-157) looks only for a `Devices` child of `Track`, but the standard puts `Devices` under `Channel`; the import warns that they were skipped for a published-layout `Channel`'s `Devices` as it does for a legacy `Track`'s.
- MIDI interchange. A clip that holds `Notes` is skipped with a warning (criterion 9), and the export writes only a track's audio clips (`SessionInterchangeController.convertTrack` reads `Track.getClips()`), so a track's `MidiClip` does not go out.
- The persisted ADM bed or object selector, which is story 360. An imported clip still selects its bed or object by its name.

## Technical Notes

- Code locators (line numbers are locators, not pins):
  - `DawProjectXmlParser`: `parseDocument` ~:82-127, `parseTrack` ~:129-181, `parseClip` ~:183-213.
  - `DawProjectXmlSerializer`: `buildDocument` ~:67-96, `buildTrackElement` ~:98-129, `buildClipElement` ~:131-155.
  - `DawProjectSessionImporter.importSession` ~:34 reads `project.xml` from the archive or from a plain XML file.
  - `DawProjectSessionExporter.exportSession` ~:29-55 writes only `project.xml` into the archive.
  - In daw-app, `SessionInterchangeController.addClipsToTrack` ~:283 and `convertTrack` ~:220.
  - Mixer settings: the `Channel` reads in `parseTrack` ~:147-150 and writes in `buildTrackElement` ~:105-112; `SessionInterchangeController.applyMixerSettings` ~:267 applies them to the track and its mixer channel.
- Facts from the standard's `Project.xsd`:
  - A lane's `track` attribute is an `IDREF` to a `Track`'s `id`.
  - `timeUnit` is `beats` or `seconds`, while `SessionClip` holds beats.
  - A clip's content is at most one element, for example `Audio`, `Warps` (which holds `Audio`) or a nested `Clips`.
  - A clip has `time` (required), `duration` and `playStart`, and no gain attribute.
  - `Audio` requires `channels`, `sampleRate` and `duration`, and a `File` with a `path`. `SessionData.SessionClip` carries no channel count, sample rate or file duration today, so the export needs them from the clip's source.
  - `Project` requires an `Application` with a `name` and a `version`, and `Transport`'s `Tempo` is a `realParameter`, so it needs a `unit`; today's export writes neither.
  - `Track` and `Lanes` can nest. Today `parseDocument` reads only `Structure`'s direct `Track` children.
  - A `Channel` holds `Volume` and `Pan` as `realParameter` elements, each with a required `unit` and an optional `value`, and `Mute` as a `boolParameter` element with an optional `value`; `solo` is its only attribute of the four.
- Cross-refs:
  - Story 020: the interchange this story completes for clips.
  - Story 329: the ADM-import rule that judges imported clip names, and its Resolution's DAWproject paragraphs.
  - Story 360: persists the ADM selector; a DAWproject import still selects by name.
- Research backing: `docs/research/open-source-daw-tools.md`, "Architectural Patterns and Lessons", lesson "4. Project File Format" (DAWproject for interchange).

## Status

- Deferred

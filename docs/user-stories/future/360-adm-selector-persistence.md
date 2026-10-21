---
title: "ADM Bed and Object Selector Persistence"
labels: ["enhancement", "persistence", "project-load", "audio", "immersive"]
---

# ADM Bed and Object Selector Persistence

## Motivation

An ADM BWF import (`AudioFileImporter.importAdmBwf`) makes at most one bed clip, holding all of the master's bed channels, and one clip per audio object, every clip referencing the same file, and names them `Bed (<layout>)` and `Object <n>` (`AdmClipAudio.bedClipName`, `AdmClipAudio.objectClipName`). Each object clip gets a track of its own; the bed clip lands on the caller's track when one is given, and on a track of its own otherwise. Nothing in `project.daw` records which bed or object a clip plays: the serializer writes the clip's name and its source file. When the project reopens, story 329's rehydrator derives the selector from the name. It strips every trailing ` (split)` (a split appends one) and requires an exact match with the parsed file's bed name or with an object name in range (`DecodedAudio.AdmAudio.audioFor`); anything else is a `Needs re-import (ADM: object unknown)` row and is never guessed.

Story 329 also decides from names alone whether an asset is an ADM import at all (`DecodedAudio.isAdmImport`), so that a master whose `axml` chunk was lost is not handed to every bed and object clip, each playing the same channels instead of its own bed or object. Its first rule required every clip holding the asset to have an ADM import name form after its trailing ` (split)` suffixes are stripped (`AdmClipAudio.hasImportNameForm`), with at least two distinct such names. That rule held only while nothing gave an ADM master's clips other names. Import DAWproject Exchange already does: it keeps each clip's name and file path as the DAWproject file gives them (`DawProjectXmlParser.parseClip`, `SessionInterchangeController.addClipsToTrack`). The parser reads clips only from `Structure/Track/Lanes/Clips`, the layout this application's own export writes, and not from the `Arrangement` lanes where the published DAWproject format places them, so a file written by another program imports with no clips today, and only a file in this application's layout that was edited outside it renames an ADM master's clips this way; another program's files will too once the parser reads arrangement lanes (story 363). So story 329 adopted the looser rule quoted below: at least two distinct ADM-form names after every trailing ` (split)` is stripped, with holders of other names not disqualifying the asset. The studio-engineer's ruling for story 329, quoted:

> - **Threshold.** "At least two distinct ADM-form names" is right. One plain file reaches that only if the user renames its clips to Bed/Object forms, and no app path does that. So the rule is not too loose.
> - **The "EVERY holder" clause.** This clause is the only way a real ADM session slips through: one clip with any other name disqualifies the whole asset, and every bed and object clip then plays the same channels. Nothing reaches that today:
>   - The import names every clip `Bed (<layout>)` / `Object <n>`.
>   - Split adds " (split)".
>   - Duplicate, glue and comp keep the name.
>   - No app code calls `AudioClip.setName`.
> - **Rule I would accept.** "An asset is an ADM import when its holders' names, after stripping every trailing ` (split)`, include at least two distinct names of ADM import form; holders with other names do not disqualify it." Changing now is optional (NIT). It becomes required in whatever story adds clip rename, or an interchange import that keeps the master's path.
> - **Accepted residual.** An ADM import cut down to clips of ONE name (bed only, or one object) whose file lost its `axml` plays the file's first channels. Accept it: a single name is genuinely ambiguous with a plain `Object 3.wav` or `Bed (5.1).wav` stem, and the fault is audible.
> - **Safe future tightening, deferred.** For a single distinct `Bed (<preset>)` name with preset in Mono/Stereo/5.1/5.1.4/7.1.4/9.1.6, a decoded file with more channels than the preset counts as an ADM import.
> - **After a confirmed relink.** If the engineer confirms a relink to a plain file, the next open raises the row again and silences the clips. Accepted: an object clip playing the whole program is never a deliverable state.

The premise "Nothing reaches that today" did not hold for Import DAWproject Exchange, which existed before story 329. The looser rule therefore landed in story 329 itself. The threshold's "no app path does that" did not hold either: a DAWproject Exchange import can give one plain file clips with two distinct ADM-form names, such as `Bed (30s)` and `Bed (60s)`, which story 329 accepted as built.

The same review left one optional point for a take relink, deferred here: the relink confirmation names a take by its take folder and track folder (`<stamp>_take-NNNN/<trackId>`), so two tracks of one take differ only by their track id — "Optional, can be deferred: also name the project track whose id the picked track folder carries."

## Goals

- **The selector is persisted.** Each clip of an ADM import records which bed or which object of its source file it plays, and reload and relink read that record instead of deriving the selector from the clip's name. A project without the record reopens exactly as story 329 reopens it.
- **Keep the looser ADM-import rule.** Story 329's rule stays the decision for every clip without a persisted selector. That includes every clip a DAWproject Exchange import creates, because the DAWproject export writes no selector and the import reads none — only a clip's name and path say what it plays.
- **The single-Bed tightening.** For a single distinct `Bed (<preset>)` name with the preset one of Mono, Stereo, 5.1, 5.1.4, 7.1.4 or 9.1.6, a decoded file with more channels than the preset counts as an ADM import.
- **Optional: count only the bed names the import writes.** For the ADM-import decision, `Bed (<name>)` has ADM import form only when the name is one a bed can get from the import: a `SpeakerLayout` preset name (Mono, Stereo, 5.1, 5.1.4, 7.1.4, 9.1.6) or `Custom`. Then a DAWproject session's `Bed (30s)` and `Bed (60s)` clips of one plain music bed load as a plain file instead of being silenced under the ADM row.
- **Optional: name the project track in a take confirmation.** When a picked take folder differs from the take's own, the confirmation also names the project track whose id the picked track folder carries.

## Acceptance Criteria

1. An ADM BWF import saved and reopened gives every bed and object clip its own audio from the persisted selector. Renaming a clip (through `AudioClip.setName`, since the application has no rename yet) and reopening still gives it its own bed or object, and the next save keeps the selector.
2. A project saved before this story, with no selector recorded, reopens with the same clips loaded, the same `Needs re-import (ADM: object unknown)` rows and the same counts as under story 329. The Resolution states whether the project schema version changes (`MigrationRegistry`).
3. Story 329's looser rule still decides for clips without a persisted selector. An asset whose holders carry at least two distinct ADM-form names and also a clip of any other name, on a file without ADM metadata, gives no clip audio at open and makes every holder a silent clip of one `Needs re-import (ADM: object unknown)` row.
4. An asset held only by clips of one distinct `Bed (<preset>)` name, with the preset in the list above, is an ADM import when the decoded file has more channels than the preset, and a plain file when it has as many or fewer. A clip of a single `Object <n>` name on a file without ADM metadata still plays the file's first channels (the accepted residual).
5. A confirmed relink of an ADM import's clips to a plain file is still not remembered: the next open raises the row again.
6. If the optional take-confirmation naming is adopted, a take picked in another track's folder of the same take names that project track in the confirmation; otherwise the Resolution says it stays deferred.
7. If the bed-name narrowing is adopted, `Bed (30s)` and `Bed (60s)` on one plain file load as a plain file, and every bed name `AdmBwfImporter.inferLayout` can produce still has the form. Otherwise the Resolution says it stays deferred.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Selector round trip**: import a generated ADM BWF file with a bed and two objects, save, rename one object clip with `AudioClip.setName`, reopen; each clip holds its own bed's or object's audio, the renamed clip included, and no row is raised.
- **Legacy project**: a project file written without the selector reopens with the rows and counts story 329's `ClipRehydratorTest` ADM cases expect.
- **Looser rule kept**: story 329's case still passes. Three clips named `Bed (Stereo)`, `Object 1` and `Vocal` on one file without an `axml` chunk; none gets audio, and all three are silent clips of one row.
- **Single-Bed tightening**: one `Bed (Stereo)` clip on a 6-channel file without ADM metadata is reported; the same clip on a 2-channel file loads as a plain file.
- **Bed names** (if adopted): `Bed (30s)` and `Bed (60s)` on one 2-channel file without ADM metadata load as a plain file; `Bed (Stereo)` and `Object 1` on the same file are still reported.
- **Relink**: the same rules decide whether a located file needs the ADM sentence of the `Use This File?` confirmation.

## Non-Goals

- A clip-rename surface in the application; this story makes renaming safe, it does not add it.
- ADM BWF export, and changing the names the import gives its clips.
- Carrying the selector through DAWproject Exchange. The DAWproject export writes no selector and the import reads none, so only a clip's name and path say what it plays, and the clips of a DAWproject Exchange import keep selecting by name. A DAWproject file whose writer left fewer than two distinct ADM-form names on a master without ADM metadata still plays the same channels on every clip, under either rule.

## Technical Notes

- Code locators: `DecodedAudio.isAdmImport` (the looser rule story 329 adopted), `DecodedAudio.AdmAudio.audioFor` and `DecodedAudio.stripSplitSuffixes`, `AdmClipAudio.hasImportNameForm`, `AdmClipAudio.bedClipName`, `AdmClipAudio.objectClipName` and `AdmClipAudio.extract`, `RehydrationSession.apply` (the no-ADM-metadata branch), `AssetRelinker` (a candidate's `RelinkPreparation.MissingAdmMetadata`) and `AssetRelinkFlow.confirmationText` (the confirmation's sentences), `ProjectSerializer` and `ProjectDeserializer` (the clip element), and the `SpeakerLayout` presets (`LAYOUT_MONO`, `LAYOUT_STEREO`, `LAYOUT_5_1`, `LAYOUT_5_1_4`, `LAYOUT_7_1_4`, `LAYOUT_9_1_6`).
- `AdmBwfImporter.inferLayout` returns only 7.1.4, 5.1.4, 5.1, Stereo, Mono or `Custom`; a 9.1.6 bed imports as `Bed (Custom)`. The single-Bed tightening keeps 9.1.6 in its list for when `inferLayout` learns it, and excludes `Custom`, which names no channel count. Interchange locators: `DawProjectXmlParser.parseClip`, `SessionInterchangeController.addClipsToTrack` and `convertTrack`, `DawProjectXmlSerializer.buildClipElement`.
- The take naming lives in `AssetRelinker` (`RelinkPreparation.TakeFolderMismatch.pickedFolder`) and the `missingAssets.confirm.takeFolder` message.
- Design references: Persistence Integrity design book §3.1 (round-trip inventory), §4.3 and §6.3 (schema evolution); story 334 owns the wider write-side inventory.
- Cross-refs: story 329 (the name-derived selector this story replaces, and the ADM-import rule it keeps), story 334 (full-state persistence).

## Status

- Deferred

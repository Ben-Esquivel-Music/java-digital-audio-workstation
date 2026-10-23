---
title: "Audio Clip Ids Round-Trip So Snapshot Compare Matches Clips"
labels: ["bug", "persistence", "snapshots", "clips"]
---

# Audio Clip Ids Round-Trip So Snapshot Compare Matches Clips

## Motivation

Story 330 made track ids round-trip. Audio clip ids still do not:

- **The id is never written.** The `<clip>` writer in `ProjectSerializer` emits the clip's name, position, length, offset, gain, fades and source references, but no `id` (`ProjectSerializer.java:540-554`). `AudioClip`'s constructor mints a random UUID (`AudioClip.java:79`), and the deserializer builds each clip with that constructor (`ProjectDeserializer.java:1250`), so every open gives every clip a new id.
- **Snapshot compare lists every clip as changed.** `SnapshotsController.compare` deserializes the snapshot and calls `SnapshotDiff.between(snapshot, current)`. Tracks now match by their restored ids, but clips are matched by id (`SnapshotDiff.clipsById`), and a deserialized snapshot's clip ids never equal the live project's. For every track the diff therefore reports each clip as `Clip removed` and again as `Clip added`, even when the clip has not changed, and a real move or trim of a clip is lost among them.
- **A test forges clip ids by reflection.** `SnapshotDiffTest.clip(...)` sets `AudioClip`'s final `id` field through `Field.setAccessible(true)` (`SnapshotDiffTest.java:33-45`) because `AudioClip` has no constructor that takes an id. Since JDK 26 that mutation prints a final-field mutation warning (JEP 500), and a future release will deny it.

What was checked and is not affected today: journal records key clip events by clip id (`JournalEventRecorder.entityIdOf`), but `JournalReplayer.applyOne` returns for every record family — the records carry no payload — so replay matches nothing by id. Automation lanes have no id at all, and nothing publishes an `AutomationEvent`. Once journal replay applies clip records, it needs the ids this story restores.

For an engineer the snapshot browser's Compare is the way to see what changed since a snapshot; today it says that every clip changed.

## Goals

- **Clip ids round-trip.** Each `<clip>` carries its id; the reader restores it through an id-restoring `AudioClip` constructor, like story 330's `Track(UUID, String, TrackType)`.
- **Bad ids degrade like track ids.** A missing, malformed or duplicate clip id gets a fresh id with a WARNING naming the track and the clip (story 330's rules for `track@id`); a malformed or duplicate id arms the degraded-open backup.
- **New clips get new ids.** Split, duplicate, paste and import keep minting fresh ids; only the deserializer restores them.
- **No reflective id forging.** `SnapshotDiffTest` builds its clips with the new constructor.

## Acceptance Criteria

1. Save and reopen: every clip's id is unchanged.
2. Compare against a snapshot of an unchanged project lists no clip entry; moving one clip lists exactly that clip as modified.
3. A file without clip ids opens as today, each clip with a fresh id, and the next save writes ids.
4. Split halves, duplicates and pasted clips have ids distinct from their source.
5. No test sets `AudioClip`'s id reflectively; the suite runs with no final-field mutation warning from it.
6. The parity harness lists `clip@id`, and `ProjectRoundTripInventoryTest` compares clip ids.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Id round trip**: a project with clips on two tracks saved and reopened through `ProjectSerializer` / `ProjectDeserializer` and through `ProjectManager`; every clip id compares equal.
- **Snapshot compare**: take a snapshot, change nothing, compare; `SnapshotDiff.isEmpty()` is true. Move one clip, compare; the diff has one `MODIFIED` clip entry and no `ADDED` or `REMOVED` ones.
- **Bad ids**: missing, `not-a-uuid` and a duplicate clip id load without throwing, each with a fresh id and a WARNING; the duplicate keeps the first clip's id.
- **Fresh ids**: splitting and duplicating a reopened clip gives ids different from the source.
- **Harness**: removing the `clip@id` reader fails `ProjectSchemaParityContractTest` naming it.

## Non-Goals

- Journal replay of clip records (no owner yet: `JournalReplayer.applyOne` is a no-op for every family until events carry payloads; story 298 owns replay).
- Take and comp identity (story 334 references takes by id).

## Technical Notes

- Code locators: `ProjectSerializer`'s `<clip>` writer, `ProjectDeserializer.parseClip`, `AudioClip` (constructor, `duplicate`), `SnapshotDiff.between` / `clipsById`, `SnapshotsController.compare`, `SnapshotDiffTest.clip`, `JournalEventRecorder.entityIdOf`, `JournalReplayer.applyOne`.
- Whether the new attribute needs a schema-version bump is ruled before implementation: story 330 bumped for additive content because an older build would erase it on save; a clip id lost that way costs only the match.
- Found in story 330 (track-identity analysis and the implementer's adjacent finding on `SnapshotDiffTest`).
- Cross-refs: story 330 (track identity), story 298 (the write-ahead journal and its replay), story 332 (journaled persistence on by default; it changes replay's reachability, not its behaviour), story 334 (takes by id).

## Status

- Deferred

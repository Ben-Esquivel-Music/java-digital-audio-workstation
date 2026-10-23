---
title: "The Open Report's Per-Item Detail Surface"
labels: ["enhancement", "persistence", "project-load", "ui"]
---

# The Open Report's Per-Item Detail Surface

## Motivation

Since story 330, an open that cannot honour everything its file saved says so in one sentence: story 329's announcement gains a clause per kind, in a fixed order and with counts only — for example `Opened project: Song — 2 missing assets, 3 MIDI notes skipped, 1 MIDI effect not recognized (kept as saved)` (`OpenAnnouncement.of`, the `projectOpen.unread.*` keys in `Messages.properties`). Each item is then named only in the log: one WARNING per item, giving the track by its number and name, the item's number, the reason, the saved XML and, for notes and events, the transport beat (`UnreadProjectItem.logLine`, the deserializer's `reportUnread`).

That leaves the engineer without a place to see the items:

- **The announcement's `Review…` is for missing assets only.** `OpenAnnouncement.of` offers it when the missing-assets report has rows (`!report.isEmpty()`), and it opens `MissingAssetsDialog`. An open whose only damage is three skipped notes has no `Review…`.
- **The log is not reachable.** No `FileHandler` exists in main code; the log goes to the console, which a packaged windowed application does not have (story 336 adds the file sink and Help ▸ Open log folder).
- **The degraded-open backup is not named anywhere on screen.** Story 330 writes `project.daw.degraded.<stamp>.bak` (or the migration backup) on the first save after such an open and logs its path at INFO; the announcement deliberately does not mention it, because at open it does not exist yet (studio ruling, round 3, Q1).

An engineer told "3 MIDI notes skipped" needs to know which notes on which track, to decide whether to save over the file or repair it from the backup.

## Goals

- **One detail surface per open**, reached from the announcement's `Review…` whenever the open has unread items, the counterpart of story 329's missing-assets dialog: a list of every item, grouped by track or mixer container, giving what was skipped, adjusted or removed and why, with the saved value and, for notes and events, the transport beat — the same facts the WARNING line carries, from the same `UnreadProjectItem` records, never re-derived by the UI.
- **The backup is named.** Once the first save has written the degraded-open (or migration) backup, the surface shows its path and offers to reveal it in the file manager; before that save it says that the first save will keep a backup.
- **The open with both missing assets and unread items** offers one `Review…` that reaches both lists.

## Acceptance Criteria

1. An open with unread items and no missing assets shows `Review…`; it opens the detail surface listing every item.
2. Each row shows the track (number and name) or the mixer container, the item, the reason and the saved value as the log line does, with the same quoting (`UnreadProjectItem.logText`).
3. After the first save writes the backup, the surface shows its path; a clean open shows no surface and no `Review…`.
4. The surface is reachable from the archive-restore, journal-replay and snapshot-restore doors with their own items (story 330 counts each door's own read).
5. The rows' text comes from daw-core records; no count or wording is computed twice.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Notes only**: open a file with one `velocity="128"` note; the announcement offers `Review…`, and the surface lists one row naming `Track 1`, `MIDI note 1`, the reason and the saved note.
- **Mixed**: a file with a missing asset, a skipped CC event and a removed mixer assignment; one `Review…` reaches all three.
- **Backup path**: save after a degraded open; the surface shows the `project.daw.degraded.*.bak` path that `ProjectManager` logged.
- **Doors**: archive restore, journal replay and snapshot restore each list their own read's items.
- **Accessibility**: every row and control has accessible text.

## Non-Goals

- Repairing items from the surface (re-reading a note, re-linking an assignment).
- The log file itself (story 336).
- New kinds of unread items (story 367 adds mixer containers and clamps; this surface lists whatever the records hold).

## Technical Notes

- Code locators: `OpenAnnouncement.of` and its `UnreadClause`, `ProjectLifecycleController.loadAttempt`, `OpenDoor`, `UnreadProjectItem` (kinds, `logLine`, `logText`), `ProjectManager.getLastUnreadItems`, `ProjectManager.writeSiblingBackup`, `MissingAssetsDialog`.
- Ruled by the studio engineer in story 330: round 2, R4 future item 5, with round 3's Q1 addition (the surface lists the backup path).
- Skills: `javafx-application-design` §4, §11 (the rows come from records built off the FX thread), §15.
- Cross-refs: story 329 (the announcement and the missing-assets dialog), story 330 (the unread items and the backup), story 336 (the log file sink), story 367 (more kinds of items).

## Status

- Deferred

---
title: "A Name the Project File Cannot Carry Never Fails a Save"
labels: ["bug", "persistence", "tracks"]
---

# A Name the Project File Cannot Carry Never Fails a Save

## Motivation

A track name holding a character XML 1.0 cannot carry makes every save of the project fail, and every checkpoint with it, so the session's work reaches no file at all. `Track.setName` and the `Track` constructor check the name only for `null`. The serializer puts it in the DOM as it is, and the render (`ProjectDocument.toXml`, the JDK's `Transformer`) refuses it: `IOException("Failed to serialize project")`. Found in story 331 (its fix round on stage A3), where `SingleCanonicalSaveTest.theRenderRunsOnTheSaveWriterSoADocumentThatCannotRenderFailsThereNotAtTheCall` uses a track named `"Vox\0"` as its fixture and says so: "This fixture relies on that serialization defect (a NUL or an unpaired surrogate in a track name fails every save of the project)".

What the user sees today, since story 331:

- Ctrl+S: `Save failed: Failed to serialize project. No safety copy could be written.` — the safety copy needs the rendered bytes, and there are none.
- Autosave: the Saved cell turns amber, `⚠ Checkpoint failed`, and the second failure in a row raises `Checkpoints are failing: Failed to serialize project. The last good checkpoint is from HH:mm.`; every later tick fails the same way.
- Create Checkpoint… and Archive Project… fail too: they render the same document.

In a check on JDK 27 (story 331's documentation step), U+0000, U+0001 and a lone low surrogate (U+DC00) made the JDK's `Transformer` throw "An invalid XML character … was found", and a lone high surrogate (U+D800) at the end of a name was written without it, so the name changed on reopen with no word.

## Goals

- **A save never fails on a name.** The studio engineer rules, before implementation, between refusing such a character where a name enters the model (rename, track creation, import) and writing it in a form the reader restores; either way no save, checkpoint or archive fails or silently changes a name because of one.
- **One rule for every name the file holds.** The rule covers every text the serializer writes from the model — track, clip, marker, insert and project names included — not only track names; each such text is checked in the current tree.
- **A project already holding such a name saves.** A model that holds one when this story lands — loaded, or edited before — can be saved and reopened.

## Acceptance Criteria

1. A track named with U+0000, U+0001, a lone low surrogate or a lone high surrogate either cannot be given that name (with a visible reason) or saves and reopens with the name the ruling defines; no save fails and no name changes without a word.
2. The same holds for every other text the serializer writes from the model.
3. `SingleCanonicalSaveTest`'s render-failure fixture uses another document that captures but cannot render, as its comment asks.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Names**: for each of the four characters, in a track name, a clip name and the project name: the save completes `Saved` and the reopened project carries the ruled name, or the name is refused at input.
- **Autosave**: a project holding such a name writes its checkpoint; the strip shows no checkpoint failure.
- **Fixture**: `SingleCanonicalSaveTest.theRenderRunsOnTheSaveWriterSoADocumentThatCannotRenderFailsThereNotAtTheCall` still fails its render on the save writer, with a fixture that no longer relies on this defect.

## Non-Goals

- XML 1.1, or any change to the project format beyond what the ruling needs.
- Names in the DAWproject interchange format.

## Technical Notes

- Code locators: `Track` (constructor, `setName`), `ProjectSerializer` (the builders that write names), `ProjectDocument.toXml` / `render`, `ProjectDeserializer`; the rename and import paths in daw-app.
- Found in story 331 (report of the fix round on stage A3; the brief of the fix round on stage B1 named it a future story); the characters' behaviour was checked on JDK 27 in story 331's documentation step.
- Design references: Persistence Integrity design book §2.1 (a fact that does not round-trip does not exist), §3.1.
- Cross-refs: story 330 (the parity harness; a reader that tolerates absent items), story 331.

## Status

- Deferred

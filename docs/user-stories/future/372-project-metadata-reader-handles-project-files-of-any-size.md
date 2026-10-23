---
title: "The Project Metadata Reader Handles Project Files of Any Size"
labels: ["bug", "persistence", "project-hub"]
---

# The Project Metadata Reader Handles Project Files of Any Size

## Motivation

The project hub's cards get each project's name and last-modified time from `ProjectMetadataReader.read` (called by `ProjectHealthScanner.scanBlocking`, `ProjectHealthScanner.java:90`). To avoid loading the whole project, the reader reads at most `READ_LIMIT = 8192` characters of `project.daw` (`ProjectMetadataReader.java:59`, `readPrefix`) and parses them as an XML document (`parseXml`).

- **Every file over 8,192 characters loses its metadata.** The `<metadata>` block is at the top of the file (`ProjectSerializer` writes it right after the root element, `ProjectSerializer.java:303-317`), so the prefix does contain it. But the prefix is a cut-off document: the parser throws `SAXException` ("XML document structures must start and end within the same entity" — checked on JDK 27 during story 330's migration audit), `parseXml` catches it and returns no fields, and `read` falls back to the directory name and the file's modification time. The card then shows the folder's name instead of the project's.
- **Story 330 makes this common.** MIDI clips are now saved, one `<note>` element of about 90 characters per note, so a few dozen notes put a small session over the limit; mixer-heavy sessions were already there.
- **The comment says the opposite.** `READ_LIMIT`'s Javadoc says "8 KB is more than sufficient to capture it without loading the potentially large full project graph" — true for capturing the block, false for parsing it.

For an engineer the hub is where a session is found by name, and the fallback card shows the folder's name. That is usually close to the project's: `ProjectManager.createProject` names the folder `sanitizeDirectoryName(name)`, which turns every character outside letters, digits, `.`, `_`, `-` and space into `_`, and `createUniqueProjectDirectory` gives a second project of the same name a ` 2` suffix. So `Don't Stop` shows as `Don_t Stop`, `Café` as `Caf_`, and a second `Song` as `Song 2`; a project restored from an archive shows its restore folder, the archive's file name with the restore time appended (`Song-20261022-143000`). The other loss is silent: `read` falls back to the file's modification time for `createdAt` as well as `lastModified`, so an over-limit project's creation date becomes its last-save time.

## Goals

- **Metadata of any file.** The reader returns the saved name, creation time and last-modified time of a `project.daw` of any size.
- **Still cheap.** It stops reading once the `<metadata>` block has been read (for example with a streaming XML reader), never builds the project graph and never reads the rest of the file.
- **Still safe.** It keeps the deserializer's hardening (no DOCTYPE, no external entities), never throws for bad input, and keeps its fallbacks for a file with no readable metadata.

## Acceptance Criteria

1. A `project.daw` of 1 MB whose metadata names `Song` shows `Song` on the hub card, with its saved last-modified time.
2. Reading stops after `</metadata>`: the reader's cost does not grow with the size of the file.
3. A malformed file, a file with a DOCTYPE, a legacy text file and a missing file behave as today (`ProjectMetadataReaderTest`).

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Large file**: a project saved with enough MIDI notes to exceed 8,192 characters; `ProjectMetadataReader.read` returns its saved name, its saved `createdAt` (not the file's modification time) and its saved `lastModified`.
- **Bounded read**: a file whose bytes after `</metadata>` are not valid XML still returns its metadata.
- **Hub**: `ProjectHealthScanner.scanBlocking` over such a project gives the saved name.
- **Kept**: the existing `ProjectMetadataReaderTest` cases still pass.

## Non-Goals

- Other hub statistics (disk usage, missing assets).
- Reading project files off the FX thread elsewhere.

## Technical Notes

- Code locators: `ProjectMetadataReader` (`READ_LIMIT`, `read`, `readPrefix`, `parseXml`), `ProjectHealthScanner.scanBlocking`, `ProjectSerializer`'s `<metadata>` writer.
- Found in story 330's migration audit (adjacent finding A4); the parity harness declares `ProjectMetadataReader` a separate consumer of `<metadata>`, outside its contract (`ProjectSchemaParityContractTest`'s class Javadoc).
- Cross-refs: story 330 (MIDI clips in the project file).

## Status

- Deferred

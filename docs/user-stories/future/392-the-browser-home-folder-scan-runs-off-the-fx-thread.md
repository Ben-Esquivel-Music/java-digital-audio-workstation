---
title: "The Browser's Home-Folder Scan Runs Off the FX Thread"
labels: ["bug", "ui", "browser", "threading", "startup"]
---

# The Browser's Home-Folder Scan Runs Off the FX Thread

## Motivation

The browser panel's FILES tree is built from a scan of the user's home folder, and the scan runs on the FX thread. `BrowserPanel`'s constructor calls `fileModelRoot = buildFileModel();`. `buildFileModel` reads `user.home`, lists the home folder and, for each visible folder in it, lists that folder's entries and tests each one with `gc.isFile() && isAudioFile(gc.getName())`. The disk lookup comes before the cheap name test, so every entry one level below the home folder costs a disk lookup, audio file or not.

The application builds the panel on the FX thread at start-up: `MainController.initialize` calls `buildBrowserPanel`, which runs `new BrowserPanel(dispatcher())`, so start-up stalls for as long as the scan takes. Story 332's close-out investigation repeated the scan's disk calls: a Linux home folder with 201 folders and 3,903 entries one level below them took about 24 s with a cold file cache and 15 ms with a warm one, and a Windows home folder with 502 entries took 36 ms. A large home folder that is not in the file cache freezes the window for the whole scan.

The same scan makes FX tests fail on such a machine. In story 332's Linux validation, `BrowserTabIndicatorTest.activeTabHasAccentUnderBarPreviousDoesNot` built the panel inside an FX helper whose 5-second wait ignores its result, so a cold scan surfaced as a `NullPointerException` at line 90. Thread dumps showed the FX thread inside `buildFileModel` while the wait expired, and the test failed on its own with a cold cache and passed with a warm one. The FX thread keeps scanning after a helper gives up, so the next FX test class's responsiveness check (`JavaFxToolkitExtension.verifyFxThreadResponsive`, 10 s) or its first bounded FX wait can fail too.

The javafx-application-design skill's §11 "Threading" says never to block the FX thread and to do file I/O as background work, and its §15 "Anti-patterns to call out" lists blocking I/O on the FX thread. The defect predates story 332: the home scan came with the browser panel's FILES section (commit `c9609585`), and `buildFileModel` took its current shape in commit `3e23a2d9`.

## Goals

- **The scan runs on a worker.** The constructor starts the FILES tree from an empty model (the "File System" root with no children) and starts the scan on a worker thread; the FX thread makes no file-system call for the FILES tree. The result is handed back through the panel's existing `postFx`, which installs the model and rebuilds the visible tree for the search text current at that moment (`rebuildFileTree(searchField.getText())`).
- **The name test comes first.** Every entry is tested with `isAudioFile` on its name before `isFile()`, so an entry whose name is not an audio file costs no disk lookup.
- **Tests wait on a real signal.** A test that checks the FILES tree's contents waits on a real completion signal, for example a package-private future completed on the FX thread after the rebuild, or a package-private constructor that takes the model source (`Supplier<FileNode>`). No test timeout is raised. Today no test checks more than the tree's root label, which the empty model also has.
- **A helper's timeout reads as a timeout.** The FX test helpers that ignore their latch result assert it, so a wait that expires fails as a timeout instead of as a `NullPointerException` later: among them the helpers of `BrowserTabIndicatorTest`, `BrowserPanelTest`, `BrowserPanelControllerTest`, `BrowserHoverStyleTest`, `SidebarButtonActionsTest` and `BrowserRowAuditionTest`, and any other helper of a test that builds a `BrowserPanel` and ignores its wait.

## Acceptance Criteria

1. Constructing a `BrowserPanel` on the FX thread makes no file-system call for the FILES tree; the home-folder scan runs on a worker.
2. Once the scan completes, the FILES tree shows the same entries as before this story (the home folder's visible folders, each with the audio files directly inside it, and the audio files directly in the home folder), pruned by the search text current when the result is installed.
3. No entry whose name is not an audio file is asked `isFile()`.
4. Application start-up does not wait for the scan.
5. No test timeout is raised, and every helper the last goal covers asserts its wait.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **The constructor does not scan**: with the model source held (a seam that blocks until released), constructing the panel on the FX thread returns, and a runnable posted to the FX thread afterwards runs while the scan is still held; once the scan is released, the FILES tree shows the model the source returned.
- **The search text survives the hand-back**: with the scan held, a search text is set; after the release, the FILES tree is pruned to that text.
- **Name before `isFile()`**: over a temporary folder used as the home folder, holding audio files, other files and a folder whose name ends in an audio extension, the model holds exactly the audio files, and an entry whose name is not an audio file is never asked `isFile()` (through a seam over the per-entry check).
- **Helpers fail as timeouts**: with the FX thread held past a helper's wait, the test fails on the helper's assertion, not on a `NullPointerException`.
- **Fault check**: with the scan put back in the constructor, the "constructor does not scan" test turns red.

## Non-Goals

- Scanning deeper than one level, refreshing the tree when the home folder changes, or choosing a different root.
- Changing which entries the FILES tree shows.
- Any change to the tests that happened to run before the failing one in story 332's validation (`SaveOutcomeNotificationTest`, `SaveFlowFixture`); the investigation ruled them out.

## Technical Notes

- Code locators: `BrowserPanel`: the constructor's `fileModelRoot = buildFileModel();` (BrowserPanel.java:244); `buildFileModel` (:776-806, with `homeDir.listFiles()` at :782, `child.listFiles()` at :791 and `gc.isFile() && isAudioFile(gc.getName())` at :794, and the same order for files directly in the home folder); the `final` field `fileModelRoot` (:140); `postFx` (:455); `rebuildFileTree` (:820); `isAudioFile`. `MainController.initialize` → `buildBrowserPanel` (MainController.java:1224, :3199-3200). Test helpers that ignore their wait's result: `BrowserTabIndicatorTest` :43, `BrowserPanelTest` :33 and :46, `BrowserPanelControllerTest` :28, `BrowserHoverStyleTest` :47, `SidebarButtonActionsTest` :35, `BrowserRowAuditionTest` :64. Tests that build panels behind their own bounded waits: `BrowserSearchScopeTest`, `RightPaneToggleRaceTest`. `JavaFxToolkitExtension.verifyFxThreadResponsive`.
- Found in story 332's close-out investigation (PR 988). Until this story lands, a Linux validation run can warm the file cache first (`find ~ -mindepth 2 -maxdepth 2 -printf '%s\n' >/dev/null`); that is an environment workaround, not a fix.
- Design references: javafx-application-design skill §11 "Threading" and §15 "Anti-patterns to call out".
- Cross-refs: story 275 (the FILES model and its search-pruned rebuild), story 289 (the `FxDispatcher` seam behind `postFx`), story 332 (where this was found; its full-suite validation).

## Status

- Deferred

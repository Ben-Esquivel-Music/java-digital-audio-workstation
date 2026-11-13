---
title: "Failures Held in Futures, Tasks and Executors Reach the Failure Funnel"
labels: ["bug", "error-handling", "reliability", "logging", "notifications"]
---

# Failures Held in Futures, Tasks and Executors Reach the Failure Funnel

## Motivation

Story 336 installs a process-wide default uncaught-exception handler and an FX-thread handler. Both route to one funnel, which writes the failure to the log file and shows it as an ERROR toast (`FAILURE_SURFACING_DESIGN_BOOK.md` §4.1). An uncaught-exception handler sees only a throwable that reaches the top of a thread. A failure that an asynchronous construct captures never gets there. It is logged only where the site logs it, and shown nowhere (story 336, ruling E12.12 and the story's Known limits):

- **`CompletableFuture`.** A throw inside `supplyAsync` or `runAsync` completes the future exceptionally, and only a stage that reads the future ever sees it. Ruling E12.12 counted 8 such calls in main source. A re-count with `git grep` at story 336 finds the same 8: `RecordCoordinator.java:1248`, `:1281`, `:2450` and `:2486`, `SaveAsFlow.java:419` and `:429`, and `ConvolutionReverbProcessor.java:328` and `:341`.
- **An executor's `submit`.** `submit` wraps the task in a `FutureTask`, which keeps the throw for a `Future.get()` that may never come. The ruling counted 10 `.submit(` calls, and the same 10 lines match today:
  - six executor submits: `TestTonePlayer.java:82`, `VelvetNoiseReverbProcessor.java:280`, `ConvolutionReverbProcessor.java:220`, `BundleExportService.java:309`, `RenderQueue.java:92` and `AsioControlThread.java:328`;
  - `DawTaskRunner.submit` (`BundleExportService.java:342`) and `MasteringChain.ParameterControl.submit` (`MasteringStageControls.java:143`), which this story classifies by what they do with a throw;
  - a `SubmissionPublisher.submit` (`MockAudioBackend.java:527`) and one Javadoc line (`AudioBackendSupport.java:143`).
- **JavaFX `Task`.** A `Task` keeps its failure in its `exception` property and reports it only to its `onFailed` handler. The ruling counted 3 classes. They hold 5 anonymous `Task`s: `HelpDialog.java:284`, `RenderCacheStatsDialog.java:116` and `:144`, and `PluginEditorSession.java:997` and `:1085`. Each sets `onFailed`; what each handler does with the failure has not been checked.
- **`SubmissionPublisher`.** When a subscriber's `onNext` throws, the publisher passes the throw to that subscriber's own `onError` and closes its subscription (`SubmissionPublisher.java:1350-1357` and `:1396-1412`, JDK 27). The subscriber stops receiving, and nothing else hears of it unless the publisher was built with an `onNext` handler. None of the 14 `new SubmissionPublisher` constructions in main source (10 files) passes one.
- **`ScheduledThreadPoolExecutor`.** When one run of a periodic task throws, the throw is kept in its `ScheduledFuture` and every later run is suppressed without a word (`ScheduledExecutorService.java:136-146`, JDK 27). Main source creates one such executor directly (`CheckpointManager.java:908`) and five through `Executors.newSingleThreadScheduledExecutor` (`DefaultAudioEngineController.java:178` and `:203`, `TestTonePlayer.java:45`, `SessionStatusDiskScanner.java:63`, `ProjectLockManager.java:342`). Three tasks are periodic: the monitor-event drain (`DefaultAudioEngineController.java:518`), the session status disk scan (`SessionStatusDiskScanner.java:79`) and the project lock heartbeat (`ProjectLockManager.java:356`).

Example: `MainController.retirePluginProject` disposes a replaced project's inserts through `disposeInsertsWhenQuiescent()` and logs a failure in `exceptionally` (`MainController.java:3226-3232`). After story 336 that WARNING reaches the log file, but nothing is shown. The engineer learns that a plugin failed while it was closed only by reading the log (story 336's note from story 334).

Two plugin dispose sites also stay outside the plugin naming that story 336 gives the insert rack's and the plugin menu's dispose sites (ruling E10). There, the site declares the plugin through story 336's `DeclaredSubject`, and the toast names the plugin. `unpublished-plugin-dispose` (`PluginInstallPanel.java:441`) and `daw-plugin-registry-dispose` (`PluginManagerDialog.java:340`) declare no subject. They catch a disposal failure and only log it, and the install panel also completes a future with it. Anything that escapes them reaches the funnel under the generic background-task copy, which names the thread ("unpublished plugin dispose") instead of the plugin.

## Goals

- **Every site is classified.** Each site in main source that hands work to a `CompletableFuture`, an executor's `submit`, a JavaFX `Task`, a `SubmissionPublisher` subscriber or a `ScheduledThreadPoolExecutor` is listed with what happens to its failure today: shown by a surface of its own, logged only, or read by nothing.
- **Each site whose failure is not shown reports it into the story-336 funnel exactly once**, as that exception, when the failure happens. The funnel logs and shows it under its rate limit (book §6.2). A site whose failure already reaches a surface of its own keeps that surface and is not reported a second time.
- **The two plugin dispose sites name the plugin.** `unpublished-plugin-dispose` and `daw-plugin-registry-dispose` declare the plugin as their subject through story 336's `DeclaredSubject`. Their failure toast then uses the plugin dispose copy and names the plugin, as the insert rack's and the plugin menu's dispose sites do.

## Acceptance Criteria

1. A disposal failure in `MainController.retirePluginProject` writes one log record and shows one ERROR toast.
2. Every classified site whose failure nothing read reports it to the funnel once, and no site reports a failure that it already shows.
3. A failed disposal on `unpublished-plugin-dispose` or `daw-plugin-registry-dispose` shows a toast that names the plugin.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- A replaced project whose insert's `dispose()` throws: the funnel writes one SEVERE record carrying that exception, the bar shows one ERROR toast, and the history accounts for it once.
- One of the three periodic tasks throws once: one funnel record and one toast, at the time of the throw, not at a later read of its future.
- A subscriber of one of the classified publishers throws from `onNext`: one funnel record and one toast.
- A site whose failure already reaches its own surface (for example a `Task` whose `onFailed` shows the failure) adds no funnel record.
- A plugin that the install panel does not keep, and a plugin removed in the Manage Plugins dialog, each throwing from `dispose()`: one toast in the plugin dispose copy, naming the plugin.
- Fault check, run once, turning a test red: the report removed from `retirePluginProject`, so its disposal failure shows nothing.

## Non-Goals

- The handlers, the funnel, the log sink and the rate limit: story 336.
- The audio thread and the FX pulse: story 337 (the RT channel and pulse isolation). Nothing here adds work to a render path (book §2.3). One of this story's sites is on a render path: with four or more late-reverb segments, `VelvetNoiseReverbProcessor.process` (`@RealTimeSafe`) submits each segment to a virtual-thread executor through `processLateSegments` (`VelvetNoiseReverbProcessor.java:275-281`) and waits on their futures (`:285`), so that site's report runs neither in the render thread's catch (`:286`) nor in a segment's task, which the render thread waits on. Book §2.3 gives its route instead: the failure is recorded on story 337's RT channel with atomic stores and no lock, log or notification, and that channel's drain on the FX pulse turns the record into a surface (book §4.3).
- Production notification injection into `DefaultAudioEngineController` and `MixerView`: story 339.
- Moving a site onto a different concurrency construct.

## Technical Notes

- Seams: story 336's `FailureFunnel.reportDeclared` and `DeclaredSubject` (daw-app, package `app.diagnostics`). daw-core and daw-sdk cannot reference daw-app (`docs/research/open-source-daw-tools.md` "### 1. Modular Architecture (SDK + Core + App)"). Their sites need a route that does not name the funnel, for example handing the failure to `Thread.getDefaultUncaughtExceptionHandler()`, which story 336 sets to the funnel's default handler.
- A site reports the exception it holds. The funnel's guarded reading of a throwable covers a plugin exception whose class loader is already closed (story 336's note from story 334).
- javafx-application-design "## 11. Threading" makes `Task` and `Service` the tools for background work, so a `Task`'s `onFailed` is where a `Task` reports.
- Book: `FAILURE_SURFACING_DESIGN_BOOK.md` §2.1 (no failure is silent; a worker task is one of its boundaries), §4.1 (the exception spine), §5.4 (notification discipline) and §6.2 (rate limiting).
- Cross-refs: story 336 (rulings E10 and E12.12, its Known limits, its note from story 334), story 334 (the dispose threads), story 337, story 339.

## Status

- Deferred

# Gap Audit Notes — 2026-05-25

Items considered during the gap audit but **not** filed as new user stories. The
user adjudicates. Each entry explains the reasoning so the disposition can be
re-examined without re-doing the investigation.

## Held off — borderline cases

### `DetachPluginRequestedEvent` has no production subscriber
- **Story:** user story 281 (Workshop View) emits `DetachPluginRequestedEvent`
  from the Workshop breadcrumb's Detach button (`PluginViewContainer`).
- **Observed:** the only consumer of
  `DetachPluginRequestedEvent.DETACH_PLUGIN_REQUESTED` is
  `WorkshopDetachEventTest` in `daw-app/src/test/`.
- **Why held off:** the story explicitly designates the event as a "stub" that
  user story 282 (Mission Control) is meant to consume. User story 282 is
  itself blocked on the `DockManager`/`LayoutManager` wire-up gaps already
  filed (`285` and `286`). Filing a third story for "subscribe to this event"
  would duplicate scope inside the same Mission Control follow-on. Story 286
  already includes "wire `PanelDetachRequestedEvent` and
  `PanelDockRequestedEvent` so panels … flow through `DockManager`'s float /
  re-dock API" — `DetachPluginRequestedEvent` belongs in the same wire-up.

### `CueLaunchRequestedEvent` has no production subscriber
- **Story:** user story 280 (Performance Stage View) emits
  `CueLaunchRequestedEvent` from the cue-launch buttons.
- **Observed:** the only consumer is `PerformanceStageCueEventTest`.
- **Why held off:** the story-280 memory entry explicitly says
  "Static-placeholder telemetry deferred. Uncommitted — don't re-review or
  re-file as gaps." `CueLaunchRequestedEvent` is part of that deferred surface
  — it is a stub today by design until cue/scene playback lands as its own
  story (it requires an Ableton-Live-style scene-launch model which isn't in
  the backlog yet).

### `HeadlessAudioHarness` only used by its own tests
- **Story:** user story 207 (Headless Audio Test Harness).
- **Observed:** `@ExtendWith(HeadlessAudioExtension.class)` appears only in
  `HeadlessAudioHarnessTest` and `HeadlessAudioBackendTest`.
- **Why held off:** test infrastructure is a different category than feature
  surfaces. The harness is *available* for end-to-end audio-engine tests; the
  fact that no current test exercises an end-to-end project render isn't a
  gap in story 207, it just means follow-on tests haven't been written yet.
  Per `feedback_test_only_callers_are_not_live_usage.md` the rule is "API
  kept alive only by tests of itself is dead" — but this is the inverse:
  an API explicitly *for tests*. Not a gap.

### `DspRegressionHarness` lives in `daw-core/src/test/`
- **Story:** user story 210 (Plugin DSP Regression Test Framework).
- **Observed:** the harness is used by 18 `*RegressionTest` classes, all of
  which live in `daw-core/src/test/java/com/benesquivelmusic/daw/core/dsp/regression/`.
- **Why held off:** the story explicitly requires this — "Add
  `DspRegressionHarness` in `daw-core/src/test/java/.../dsp/`". 18 processor
  regression tests using the harness is exactly the goal.

### `@HardcodedColorAllowed` annotated on 57 files
- **Observed:** 57 files carry the annotation (memory says 56 — one drift).
- **Why held off:** per memory entry
  `project_story_277_landed.md`, "56 files @HardcodedColorAllowed (debt
  marker, not a refactor)". One additional file isn't worth filing a
  follow-up — the count fluctuates as new files land that opt in.

### `ProcessorRegistry.getInstance()` is `@Deprecated(forRemoval=true)`
- **Why held off:** memory entry
  `project_processor_registry_singleton_deferred.md` says removal is blocked
  on `InsertEffectFactory` static→instance migration. Explicitly off-limits.

### `dockManager != null` dead branches in `MainController#onToggleDockMixer/Browser/Arrangement`
- **Why held off:** these are the secondary symptom of the user story 285 gap
  (the `dockManager` field is forever null in production). User story 285
  already mandates removing them: "The F3 / F4 / F5 toggle handlers at
  `MainController.java:1151-1174` lose their `else if (dockManager != null …)`
  dead branches". No separate story needed.

### `MixerView` migration to `MixerChannelStrip` not done
- **Why held off:** memory entry
  `project_story_271_partial_migration_deferred.md` explicitly defers
  this; "MixerView migration to .mixer-channel migration is a tracked
  next-cycle follow-up, alias retained one cycle". Out-of-scope.

### Drag-target highlighting on `BrowserPanel` rows
- **Why held off:** user story 248 wired `DragVisualAdvisor` into
  `BrowserPanel`, `ClipInteractionController`, and `InsertEffectRack`. Imports
  and call sites exist. Not a gap.

## Considered as a gap, then disproven by re-reading the code

These are *not* gaps — listed only so a future audit can skip them.

| Surface | Suspected gap | Disproven by |
| --- | --- | --- |
| Story 077 menu bar / View → Workshop | "Is Workshop reachable from the menu?" | `MenuConstructionService.java:342-344` adds the Workshop entry. |
| Story 273 NotificationsSection in inspector | "Is `NotificationHistoryPanel` deleted?" | Grep returns zero matches; `NotificationsSection.java` is the replacement. |
| Story 244 BackupSettingsDialog wired | "Is it reachable from a menu?" | `MainController.java:1567` + `:1952` + `MenuConstructionService.java:188`. |
| Story 247 MigrationReportDialog | "Does load surface migrations?" | `ProjectLifecycleController.java:656` `MigrationReportDialog.showIfNeeded(...)`. |
| Story 219 platform audio backends | "Does the controller create ASIO?" | `DefaultAudioEngineController#applyBackendByName` routes through `AudioBackendSelector#selectByName` at line 1064. |
| Story 240 HRTF SOFA import | "Is the dialog reachable?" | `BinauralMonitorPluginView`, `HrtfProfileImportDialog`, `HrtfProfileBrowserDialog` all wired. |
| Story 250 RoomTelemetryDisplay GPU canvas | "Is `GpuPipeline.detect` surfaced?" | `LatencyTelemetryPanel.java` consumes it. |
| Story 255 MetronomeSideOutputRouter | "Does the engine invoke `route()`?" | `AudioEngine.java:843` + `:926` set and invoke the router on every buffer cycle. |
| Story 109 @RealTimeSafe contract enforcement | "Is the reflective audit running?" | `RealTimeSafeContractTest.java` enforces five invariants under `mvn test`. |
| Story 230 / 231 mixer surfaces | "Are VCA / Link-stereo wired?" | `MixerView.java` references `VcaGroupManager`, `ChannelLinkPopover`, etc. |
| Story 239 Object Panner Automation | "Does `ObjectParameter` reach the spatial panner UI?" | `SpatialPannerController.java` + `spatial/SpatialTrajectoryOverlay.java`. |
| Story 232 / 233 plugin wrappers / templates | "Are they reachable?" | `PluginViewController`, `TrackTemplateController` both wired into `MainController`. |
| Story 256 asioshim CI | "Is the workflow present?" | `.github/workflows/windows-asioshim.yml` is committed and runs on path-filtered PRs. |
| Story 284 GpuCanvasView | "Are all 10 displays migrated?" | All 10 `extends GpuCanvasView`. Story 284 itself is uncommitted but landed in source. |

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

---

# Follow-up Audit — 2026-05-30

Second pass after stories **285** (DockManager wire-up, PR #877) and **286**
(LayoutManager wire-up, PR #878) both landed and the tree went clean. With the
docking foundation now live, the focus was: did 285/286 fully close out their
parent feature story **282 (Mission Control)**, and are there model-only
features elsewhere with no production surface?

## Filed as new stories

| # | Gap | Decisive evidence |
| --- | --- | --- |
| 287 | 282 Goal "visualisation tiles as first-class panels" (spectrum / correlation / loudness / tuner / room-3D) undelivered — they remain a fixed `@FXML` `HBox` row + standalone `*DisplayWindow`s. | Only 5 panels registered (`MainController.java:1795-1805`); no `ui/display/*` type `implements Dockable`; appears in no Goal/Non-Goal of 285 or 286. |
| 288 | 282 Goal "grip handle → drag to detach" + "dock targets light up with `-accent-soft` overlay" undelivered. 286 wired the *receiving* event bridge but nothing *fires* the events and there is no grip/drop-zone gesture. | `new PanelDetach/DockRequestedEvent(...)` exists only in 4 test lines; no `setOnDragDetected`/drop-zone in `ui/dock/`; bridge passes `null` bounds (`MainController.java:1879`). 285 line 63 explicitly assigns "grip handles / drop-zone highlight" to story 282 — i.e. in-scope, undone, on no deferral list. |

288 deliberately handles **panel** detach (`PanelDetachRequestedEvent`), **not**
the deferred **plugin** `DetachPluginRequestedEvent` stub (held-off item above).

## Re-confirmed done — NOT gaps (skip next time)

- **Rest of 282** is delivered: `LayoutManager` + View → Layout menu + dock
  manifest bar + per-project persistence via 286 (`MainController.installLayoutManager()` ~`:1835`,
  manifest `:1900-1932`, persistence `ProjectLifecycleController.java:150,639`);
  arrangement-as-dock-host via 285 (`ArrangementDockable`, `DockZone.CENTER`).
  The CENTER-single-selection (vs free/tabbed) residue is the *tabbed dock
  targets* item **both 282 (line 66) and 285 (line 62) explicitly defer** — not a gap.
- **DSP plugins 155-169** (BusCompressor, MatchEq, Mid/Side, Multiband,
  De-esser, True-Peak Limiter, Transient Shaper, Noise Gate, Convolution Reverb,
  Exciter, Dither): all discoverable as `ServiceLoader` providers in
  `daw-core/.../module-info.java`. NB: discovery migrated sealed-`permits` →
  `ServiceLoader`, so `dawg-annotations-reflection` SKILL §1/§2 is stale on that
  point (informational — not in scope to fix here).
- **192** Command Palette wired; **168** ISRC/CD-Text (`AlbumAssemblyView`);
  **249** comping (`CompToolHandler`); **241** Atmos A/B instantiated; **234**
  Track Freeze menu items; **250-254/284** GpuCanvas migration (all displays
  `extends GpuCanvasView`).
- **TelemetrySetupPanel** does NOT implement `Dockable` and is NOT registered
  (the `Dockable` contract was deferred entirely — see the "Dockable contract
  deferred" comment at `TelemetrySetupPanel.java:138-142`, which hands the work
  to "a future story … once there is a consumer"). **User escalated 2026-05-30 →
  folded into story 287** as `PANEL_TELEMETRY` / `DockZone.RIGHT`, with the
  ownership caveat that it is the setup-state child of `TelemetryView` (register
  a single shared instance). No longer "left for the user".

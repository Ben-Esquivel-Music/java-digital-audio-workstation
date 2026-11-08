---
title: "The Window's Rebuild Under Test"
labels: ["enhancement", "testing", "ui", "reliability"]
---

# The Window's Rebuild Under Test

## Motivation

Story 333's File ▸ Close Project resets the window as New does, “the same replacement New performs, without creating a folder” (story 333, ruling R10). Both install their Untitled project through one replacement, `ProjectLifecycleController.installFreshUntitledProject`, whose `rebuildUI()` runs the window's own rebuild: in the application, `MainController.handleProjectRebuild`. Three of the resets the user relies on come only from that rebuild:

- **A new transport controller, the old one retired.** `createTransportController()` builds a new `TransportController` and retires the one it replaces once its successor exists (`previous.retire()`), so a take whose Stop is still pending is not published into the closed project.
- **The closed project's meters released.** `viewNavigationController.setMixerView(newMixerView)` disposes the replaced mixer view, with its meters and its insert racks (`ViewNavigationController.setMixerView`: `if (mixerView != null && mixerView != newMixerView) mixerView.dispose();`), and the new view is subscribed to the meter feed (`newMixerView.setMeterFeed(meterFeed())`).
- **The audio engine rebound, the device left open.** `rebuildViewModels()` binds the engine to the new project (`engineBinder.bind(project)`). `EngineBinder.bind` swaps the engine's graph and rebinds the metering tap bus under a new epoch, which disposes every tap subscription of the closed project's epoch; it starts, stops, opens and closes nothing.

No test runs that rebuild. `MainController` is never FXML-loaded in tests: loading `main-view.fxml` through `FXMLLoader` runs `MainController.initialize()`, which starts a real `AudioEngine` and the autosave timers and would hang a headless test (the reason `StatusBarStyleTest` and `StatusBarFxmlTest` give for not loading it). So the resets are pinned by source instead:

- `CloseProjectResetsToUntitledTest.theWindowsRebuildBehindCloseProjectIsTheOneThatResetsTheWindow` pins that the lifecycle rebuild is `this::handleProjectRebuild`, and that its body contains `pluginViewController.reconcileGraph();`, `newMixerView.setMeterFeed(meterFeed());`, `createTransportController();`, `rebuildViewModels();`, `wirePluginRackEditors(newMixerView);` and `viewNavigationController.setMixerView(newMixerView);`.
- `CloseProjectResetsToUntitledTest.newAndCloseProjectInstallTheirUntitledProjectThroughOneReplacement` pins that New and Close Project share `installFreshUntitledProject`.
- `ProjectChangeWhileTakeIsWrittenTest.mainControllerFeedsTheCurrentControllersAnswerAndRetiresTheControllerItReplaces` pins, by source order, that `createTransportController()` captures the controller it replaces, builds its successor, then retires it.
- `EngineBinderConformanceTest.mainControllersRebuildViewModelsBindsTheProjectThroughTheEngineBinder` pins the engine's bind.

The mechanisms are tested on their own — `EngineBinderMeteringTest.rebindDisposesEpochNSubscriptionsAndUnbindEmptiesTheSnapshot` in daw-core, `MixerPluginLifetimeTest` through a real `ViewNavigationController`, `MixerViewMeterFeedTest.detachingTheViewFromItsSceneReleasesEveryMeterSubscription` — but no test asserts them for either door. Story 333's Resolution records these resets as a known-limit category, pinned by source and asserted by no test of the door, for New as for Close Project, with this story as its owner. Three of ruling R10's resets — the new transport controller, a meter feed that carries no channel of the closed project, and the rebuild's half of an unchanged audio backend open count — rest on that category, and so does story 333's Technical Notes obligation that File ▸ Close Project retire the outgoing `TransportController`.

## Goals

- **The rebuild runs in a test.** `handleProjectRebuild` — or what it becomes — runs headless, without the FXML load. Either the rebuild moves into a unit that is given its collaborators and can be built without `MainController.initialize()`, or a partial `MainController` is given the fields the rebuild reads, as `CloseProjectResetsToUntitledTest.closeProjectNeverStopsTheAudioEngineAndTheExitAfterItDoes` already builds one (`new MainController()`, fields set reflectively, `installPrimaryStage`). The Resolution says which, and why.
- **New and Close Project are asserted alike.** For both doors, at least:
  - the transport controller is replaced, and the outgoing one is retired after its successor exists;
  - the replaced mixer view holds no meter subscription, and no tap subscription of the closed project's epoch is left;
  - the audio engine is bound to the new project.
- **No device open or close during the rebuild.** Every open and close of the audio device is counted through the rebuild of either door, and the count is zero.
- **Story 333's category closes.** Once the assertions land, story 333's Resolution marks its window's-rebuild category as closed by this story. Each source pin that stood in for an assertion either stays, as a pin of the wiring, or is removed; the Resolution names each and the reason.

## Acceptance Criteria

1. A headless test runs the window's rebuild behind New Project and asserts: a new transport controller, the previous one retired after its successor exists; no meter subscription of the replaced mixer view left; the engine bound to the new project.
2. The same test case runs behind File ▸ Close Project, through the close protocol, and asserts the same.
3. Through either rebuild, no audio device is opened or closed.
4. Story 333's Resolution marks its window's-rebuild category as closed by this story.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **New's rebuild**: criterion 1, through New Project's replacement.
- **Close Project's rebuild**: criterion 2, through the protocol (the exit fixture's Close Project, as `CloseProjectResetsToUntitledTest` drives it), so the take rule and the replacement run as in the application.
- **Device untouched**: criterion 3, with a counting double of the audio engine or its backend. `closeProjectNeverStopsTheAudioEngineAndTheExitAfterItDoes` already verifies, with `mock(AudioEngine.class)`, that Close Project never calls the engine's `shutdown()`; this test counts the device's opens and closes through the rebuild itself.
- **Fault checks**, each run once, each turning a test of this story red: `createTransportController()` without its `previous.retire()`; `handleProjectRebuild` without `viewNavigationController.setMixerView(newMixerView)`; `rebuildViewModels()` without `engineBinder.bind(project)`.

## Non-Goals

- Which resets a door makes: story 333's ruling R10 decides them; this story only asserts them.
- Loading `main-view.fxml` in tests.
- The selection's clear and the close of the closed project's plug-in editors, which `CloseProjectResetsToUntitledTest` already asserts through `MainController.replacePluginProject` (`closeProjectClearsTheTimeRangeAndTheClipSelectionOfTheClosedProject`, `closeProjectDisposesTheClosedProjectsPluginsAndClosesTheirEditor`).

## Technical Notes

- Code locators: `MainController` — `handleProjectRebuild`, `createTransportController`, `rebuildViewModels`, `wirePluginRackEditors`, `replacePluginProject`, `initialize`; `ProjectLifecycleController` — `installFreshUntitledProject`, `rebuildUI`, `installUntitledAfterClose`, `createNewProjectAfterClose`; `ViewNavigationController.setMixerView`; `MixerView.dispose`; `EngineBinder.bind`; `TransportController.retire`; the tests named above.
- The retirement is the second guard, not the first. The take rule makes Close Project wait until `isTakeBeingWritten()` is false before any replacement (story 333's Take-at-exit test for the File ▸ Close Project door, `TakeAtExitTest`), and the window is input-inert from GUARD on, so no take can start before the rebuild. The retirement keeps a take published late from reaching the replaced project.
- The outgoing view's meters are released on more than one path, all inside the rebuild: the engine's rebind disposes the closed epoch's tap subscriptions, `setMixerView` disposes the replaced view, and a view that leaves the scene releases its own subscriptions (`MixerViewMeterFeedTest`). A test of the door asserts the outcome — no subscription left — not one path.
- Ruled by the studio engineer in story 333 (the ruling on app fix pass 2b, §3): the rows whose only missing link is the window's rebuild go to one known-limit category, owned by this story, “the window's rebuild under test”, whose scope the first three Goals carry; the studio engineer's final ruling F18b puts story 333's `TransportController` retirement obligation in the same category. Found by story 333's app fix pass 2b report.
- Cross-refs: story 333 (ruling R10, the window's-rebuild category), story 323 (`TransportController.retire`), story 314 (`EngineBinder`), story 318 (the metering tap bus and the meter feed), story 322 (the control wiring the rebuild renews first).

## Status

- Deferred

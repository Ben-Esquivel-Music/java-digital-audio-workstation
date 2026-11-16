---
title: "Restart Audio Engine Is a Menu Command"
labels: ["feature", "audio-engine", "menus", "reliability"]
---

# Restart Audio Engine Is a Menu Command

## Motivation

Story 337 built Restart Audio Engine, `MainController.restartAudioEngine()`, as a notice action only. Its one production caller is the window's audio health drain (`MainController.attachAudioHealthDrain`: `() -> restartAudioEngine()`), which wraps it as `new NoticeAction(copy.restartAudioEngineLabel(), collaborators.restartAudioEngine())` and offers it on the notices a stream restart can cure (`AudioHealthDrain.actionOf`: `case CALLBACK, SINK, PACING, INPUT, DEVICE, RENDER_HALTED -> restartAction;`) and on a halt that is not fatal. A notice's entry under Window ▸ Toggle Notifications re-runs its action. No menu item, keyboard shortcut or `DawAction` runs it.

So where no such notice was posted in the window's session, the engineer has no Restart Audio Engine:

- The automation notice says "restarting the audio engine retries its automation" (`failure.audio.automation`), but its action is Open Log Folder (`case RENDER, WORKER, DSP, MIDI, PLUGIN, AUTOMATION, CONFIG -> openLogFolderAction;`).
- A Java Sound capture line that stalls, and an input stop that no notice reported, post nothing (story 337's Known limits and Accepted as built).

Story 337 gives the engineer a way around it: changing an audio setting marked "Engine reconfigure" under Edit ▸ Audio Settings… and applying it. That runs the same `DefaultAudioEngineController.applyConfiguration` as the restart, but Apply is enabled only once a setting has changed, so the engineer must change a setting to get it. It also does not clear a latched engine-fault fact itself: only a successful Restart Audio Engine calls `AudioHealthDrain.clearEngineFault()`, and after an apply a latched fact clears only by the drain's own rules (story 337, rulings E59, E71 and E87).

The notice's route already protects a take. `applyConfiguration` first takes a configuration lease (`DefaultAudioEngineController.beginConfigurationChange()`), which waits for the window's record guard, `RecordCoordinator.requestConfigurationChange()`. While a take is in flight the guard asks "Stop the take and apply?", stops the take only on OK, and grants the change once the record coordinator has settled. A declined guard restarts nothing (story 337, ruling E12).

## Goals

The requirement, as story 337's studio engineer ruled it:

- **Restart Audio Engine is a menu command**, reachable whether or not a notice offered it and whether or not a fault is latched.
- **It is one command.** Chosen from the menu or from a notice, it does the same thing. A successful restart from either route clears the latched engine-fault fact the same way (story 337, rulings E62, E71 and E87).
- **It never ends a take without asking.** While a take is in flight (being prepared, counting in or recording, MIDI-only included), choosing the command first asks the engineer to confirm stopping the take. Declining leaves the take recording and restarts nothing. Confirming ends the take exactly as Stop does, keeps everything it captured, and runs the restart once the take is written.
- **It never closes the device under a take being written.** While a take is still being written after its Stop, the restart waits for it.
- **One at a time.** A second restart never starts while a restart or an applied "Engine reconfigure" setting is already under way.
- **Renders are left alone.** A restart never changes or fails a freeze or export that is rendering.

## Constraints

- Story 337's ruling E12 stays: the restart runs on its own virtual thread, never on the FX thread or the render drive, and never starts playback or recording.
- The notices offer Restart Audio Engine where they do today (story 337, ruling E13).
- A successful restart keeps story 337's ruling E62's meaning: it returned normally and, if a stream was open when it began, one is open when it returns.

## Acceptance Criteria

1. With no notice posted and no fault latched, the menu command restarts the audio engine as a notice's Restart Audio Engine does.
2. A successful restart from the menu clears a latched engine-fault fact as one from a notice does, and one that did not succeed (story 337, ruling E62) leaves it latched.
3. With a take being prepared, counting in or recording, MIDI-only included, the command first asks the engineer to confirm stopping the take. Declining leaves the take as it was and restarts nothing. Confirming ends the take as Stop does, keeps everything it captured, and the restart runs once the take is written.
4. A restart chosen while a take is being written after its Stop runs only once the take is written, and the take is written complete.
5. A restart chosen while a restart or an applied "Engine reconfigure" setting is under way does not start before it has ended.
6. A freeze or export that renders while a restart runs completes, with the audio it renders when no restart runs.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- A daw-app test: with no notice posted, the menu command restarts the engine as the notice's action does. After a successful restart the window's latched engine-fault fact clears, and after one that did not succeed it stays latched, as `MainControllerRestartAudioEngineTest`'s `aRestartWithNoStreamOpenClearsTheFault` and `aRestartWhoseReopenFailedLeavesTheFaultLatched` show for the restart a notice runs.
- A daw-app test for each take state (being prepared, counting in, recording, MIDI-only recording): choosing the command asks first. Declined, the take goes on and nothing restarts. Confirmed, the take ends as Stop ends it, with everything it captured, and the restart runs after the take is written.
- A daw-app test: a restart chosen while a take is being written runs only after the take is written.
- A test: a second restart, and a restart chosen during an applied "Engine reconfigure" setting, each start only after the one under way has ended.
- A test: a freeze that renders while a restart runs completes, with the same audio as without the restart.
- A real-wiring test or source pin that the window's menu holds the command, and that the command and the notices' action run the same restart.
- `MainControllerRestartAudioEngineTest` and `FailureSpineWiringSourceTest` stay green.

## Non-Goals

- The watchdog's restart offer: story 338.
- Which notices offer Restart Audio Engine: story 337, ruling E13.

## Technical Notes

- daw-app: `MainController.restartAudioEngine()` and `restartAudioEngine(SettingsModel, AudioEngineController, BooleanSupplier, Runnable)`; `AudioHealthDrain` (`restartAction`, `actionOf`, `clearEngineFault()`); `NoticeAction`; `DefaultAudioEngineController.applyConfiguration` and `beginConfigurationChange()`; `RecordCoordinator.requestConfigurationChange()`; `SettingsDialog`, whose Apply runs `applyConfiguration` for an "Engine reconfigure" change; `MenuConstructionService`; `DawAction`.
- Cross-refs: story 337 (rulings E12, E13, E59, E62, E71 and E87; its Known limits); story 338, whose watchdog's restart offer reuses `MainController.restartAudioEngine()`.

## Status

- Deferred

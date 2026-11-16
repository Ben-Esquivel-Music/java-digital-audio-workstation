---
title: "An Offline Render Reports Its Own Audio Failures in Its Result"
labels: ["feature", "audio-engine", "export", "reliability"]
---

# An Offline Render Reports Its Own Audio Failures in Its Result

## Motivation

Story 337's code review round keeps every block of an offline render off the engine's worker pool and has each site that cannot record off a render thread write a log record instead (story 337, rulings E75 to E77): the return-bus cap is one WARNING per pipeline from `RenderPipeline.renderOffline`, a `ClapInsertEffect` crash is a SEVERE record, and a `ClapPluginHost.process` failure or a failed `VelvetNoiseReverbProcessor` late segment is a rate-bounded WARNING through `OffLaneFailureLog`. The person freezing or exporting sees none of it: a track freeze completes with audio that a failed insert changed, and a stem export completes with no error. A freeze replaces the live chain and an export is a deliverable, so either must be correct or visibly failed (story 337, ruling E92). On `main` the same offline failures were swallowed or only logged, so this is a feature, not a residual of story 337's findings.

## Goals

- **An offline render keeps its own failures.** It collects every audio failure of its render — insert, plugin, DSP, automation, the return-bus cap — in its own result, and its caller shows them: a track freeze, render in place, and stem and bundle export once the application calls them.
- **A freeze or render in place whose audio a failure changed fails visibly.** When an insert, plugin or DSP failure changed the rendered audio, it fails with "Freeze failed: …" naming the insert, leaves the track unfrozen, and changes nothing in the live session: no bypass and no quarantine count comes from an offline failure.
- **An offline failure leaves the live session's processors as they were.** A freeze, render in place or export renders the live session's own processor instances: `TrackFreezeService.freeze` and `RenderInPlaceService.render` run the channel's own effects chain, and `OfflineStemRenderer` the project's own mixer. Today a throw that `ClapInsertEffect` catches during one of them latches that live insert, and playback then passes the plugin through, because nothing in the application resets the latch. After any offline render, live playback processes through every insert it processed through before.
- **A freeze whose insert failed or was passed through because of a failure is never stored in, or served from, the rendered-track cache.** An insert is passed through because of a failure when its CLAP insert is latched, or when the plugin supervisor bypassed it after a fault without the user choosing it. An insert the user bypassed on purpose is not one, and a freeze of a track with such an insert stays cacheable. `TrackFreezeService.freeze` stores whatever it rendered in a `RenderedTrackCache` it is given and completes a freeze from a cache hit without rendering. The application passes it no cache today, so nothing is stored yet; the requirement holds once one is passed.
- **An export with such a failure fails.** It leaves no file that claims to be complete.
- **The return-bus cap is a warning in the export's result**, not a failure, because the file matches what playback plays.
- **An offline render parallelizes only on workers of its own**, never on the engine's pool, with output bit-identical to its sequential render.
- With the studio engineer, this story rules what a render does with an insert that had already failed when the render began: a latched CLAP pass-through, or a supervisor bypass the user did not choose.

## Acceptance Criteria

1. An offline render with an insert, plugin, DSP or automation failure returns it in its result, naming the insert or parameter, and its caller shows it.
2. A freeze or render in place whose render had an insert, plugin or DSP failure that changed the audio fails with "Freeze failed: …" naming the insert, leaves the track unfrozen, and leaves the live session's bypass and quarantine state as it was.
3. A stem or bundle export with such a failure fails and leaves no file that claims to be complete.
4. An export of a mix over the return-bus cap completes, and its result carries the cap warning.
5. An offline render on workers of its own is bit-identical to its sequential render, and no block of it runs on the engine's worker pool.
6. An insert that had already failed when the render began is handled as the studio engineer's ruling says.
7. After a freeze, render in place or export during which a CLAP insert's own catch met a throw, live playback still processes through that plugin.
8. A freeze given a rendered-track cache, whose render had an insert, plugin or DSP failure or passed an insert through because of a failure (a latched CLAP insert, or a supervisor bypass the user did not choose), leaves no cache entry for its key, and a later freeze of the same key renders again. A freeze of a track with an insert the user bypassed on purpose, and no failure, stores its entry, and a later freeze of the same key is served from it.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- A daw-core test per failure kind — insert, CLAP crash, CLAP processing error, DSP segment, automation, return-bus cap: the offline render's result carries it.
- A freeze test: a CLAP insert that crashes during the freeze fails it naming the insert, the track stays unfrozen, and the live chain's bypass and supervisor counts are unchanged. `TrackFreezeServiceTest.aFreezeWhoseClapInsertCrashesCompletesAndLogsTheCrash` pins today's completed freeze and changes with this story.
- An export test: a failing insert fails the export and leaves no file that claims to be complete; a mix over the cap exports with the warning in its result.
- A live-chain test for each of freeze, render in place and stem export: a `ClapInsertEffect` whose plugin host's `process` throws to it during the offline render, then a live block through the same chain is processed by the plugin, not passed through.
- A cache test: `TrackFreezeService.freeze` with a `RenderedTrackCache`, whose CLAP insert fails during the render, leaves the cache without an entry for its key, and a second freeze of that key renders.
- A cache test: a freeze with a `RenderedTrackCache` of a track whose insert the plugin supervisor bypassed after a fault leaves no entry for its key, and a freeze of a track whose insert the user bypassed stores its entry, which a second freeze of that key is served from.
- A parity test: an offline render on workers of its own is bit-identical to the sequential one, and `AudioEngineMultiCoreTest.anOfflineRenderRunsEveryInsertOnItsOwnThreadAndRecordsNothingInTheEnginesRecord` stays green.

## Non-Goals

- Live playback's notices and the engine's audio health record: story 337.
- CLAP processing failures reaching plugin-fault supervision in the live session: future story 451.
- Preventing the return-bus overflow: future story 452.
- Each stem's isolation under MUTE automation, and whether an export may run while the engine streams: future story 456.

## Technical Notes

- daw-core: `RenderPipeline.renderOffline` and `renderingOffline`, and `Mixer.mixDown`'s `parallelInserts` (story 337, ruling E75); `core.audio.health.OffLaneFailureLog`; `RenderThread.recordOnCurrentThread` and `AudioHealthRecord.recordOnCurrentThread`, which answer `false` off a render thread; `EffectsChain.processOffline`; `TrackFreezeService.freeze` (its `RenderedTrackCache` load and store) and `RenderInPlaceService`; `ClapInsertEffect`'s `faulted` latch, which only its `reset()` clears, reached through `EffectsChain.reset()`, which no production code calls; `OfflineStemRenderer`, which passes the pipeline an explicit fresh `AudioHealthRecord`; `ReflectiveParameterBinder.applyBindings`, which throws story 337's ruling E57 exception off a render thread.
- daw-app: `TrackFreezeController` ("Freeze failed: …").
- `PluginInvocationSupervisor` wraps every slot in production, so a throw from a supervised slot bypasses that slot in the live session (future story 451): an offline failure must not reach the live session's supervision.
- What the code tells apart today: an `InsertSlot` has one bypass flag, which the user's bypass (`MixerChannel.setInsertBypassed`) and the plugin supervisor's fault path (`slot.setBypassed(true)` in `PluginInvocationSupervisor`) both set. `TrackFreezeService.freeze` renders the channel's effects chain (`channel.getEffectsChain()`), which holds only the slots that were not bypassed when `MixerChannel.rebuildEffectsChain` last ran, and reads neither that flag nor `ClapInsertEffect.isFaulted()`.
- Cross-refs: story 337 (rulings E75 to E77 and E92; its Known limits); future stories 451, 452 and 456.

## Status

- Deferred

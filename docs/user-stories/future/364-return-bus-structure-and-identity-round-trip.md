---
title: "Return-Bus Structure and Identity Round-Trip"
labels: ["bug", "persistence", "mixer", "project-load"]
---

# Return-Bus Structure and Identity Round-Trip

## Motivation

Story 330 made track identity round-trip, so every id-keyed mixer reference to a track resolves after a reopen. Return buses got no such fix: the file does not say how many buses there are or which one a send means, so a reopened mixer can differ from the saved one.

- **The file is not authoritative for the bus list.** `Mixer()` always creates one return bus, `Reverb Return` (`Mixer.java:131-141`). `ProjectDeserializer.parseMixer` applies the i-th `<return-bus>` element to the i-th existing bus and creates a bus, by its saved `name`, only past the existing count (`ProjectDeserializer.java:1428-1446`). A bus name is final, so the reader cannot rename the default bus. The mixer lets the user remove any bus, the default one included (`MixerView` → `RemoveReturnBusAction`, `MixerView.java:2317`). Saved as `[Reverb Return, Return 2]` minus `Reverb Return`, the session reopens as one bus named `Reverb Return` holding Return 2's settings: the audio is the same, the label is wrong, and the user cannot rename it. An empty `<return-buses/>` (every bus removed) reopens with a default `Reverb Return` at unity, with no inserts or sends, taking one of the `Mixer.MAX_RETURN_BUSES` slots.
- **Return-bus ids are not written.** The `<return-bus>` writer emits the bus name and its channel settings, no id (`ProjectSerializer.java:615-621`). Sends and sidechain keys name a bus by its position: `send@target-index` (`:788-789`) and `sidechain-source="return:<index>"` (`:768`). The snapshot send writer says so itself: "`target-index` is what survives a reload, because return bus ids are regenerated when a project is opened" (`:1204-1211`). Only track strips can join a VCA group or a channel link today, but any feature that lets a return bus in needs its id first.
- **Master and return-bus sends are latent, model-only state.** No surface creates them: `MixerView` builds send controls only in `buildChannelStrip` (a track strip); `buildReturnBusStrip` and `buildMasterStrip` have none. The mix never renders them either: `Mixer.mixDown` and `mixDownInstrumented` call `routeSends` only inside their loops over the track channels, and the return buses sum into the master (`Mixer`'s class Javadoc), so a send from the master to a return bus would be a feedback path. The reader still reads a master send, and keeps it when it targets the default bus; one to a later bus is dropped without a report, because `parseMixer` applies the master's channel element (`:1422-1425`) before it creates the buses past the default one, and `parseSend` drops a send whose `target-index` is not yet a bus (`:1933-1942`).
- **A sidechain keyed from a later bus is lost.** `resolveSidechainSource` resolves `return:<index>` at once (`:1908-1923`), while the buses are still being created. A compressor on the master, or on an earlier return bus, keyed from a later return bus reopens with no key: its gain reduction stops, which is audible.
- **Removing a bus leaves dangling sends (latent model hygiene).** `Mixer.removeReturnBus` removes the sends to the bus from the track channels only (`Mixer.java:427-440`). Sends from the master and from other return buses — which, as above, no surface creates — stay in the model, are written with `target-index="-1"`, and are dropped on reopen without a report.

For an engineer a return bus is a named place in the mix: reverb, delay, parallel compression. A bus that reopens under another bus's name mislabels the mix, and a ducking key that no longer keys changes it, neither with a word to anyone (Persistence Integrity book §2.1, rejection §9.4).

## Goals

- **The file is authoritative.** When `<return-buses>` is present it sets the count, the order and the names of the return buses; an empty element means no return buses. Only a file without the element (a legacy file) gets the default bus.
- **Return-bus ids round-trip.** Each `<return-bus>` carries its id, and sends and sidechain keys that target a return bus name it by id. A schema bump through the story-188 registry migrates `target-index` and `return:<index>` to ids.
- **References resolve after the structure exists.** The reader builds the master, every return bus and every track channel first, then resolves sends and sidechain keys in a second pass, so no reference depends on parse order.
- **A send on the master is dropped.** The reader drops a send it finds on the master with a WARNING: the return buses sum into the master, so it would be a feedback path. The drop is logged only: it is not counted and arms no backup, because the send never rendered and no surface creates one.
- **No send survives its bus (latent model hygiene).** Removing a return bus removes every send to it — from track channels, the master and other return buses — and undo restores them all.
- **A reference that does not resolve is reported.** A send or key naming a bus the file does not hold is dropped, counted in the open's report (story 330's announcement), and arms the degraded-open backup.

## Acceptance Criteria

1. A project saved as `[Reverb Return, Return 2]` with `Reverb Return` removed reopens with one bus named `Return 2`, holding its settings, inserts and sends.
2. A project saved with every return bus removed reopens with no return buses.
3. A file written before the bump reopens exactly as it does today, and the next save writes return-bus ids.
4. A send found on the master is dropped with a WARNING (the return buses sum into the master, so it would be a feedback path), logged only: it is not counted and arms no backup, because the send never rendered and no surface creates one. A master compressor keyed from the second return bus is present after a reopen.
5. Latent model hygiene: after `Mixer.removeReturnBus`, no channel — track, master or return bus — holds a send to the removed bus; undo puts every one of them back.
6. The parity harness lists every new attribute, and `ProjectRoundTripInventoryTest` compares the return buses by id.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Default removed**: remove the default bus from `[Reverb Return, Return 2]`, save and reopen; names, count and settings are equal.
- **All removed**: remove every bus, save and reopen; there are zero return buses.
- **Master key from a later bus**: a master compressor keyed from Return 2 keeps its key after a reopen, and its gain reduction on a fixed signal is unchanged.
- **Send on the master**: a file whose master holds a `<send>` to a return bus opens with that send dropped and one WARNING (the return buses sum into the master, so it would be a feedback path); the announcement has no clause for it, `losesSavedContent()` is false, and the first save writes no `.bak`.
- **Remove bus** (latent model hygiene): with sends to a bus from a track, the master and another bus, `removeReturnBus` leaves no send to it, and undo restores all three.
- **Dangling bus reference**: a send and a sidechain key naming a return-bus id the file does not hold are dropped, counted in the announcement, and the first save writes one degraded `.bak`.
- **Migration**: a schema-2 fixture with `target-index` and `return:<index>` references reopens with every reference resolved to the same bus; the step has its own golden DOM-in/DOM-out test.
- **Harness**: removing the new return-bus id reader fails `ProjectSchemaParityContractTest` with a message naming it.

## Non-Goals

- Letting return buses join VCA groups or channel links; this story only makes their ids persist first.
- Sends from one return bus to another: they stay out of scope until a feature renders them, with cycle checks.
- Renaming return buses in the UI.
- Structural mixer edits racing the render thread (story 365, and the note story 322 left for `Mixer.returnBuses` and `MixerChannel.sends`).

## Technical Notes

- Code locators: `ProjectDeserializer.parseMixer`, `parseSend`, `resolveSidechainSource`, `applyMixerChannelAttrs`; `ProjectSerializer`'s `<return-buses>` writer and its send and sidechain writers; `Mixer()`, `Mixer.removeReturnBus`; `RemoveReturnBusAction`.
- Ruled by the studio engineer in story 330 (round 3, Q3, severity Should; merged with round 2's future item on return-bus identity). Story 330's round-trip family keeps `[Reverb Return, Delay Return]` without removing the default bus, and no test pins the wrong rename as expected behaviour.
- Design references: Persistence Integrity design book §3.1 (the return-bus row, PARTIAL, owner 364), §4.4 (the harness), §6.3 (schema evolution, the degraded-open backup), rejection §9.4.
- Cross-refs: story 330 (track identity, the open report, the degraded-open backup), story 188 (migration registry), story 367 (mixer containers degrade item by item).

## Status

- Deferred

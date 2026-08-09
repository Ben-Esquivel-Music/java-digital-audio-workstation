# Persistence Integrity Design Book

> A reference design for **round-trip fidelity and durability** in the Java
> Digital Audio Workstation: the guarantee that a project saved today reopens
> tomorrow with **everything still in it** — audio, MIDI, inserts, takes,
> tempo, transport setup — and that no crash, race, or exit path can silently
> destroy work in between. **No code in this document.**
>
> Companion to the five existing design books:
> - `docs/design/UI_DESIGN_BOOK.md` — visual language, tokens, grid, components.
> - `docs/design/PLUGIN_VIEW_DESIGN_BOOK.md` — the plugin editor surface and SDK seam.
> - `docs/design/SETTINGS_VIEW_DESIGN_BOOK.md` — settings model, scope, apply contract.
> - `docs/design/PROJECT_MANAGER_DESIGN_BOOK.md` — project lifecycle, autosave, recovery.
> - `docs/design/CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md` — the wiring between surfaces.
>
> And to the four sibling books born from the same 10-area functional audit:
> - `docs/design/AUDIO_ENGINE_WIRING_DESIGN_BOOK.md` — the audible core: every sound-making promise of the UI is honoured by the engine.
> - `docs/design/RECORDING_RELIABILITY_DESIGN_BOOK.md` — a two-hour session ends with all audio on disk.
> - `docs/design/FAILURE_SURFACING_DESIGN_BOOK.md` — no silent failures, no button does nothing.
> - `docs/design/INTERACTION_COMPLETENESS_DESIGN_BOOK.md` — every control does something real; every visual tells the truth.
>
> This book **extends — never replaces —** `PROJECT_MANAGER_DESIGN_BOOK.md`. That
> book defines the project lifecycle a user *sees*: the Hub, the Session Status
> Strip, checkpoints, journals, locks, sessions, archives (its §3–§6). This book
> defines what must be true *underneath* for those surfaces to be honest: the
> exact inventory of state that must survive a save→reopen cycle, the atomicity
> and isolation rules for every write, the recovery obligations of every open
> path, and the protocol for leaving the application. The audit found the
> machinery real but the *guarantee* absent: reopened projects are silent, whole
> categories of work are never written, the safety nets are off by default, and
> quitting the app discards everything without a word. Stories 329–335 close
> that gap.

---

## 0. How to use this book

1. **Read §1 first.** A frank inventory of what actually survives a save→reopen cycle today and
   what the write/exit paths can destroy. Every later section is judged against it.
2. **§2 is the foundation.** Nine non-negotiable principles; each exists because §1 shows what
   happens without it.
3. **§3 is the information model.** The **round-trip inventory** — one table saying, for every
   fact the model holds, whether it serializes and whether it restores — plus the durability
   artifact model layered on the Project Manager book's §3.
4. **§4 is the architecture catalogue.** Atomic-replace writer, checkpoint snapshot isolation, clip
   rehydrator, plugin-slot descriptor, recovery gate, exit coordinator, write/read parity harness.
5. **§5 is the behavioural contract.** The tables a reviewer checks a PR against: round-trip,
   write, open, exit, recovery decisions, settings truth.
6. **§6 is cross-cutting behaviour.** Threading, schema evolution, failure surfacing, test
   strategy.
7. **§7 is the integration layer.** How this book binds to the Project Manager book section by
   section, and to the other eight books.
8. **§8 is the migration path.** Seven stages, one per story 329–335, in dependency order, each
   independently shippable, with existing backlog stories woven in.
9. **§9 is the rejection list.** Persistence anti-patterns already demonstrated in this codebase;
   they must not come back.

ASCII diagrams are deliberately wide (≈120 columns); render in a monospace-capable viewer. All
`file.java:line` references are locators pinned to today's tree; content correctness is what
matters, drift is acceptable.

---

## 1. Critique of persistence shipping today

The single most important promise a DAW makes — *save it, reopen it, it is all still there* — is
broken at every layer: the read path restores metadata but not music, the write path omits whole
categories of work, the durability machinery is dark by default, and the exit path is a data-loss
event. This section is the evidence.

### 1.1 Reopening a project restores metadata, not music

The deserializer rebuilds every `AudioClip` from name, position, duration, and the persisted source
path only — `ProjectDeserializer.java:539` constructs the clip and never calls `setAudioData`; a
repo-wide search confirms `AudioClip.setAudioData` (`AudioClip.java:327`) is called by importers,
recording, and clip-edit actions, never by any load path. Both consumers of a clip's audio read the
in-memory array: the render loop resolves `clip.getAudioData()` (`RenderPipeline.java:1069`) and
**skips the clip entirely** when it is null or empty (`RenderPipeline.java:894-895`), and the
arrangement waveform draws from the same array (`ClipOverlayRenderer.java:152`). A reopened project
*plays silence and renders empty clips* — every clip, every time, even when the source WAV is
sitting right there on disk.

Worse, the deserializer already *knows* when sources are gone: it collects every missing path during
load (`ProjectDeserializer.java:532`, `:535`) into an accessor (`ProjectDeserializer.java:133`)
whose only callers are round-trip tests. The production open path
(`ProjectLifecycleController.java:844`) never consults it and shows an unconditional "Opened
project: …" `SUCCESS` notification (`ProjectLifecycleController.java:866`) for a project whose
audio may be wholly gone. Story 063 has described this gap for a long time; it remains
unimplemented.

### 1.2 Whole categories of work are never written — or written and never read

The serializer and deserializer have drifted into asymmetry. A repo-wide search of the persistence
package finds **zero** references to `getMidiClip`, `getTakeComping`, `getTakeGroups`, or
`getTempoMap`. Concretely:

- **MIDI notes and CC lanes are never saved.** Every track owns a `MidiClip` (`Track.java:91`) that
  the piano roll and MIDI recording write into, but the track serializer
  (`ProjectSerializer.java:268` onward) writes clips, soundfont assignment, automation, and the MIDI
  effect chain — never the `MidiClip`. The pair `serializeMidiClip`/`deserializeMidiClip` exists
  (`ProjectSerializer.java:1062`, `:1130`) with **only test callers**. All piano-roll work is lost
  on save, silently.
- **MIDI effect chains are written and never restored.** The serializer emits a full
  `midi-effect-chain` element per track (`ProjectSerializer.java:341-343`); the deserializer
  contains no handling for it at all. Write-only persistence: the bytes are on disk, the arpeggiator
  is gone.
- **Pre-roll / post-roll are written and never restored.** `buildTransport` writes the three
  pre/post-roll attributes (`ProjectSerializer.java:252-254`); no `pre-roll` token exists anywhere
  in the deserializer.
- **The tempo map cannot be expressed.** `Transport` is backed by a full `TempoMap`
  (`Transport.java:74`) that the timeline ruler already consults (`TimelineRulerModel.java:58`), but
  the format serializes exactly one beat-0 tempo and time signature (`ProjectSerializer.java:235`
  onward). Tempo or meter changes are unrepresentable end to end.
- **Take stacks and comping are never persisted.** Tracks own `TakeComping` and a take-group map
  (`Track.java:98-99`); loop recording populates them (`RecordingPipeline.java:294`) and the comp
  tool consumes them in production — yet the serializer emits no take-lane, take-group, or
  comp-region element of any kind (the only `take` token is the `takes-folded` fold-state attribute,
  `ProjectSerializer.java:292`).
- **Plugin-backed inserts are silently dropped, shifting the chain.**
  `InsertEffectFactory.createSlotFromPlugin` (`InsertEffectFactory.java:86`) infers a *built-in*
  type (`InsertEffectFactory.java:107`) — null for any external-JAR or CLAP plugin. The serializer
  guards the `effect-type` attribute *and all parameter children* behind a non-null type
  (`ProjectSerializer.java:510-512`), so a plugin slot is written as name/bypassed/expensive only.
  On load, `parseInsertSlot` returns for an empty `effect-type` and **explicitly returns for
  `CLAP_PLUGIN`** (`ProjectDeserializer.java:893-902`) — the slot is never recreated and every
  later slot shifts one position up. A mix using any third-party insert reopens with a different,
  shorter chain and no warning.
- **The installed-plugin registry is session-scoped.** `PluginRegistry` keeps entries in in-memory
  collections only (`PluginRegistry.java:25-27`, registration at `:37`); nothing persists them and
  nothing re-registers at startup — which turns the dropped-insert bug above from "recoverable by
  hand" into "unrecoverable without re-installing first".
- **Recorded audio's persisted paths point at OS temp.** The recording output root is a system temp
  directory (`TransportController.java:433`); the clip's source path is a temp segment path
  (`RecordingPipeline.java:308`) and that is what the serializer persists
  (`ProjectSerializer.java:377`). Long takes are worse: segments roll every 30 minutes / 500 MB
  (`RecordingSession.java:34-37`, `:395-397`) and the clip references **only the first segment**.
  Capture-to-disk is `RECORDING_RELIABILITY_DESIGN_BOOK.md` territory (story 323); this book owns
  the consequence — what `source-file` must mean for a reload to be possible.

### 1.3 The write path can corrupt the very file it exists to protect

`writeDawProjectFile` truncates and rewrites `project.daw` in place —
`Files.writeString(projectFile, xml)` (`ProjectManager.java:493`; the metadata-only variant at
`:527` is the same pattern). A crash or disk-full mid-write leaves a torn, unparsable project with
no intact prior version. The codebase already contains the correct pattern — the crash-recovery
path writes a `.recovering.tmp` sibling and atomically moves it into place
(`ProjectLifecycleController.java:1368` onward) — but the *normal* save path, the one that runs
hundreds of times per session, does not use it. Checkpoints share the bare-write pattern
(`CheckpointManager.java:279`); the blast radius there is one new timestamped file, but the
asymmetry is the point: atomicity exists only where a previous story happened to add it.

### 1.4 Autosave serializes the live model against concurrent edits, and fails silently

The checkpoint data supplier serializes the **live** project (`MainController.java:829-836`)
catching `IOException` only. `triggerCheckpoint` dispatches that supplier onto a virtual thread
(`CheckpointManager.java:309`) while the FX thread keeps editing the model — and
`DawProject.getTracks()` returns an unmodifiable view over the live mutable list
(`DawProject.java:270-271`), so a track added mid-walk throws `ConcurrentModificationException`.
`performCheckpoint` also catches only `IOException` (`CheckpointManager.java:288-290`), so the
runtime exception escapes without calling `notifyFailed` —
`ProjectOperationProgress.recordSaveFailed` never fires and the Session Status Strip (story 295)
keeps showing a healthy autosave. The scheduler survives (each tick re-dispatches), so the failure
mode is *silently lost checkpoints*, exactly when the user is editing most actively — which is
when a checkpoint matters most.

### 1.5 One Ctrl+S: four serializations, two checkpoints, all on the FX thread

The manual save path calls `saveDawProject` **and then** `saveProject` back-to-back
(`ProjectLifecycleController.java:358-359`). Each performs a full serialize-and-write of
`project.daw` *and* a synchronous `performCheckpoint` (`ProjectManager.java:317-332` and
`:290-307`), which documents that it "runs the actual file I/O on the calling thread"
(`CheckpointManager.java:256-262`) — here, the JavaFX application thread. Net effect per Ctrl+S:
four full serializations, two duplicate checkpoint files, and a UI freeze growing linearly with
session size. The Project Manager book §1.1 called out the monolithic save; the double-save doubles
it.

### 1.6 The crash-recovery machinery is complete — and dark

Story 298 built the write-ahead journal, `RecoveryScanner`, and `RecoveryDialog` end to end. None of
it can run on a stock install:

- `DEFAULT_USE_JOURNALED_PERSISTENCE = false` (`SettingsModel.java:78`), and the coordinator's
  `openFor` returns immediately unless the preference is true
  (`ProjectJournalCoordinator.java:185-191`; the supplier is solely the preference,
  `MainController.java:684`). No journal, no recovery — ever — unless the user finds a hidden
  toggle.
- Even flag-on, recovery is reachable from exactly one place — the Welcome Recover tile
  (`ProjectLifecycleController.java:1248`) — and a project only *classifies* as recoverable when
  its lock is more than 10 minutes stale (`ProjectHealthScanner.java:173-178`,
  `ProjectLockManager.java:65`). Relaunch within 10 minutes of a crash — the overwhelmingly common
  case — and the project presents as a plain "Continue".
- Every other open path funnels through `loadProjectFromPath`
  (`ProjectLifecycleController.java:844`), which never runs the recovery scan but *does* open the
  journal — whose rotation listener rotates on the **first successful checkpoint**
  (`ProjectJournalCoordinator.java:358-372`), permanently discarding the crashed session's
  un-replayed records within minutes of the plain reopen.

A safety system simultaneously built, disabled, unreachable, and self-erasing.

### 1.7 Quitting the application is a data-loss event

There is no exit protocol at all:

- The only primary-stage lifecycle hook is `setOnHidden` (`MainController.java:1007`) — it
  disposes view-models and timers *after* the window is gone and cannot veto anything. The only
  `setOnCloseRequest` in the app is on floating dock-panel stages (`MainController.java:3579`);
  `DawApplication`'s `WINDOW_HIDDEN` handler closes the event bus and dispatcher only
  (`DawApplication.java:98`).
- `ProjectManager.closeProject()` — the final save, checkpoint-scheduler stop, and **lock
  release** (`ProjectManager.java:339-359`) — has zero production callers; only tests invoke it.
  Every real exit leaves the lock file behind, which feeds the §1.6 staleness heuristic garbage on
  the next launch.
- `confirmDiscardUnsavedChanges` (`ProjectLifecycleController.java:813`) is invoked only from
  New/Open/Restore/Recover — never from any exit. The File menu
  (`MenuConstructionService.java:141-182`) contains no Exit item, no Close Project, and no
  project-level Save As.
- The stolen-lock contract is documented and abandoned: `LockStolenException`'s javadoc says the UI
  should prompt Save As (`LockStolenException.java:6-11`); the save path catches it as a generic
  `IOException` → "Save failed" toast (`ProjectLifecycleController.java:370-376`); `closeProject`
  swallows it assuming "the UI is expected to have already prompted Save As"
  (`ProjectManager.java:346-349`) — nothing does. The lock indicator even *tells the user* to "Use
  Save As to preserve your changes" (`LockStatusIndicator.java:110-111`) — an instruction to use a
  feature that does not exist. (That indicator also styles itself with an inline hex colour; a token
  violation for `UI_DESIGN_BOOK.md`, noted in passing.)

### 1.8 Checkpoint retention is amnesiac, and the Backups category prunes a ghost directory

- The checkpoint index is an in-memory list (`CheckpointManager.java:53`) that `start(...)` never
  seeds from the on-disk `checkpoints/` directory (`CheckpointManager.java:82-105`);
  `pruneOldCheckpoints` trims only that list (`:341-350`), so files from previous sessions are
  invisible to rotation and the directory grows without bound.
- `applyRetentionPolicy` — the only code that scans the real directory — is a guaranteed no-op
  because the policy field is never set (`:360-364`): `setRetentionPolicy` (`:243`) has **zero
  callers anywhere in the repo**.
- Meanwhile the entire Settings ▸ Backups category (six retention rows, `SettingsDialog.java:146`)
  applies its policy to `~/.daw/autosaves` (`BackupRetentionController.java:94-95`), a directory
  **nothing ever writes**; `applyPolicy` returns 0 when it does not exist (`:127-129`). Real
  autosaves go to the per-project `checkpoints/` directory (`CheckpointManager.java:271`); a menu
  comment still claims the policy applies "immediately to ~/.daw/autosaves"
  (`MenuConstructionService.java:199-203`) — true, and useless. Stories 191/308 built a retention
  engine and pointed it at a ghost.

### 1.9 Settings that lie about persistence-adjacent behaviour

- **Autosave interval ignored at startup.** The `CheckpointManager` is constructed with
  `AutoSaveConfig.DEFAULT` — 5 minutes / 50 checkpoints — hard-coded (`MainController.java:822`,
  `AutoSaveConfig.java:23-24`), while the persisted preference defaults to 120 seconds
  (`SettingsModel.java:70`). The only reconciliation point is the Settings Apply path
  (`LiveSettingsApplier.java:35-41`): until then, the strip countdown and the actual cadence both
  follow the wrong number.
- **Every Apply resets the open project's tempo.** `LiveSettingsApplier.apply` unconditionally
  writes the *default tempo* preference into the live transport (`LiveSettingsApplier.java:42`),
  pushing a beat-0 tempo change (`Transport.java:157`) with no undo entry — changing only the
  theme silently rewrites the session's tempo.
- **The master CPU degradation policy is write-only.** `setMasterCpuBudgetPolicy` persists it
  (`SettingsModel.java:492-493`); the enforcer is constructed without it
  (`MainController.java:2197`); the only reader is the dialog re-displaying its own stored value.
- **Invalid tempo input is silently discarded on Apply** — the parse-failure branch is a bare
  return with a comment admitting it (`SettingsDialog.java:2042`); validator-rejected values fall
  through the same hole with no row-level error state.
- **UI scale is doubly broken.** The applier adds a bare scale transform pivoted at the origin
  (`LiveSettingsApplier.java:30-33`) — scale > 1 renders content past the window edge, scale < 1
  shrinks into the corner — and no startup path reads the persisted value at all (`DawApplication`
  applies theme and density to the new scene but never scale), so the preference silently reverts to
  1.0 every session while the Settings row displays the stored value as if active.

### 1.10 What today's code gets right (keep)

- **The atomic-replace pattern exists** — the recovery writer
  (`ProjectLifecycleController.java:1368` onward) writes a `.tmp` sibling then moves with
  `ATOMIC_MOVE`, falling back to `REPLACE_EXISTING`. §4.1 generalises exactly this.
- **The journal / recovery machinery is genuinely complete** (story 298): writer, coordinator,
  scanner, dialog, replay, discard-then-load, and the re-present-Welcome flow
  (`ProjectLifecycleController.java:1248-1312`). This book turns them on and widens their reach; it
  does not rebuild them.
- **The Session Status Strip and `ProjectOperationProgress`** (story 295) are the right failure
  surface; the manual-save path already reports into them (`ProjectLifecycleController.java:367`,
  `:374`). The gaps are the paths that *bypass* them (§1.4).
- **The first-save-into-temp trap is fixed** — `trySaveProject` prompts for a real destination
  (`ProjectLifecycleController.java:339-351`, story 296). Recording's temp-dir output (§1.2) is the
  surviving instance.
- **Checkpoint I/O runs on virtual threads** (`CheckpointManager.java:301-315`), and checkpoints
  carry full project XML (story 190) so the Snapshots browser can deserialize them.
- **Lock management is solid**: heartbeat, staleness, conflict dialog with Open Read-Only / Take
  Over / Cancel (`MainController.java:844`).
- **Built-in insert effects round-trip well** — the reflective parameter-snapshot path
  (`ProjectSerializer.java:513-525`) is robust to parameter renumbering; §4.4's parity thinking
  builds on it.
- **Automation, loop/punch regions, fold state, and layout JSON already round-trip**, and **the
  deserializer already collects missing files** (§1.1). The format's skeleton is healthy; the gaps
  are omissions, not rot.

### 1.11 Summary of the gap

| Symptom (today) | Root cause | This book's fix |
|---|---|---|
| Reopened project is silent, clips empty | Audio never rehydrated on load (§1.1) | Clip rehydrator + missing-assets surface (§4.3, story 329) |
| Piano-roll / arpeggiator / pre-roll work vanishes | Never serialized, or write-only (§1.2) | Round-trip inventory + parity harness (§3.1, §4.4, story 330) |
| Third-party inserts dropped, chain shifts | Type-less slots discarded on load (§1.2) | Plugin-slot descriptor + placeholder (§4.5, story 334) |
| Takes/comps/tempo map lost | Format cannot express them (§1.2) | Schema additions (§4.6, story 334) |
| Crash mid-save corrupts project.daw | In-place truncating write (§1.3) | Shared atomic-replace writer (§4.1, story 331) |
| Autosave dies silently mid-session | Live-model serialization + narrow catch (§1.4) | Snapshot isolation + Throwable containment (§4.2, story 331) |
| Ctrl+S freezes UI, doubles work | Double save, sync checkpoint on FX (§1.5) | Single-save collapse, off-FX write (§5.2, story 331) |
| Crash recovery can never fire | Off by default, one entry point, 10-min blind window, self-erasing journal (§1.6) | Default-on + recovery gate on every open (§4.7, story 332) |
| Quit discards everything, leaks lock | No close handler, closeProject uncalled (§1.7) | The exit protocol + Save As (§5.4, story 333) |
| Checkpoints grow forever; Backups prunes a ghost | Index never seeded; policy never set; wrong directory (§1.8) | Retention unification (§4.8, stories 331 + 335) |
| Settings lie (interval, tempo, scale, CPU policy) | Read-at-Apply-only or read-never (§1.9) | Settings-truth contract (§5.6, story 335) |

---

## 2. Design principles

Nine non-negotiable rules. Each cites its rationale in §1 and its home in the companion books.

### 2.1 A fact that does not round-trip does not exist

If the model holds it and the user can edit it, it serializes and restores — or the surface that
edits it is removed. "Saved" means *the whole session*, not the subset the serializer happens to
know about; §1.2 is what partial round-trip looks like from the user's chair. The §3.1 inventory
is the exhaustive ledger; a PR adding editable model state without a row there is incomplete by
definition.

### 2.2 Every durable write is atomic, or it is a hazard

Every file this system writes goes through write-temp-sibling → fsync → atomic move. No
exceptions. §1.3 shows the asymmetry: the rarely-run recovery path is safe, the
hundred-times-a-session save path is not. Atomicity is a property of the *writer*, not of call-site
diligence — hence one shared helper (§4.1). (Project Manager book §5.2 declared this; this book
makes it universal.)

### 2.3 Serialization reads only quiescent state

A serializer walking a model another thread is mutating is a coin-flip (§1.4). Every off-FX
serialization consumes an immutable capture taken on the owning thread — copy-on-write, dirty-flag
incremental copy, or (interim) an FX-thread capture handed to the worker — per the Project Manager
book §5.3. The FX thread never performs the disk write; the worker never touches the live model;
one writer per file.

### 2.4 The read path is as honest as the write path

Opening a project is not "parse succeeded". The open pipeline accounts for every referenced asset
and every element it could not honour, and surfaces the result *once, visibly, with an action*. An
unconditional success toast over missing audio (§1.1) is the read-path equivalent of a silent save
failure. Degraded opens are *labelled* opens.

### 2.5 Durability is default-on; opt-out is for experts

A safety net that ships disabled protects no one (§1.6). Journal, recovery scan, checkpoint
retention — all active on a stock install, preferences as opt-outs. Story 298 deliberately shipped
dark for one release (its §7 Stage 4 exit criterion); that release has shipped. The flag flips.

### 2.6 Every open is a potential recovery

Crashes are routine (Project Manager book §2.3). *Every* open path — Continue, Hub, File▸Open,
archive restore, Welcome — runs the same recovery gate before the journal can rotate (§1.6's
self-erasure), and crash detection is a positive fact (the session seal, §3.2), never a timing
heuristic. Recovery must not depend on which door the user walked through.

### 2.7 Exit is a protocol, not an event

Leaving the application is an ordered sequence with a veto point — prompt, flush, seal, release,
dispose — not a side effect of a window disappearing (§1.7). Every step publishes its outcome
*before* the terminal state, so observers never see a half-closed world. The same protocol runs for
File▸Exit, the window close button, and workspace switch; input source is irrelevant past the
intent boundary (Control Synchronization book §2.8).

### 2.8 A setting is read by the thing it claims to control

Every settings row names an authority, and that authority reads it at startup *and* on apply — not
only when the Settings dialog happens to be the trigger (§1.9). Apply touches only edited facts
(Settings View book's apply contract); rejected input produces visible row-level feedback, never a
silent drop. A row whose authority no longer reads it is removed, not left as decoration.

### 2.9 Write/read parity is enforced by a harness, not by memory

§1.2's write-only elements happened because nothing *fails* when a serializer gains an element the
deserializer never learns. The §4.4 harness makes the asymmetry a test failure: every emittable
element must have a reader, every editable model fact a round-trip assertion. The persistence
instance of the repo's conformance-sentinel pattern.

---

## 3. Information model

### 3.1 The round-trip inventory

The core deliverable of this book: one table, one row per fact the model holds, with today's truth
and the owning stage. "Serialized" and "Restored" describe the current tree. This table *is* the
definition of done for stories 329, 330, and 334 — and the template the §4.4 harness asserts
against permanently.

```
  Fact (authoritative home)                     │ Serialized today │ Restored today │ Round-trip verdict      │ Owner
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  Track properties (name/type/colour/volume/    │ yes              │ yes            │ OK — keep               │ —
    pan/mute/solo/arm, groups, fold state)      │                  │                │                         │
  Audio clip metadata (name, start, duration,   │ yes              │ yes            │ OK — keep               │ —
    source-file reference)                      │                  │                │                         │
  Audio clip SAMPLE DATA (AudioClip.audioData)  │ by reference     │ NO — never     │ BROKEN: silent clips,   │ 329
                                                │ (source-file)    │ rehydrated     │ empty waveforms         │
  Missing-asset report (deserializer collects)  │ n/a              │ collected,     │ BROKEN: invisible       │ 329
                                                │                  │ never shown    │                         │
  MIDI notes + CC lanes (Track.midiClip)        │ NO               │ NO             │ BROKEN: all MIDI lost   │ 330
  MIDI effect chains (arpeggiator settings)     │ yes              │ NO             │ BROKEN: write-only      │ 330
  Pre-roll / post-roll configuration            │ yes              │ NO             │ BROKEN: write-only      │ 330
  Loop region + punch region                    │ yes              │ yes            │ OK — keep               │ —
  Tempo + time signature (beat 0)               │ yes              │ yes            │ OK — keep               │ —
  TEMPO MAP (tempo/meter change lists)          │ NO — format      │ NO             │ BROKEN: inexpressible   │ 334
                                                │ cannot express   │                │                         │
  Automation breakpoints                        │ yes              │ yes            │ OK — keep               │ —
  Built-in insert slots + parameters            │ yes (reflective  │ yes            │ OK — keep               │ —
                                                │ snapshot)        │                │                         │
  Plugin-backed inserts (external JAR / CLAP)   │ type-less stub   │ NO — dropped,  │ BROKEN: chain corrupts  │ 334
                                                │ (name only)      │ chain shifts   │                         │
  Take stacks + comp selections (TakeComping,   │ NO               │ NO             │ BROKEN: takes lost      │ 334
    takeGroups)                                 │                  │                │                         │
  Recorded-clip segment list (multi-segment     │ first segment    │ n/a            │ BROKEN: capture side    │ Book 2
    takes)                                      │ only, temp path  │                │ owned by story 323      │ (323)
  Installed third-party plugin registry         │ NO (in-memory    │ NO             │ BROKEN: app-scoped,     │ 334
    (app-level, not per-project)                │ only)            │                │ forgets on restart      │
  Mixer channel volume/pan/sends, VCA/return    │ yes              │ yes            │ OK — keep (control      │ —
    structure                                   │                  │                │ truth: Book 1, 322)     │
  Markers, metronome settings, soundfont        │ yes              │ yes            │ OK — keep               │ —
    assignments                                 │                  │                │                         │
  Dock/window layout JSON (story 282)           │ yes              │ yes            │ OK — keep               │ —
```

Rules the table encodes: sample data is persisted *by reference* and restored *by rehydration* — a
valid strategy only when the read side completes it (§4.3); a "write-only" verdict is worse than
"not serialized" (it wastes bytes, implies safety, rots silently — §2.9's harness makes it
unreachable); cross-book rows are labelled, not absorbed — the recorded-segment row is produced by
`RECORDING_RELIABILITY_DESIGN_BOOK.md` (story 323) and consumed by this book's 329.

### 3.2 The durability artifact model

Layered directly on the Project Manager book §3.2's project layout and §5.1's three persistence
layers; this book adds precise *trust semantics* per artifact and one new record: the **session
seal**.

```
  Artifact              │ Written when                   │ Written how (§2.2)      │ Protects against              │ Trusted by
  ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  project.daw (HEAD)    │ manual save; exit protocol     │ atomic replace          │ losing the last deliberate    │ every open
                        │                                │                         │ save                          │ path
  checkpoints/*.daw     │ timed autosave; save           │ atomic replace (new     │ losing more than one          │ Snapshots
                        │                                │ timestamped file)       │ interval of work              │ browser, recovery
  journal/segment-*.bin │ every undoable mutation        │ append + fsync          │ losing anything between       │ recovery scan
                        │ (default ON, §2.5)             │                         │ checkpoints                   │ (§4.7)
  snapshots/*.daw       │ user-named save (story 299)    │ atomic replace; never   │ losing a milestone            │ user, Hub
                        │                                │ auto-deleted            │                               │
  .project.lock         │ heartbeat every 2 s            │ mtime refresh           │ concurrent sessions           │ conflict dialog,
                        │                                │                         │                               │ health scan
  SESSION SEAL          │ final record of the exit       │ journal append + fsync, │ misclassifying a crash as a   │ recovery gate
  (journal close record)│ protocol (§5.4) and of         │ then segment rotation   │ clean close — or vice versa   │ on every open
                        │ project switch                 │                         │                               │
```

**Why the seal is a journal record and not a marker file.** A separate `.clean-close` file is a
second source of truth that can drift from the journal (deleted by the user, orphaned by a crash
between the two writes). The journal already *is* the authoritative record of un-checkpointed work;
a final sealed record makes "clean close" a positive, fsync-ordered fact in the stream the recovery
scan reads anyway. Absence of a seal (or records after the last checkpoint marker) ⇒ recovery
candidate — regardless of how fresh the leftover lock looks. Lock staleness is demoted to an
informational fact for the conflict dialog, never the crash detector (§1.6). With the journal opted
off (§2.5), the gate degrades to the lock heuristic — explicitly, as the documented lesser mode.

### 3.3 Path semantics

Every asset reference inside `project.daw` is **project-relative** (to the project directory,
forward-slash normalised). Absolute paths are accepted on read for backward compatibility and
re-written relative on the next save when the asset lives under the project directory. Rationale:
§1.2's temp-path persistence shows what absolute paths do — they bind a project to one machine's
transient filesystem state, and the Project Manager book §3.2's "everything else is derivable" only
holds if references travel with the directory. Assets *outside* the project (shared sample
libraries) remain absolute; they are exactly the class the missing-assets surface (§4.3) and the
archive consolidation flow (story 189) exist to manage.

---

## 4. Architecture catalogue

Seven components. Each names its home, its design decision, and the rejected alternative with
reasons.

### 4.1 The atomic-replace writer

One helper in `daw-core` persistence, used by every durable write (§2.2): write to a same-directory
`.tmp` sibling → fsync the stream → atomic move over the destination → `REPLACE_EXISTING`
fallback where the filesystem denies `ATOMIC_MOVE`. It is the recovery writer's existing logic
(`ProjectLifecycleController.java:1368` onward) *moved down* into the core so `ProjectManager`,
`CheckpointManager`, the story-299 snapshot store, and the recovery path all share one
implementation.

- *Why one helper rather than per-writer discipline:* §1.3 proves per-site discipline decays —
  the pattern existed in the tree and the main save path still didn't use it. One choke point makes
  atomicity a reviewable property.
- *Rejected — journal-first-only (skip head atomicity because the journal can replay):* replay
  depends on the journal being enabled and intact; the head must be self-reliantly safe. Defence in
  depth, not either/or.
- *Rejected — leaving checkpoints on bare writes ("a torn one loses only one file"):* the
  Snapshots browser (story 190) deserializes checkpoints as full projects; a torn one turns a
  restore attempt into a parse error at the worst possible moment.

### 4.2 Checkpoint snapshot isolation

The checkpoint supplier stops reading the live model (§2.3). Design:

1. **Capture on the FX thread** — an immutable representation of project state, produced where
   mutation is single-threaded. Interim: the serializer walks *on the FX thread* only long enough to
   produce the document/string capture, handed to the virtual-thread writer for disk I/O. Target:
   the Project Manager book §5.3's copy-on-write / dirty-flag incremental snapshot, making
   FX-thread cost O(changed) instead of O(project).
2. **Contain `Throwable`, not `IOException`** — any capture or write failure reaches
   `notifyFailed` → `ProjectOperationProgress.recordSaveFailed` → the strip's Saved cell warning
   state (Project Manager book §4.5). A failed autosave the user can see is a nuisance; one they
   cannot see is §1.4.
3. **Manual save collapses to one pass** — one capture, one atomic head write, one checkpoint, off
   the FX thread with the strip's progress affordance for slow saves. The
   `saveDawProject`-then-`saveProject` double call (§1.5) is retired; one becomes the single
   canonical entry.

- *Rejected — synchronizing the model (locks around track lists):* puts contention on the FX
  thread and the audio-adjacent model for a background reader's benefit; captures are cheaper than
  locks here.
- *Rejected — serializing on the FX thread permanently (no worker):* §1.5's freeze, growing with
  session size; violates the Project Manager book §2.1.

### 4.3 The clip rehydrator and the missing-assets surface

A load-side service that turns persisted references back into playable state (§2.4), owned by story
329:

- After deserialization, every `AudioClip` with a source reference and no sample data is queued for
  rehydration on background virtual threads — decode, populate the clip's audio data, publish
  per-clip completion to the FX thread through the established dispatcher seam so waveforms appear
  as they land. Playback readiness is a per-clip fact, not a whole-project gate: a 40-track project
  must not block its window behind 40 decodes. (Lazy rehydration-on-first-read was rejected as the
  *primary* strategy: the render pipeline's clip skip is silent, so a lazy miss during playback
  reproduces §1.1's symptom for the first bars. Eager-but-async keeps the failure mode visible and
  bounded.)
- The deserializer's already-collected missing-file list (§1.1) feeds a **missing-assets report**:
  one warning surface per open, count + paths, with a relink affordance (locate file / locate folder
  / skip) mirroring the archive dialog's per-asset pattern (Project Manager book §4.7, story 299).
  The unconditional "Opened project" toast becomes outcome-qualified.
- Relinked paths are re-persisted project-relative (§3.3) on next save.

### 4.4 The write/read parity harness

The §2.9 enforcement, owned by story 330 and extended by every later schema stage:

- A **schema parity test** derives the element/attribute names the serializer can emit and asserts
  the deserializer handles each (and vice versa for required elements), failing with the offending
  name. §1.2's `midi-effect-chain` and `pre-roll-*` would have been build failures the day they
  were written.
- A **model round-trip test family**: build a project exercising every §3.1 row, save, reopen,
  compare fact by fact. Each new serialized fact adds its assertion in the same PR — the
  inventory's "Owner" column is the reviewer's checklist.
- Both live in `daw-core` (JavaFX-free), following the repo's conformance-sentinel tradition: the
  test names the contract, the failure message names the drifted element.

### 4.5 The plugin-slot descriptor and the unresolved placeholder

Story 334's fix for §1.2's dropped inserts:

- A plugin-backed `InsertSlot` serializes a **descriptor** — plugin identity (stable id +
  version), source kind (external JAR / CLAP), display name, bypass/expensive flags, chain position,
  and the plugin's opaque state (parameter snapshot where the reflective path applies, otherwise the
  plugin's own state blob).
- On load, the descriptor resolves through the plugin registry. Resolution failure produces an
  **unresolved placeholder slot** in the same chain position: audibly bypassed, visually distinct in
  the rack, carrying the descriptor so a later install or relink restores it losslessly. The chain
  *never* shifts and the slot is *never* silently deleted — the mix topology is data, not a cache.
- The installed-plugin registry (entries + manifest provenance from the story-303 install flow)
  persists in the app configuration directory and re-registers at startup. Per-project descriptors +
  app-level registry together make third-party chains survive both project reopen and restart.
- *Rejected — jar path as identity:* paths break on machine moves (§3.3); identity is the
  manifest id the install flow already validates.
- *Rejected — refusing to load a project with unresolvable plugins:* punishes collaboration and
  machine migration; the placeholder preserves everything while degrading audibly and visibly
  (§2.4).

### 4.6 Schema growth: tempo map, MIDI, takes

The format additions of stories 330 and 334, under the migration registry (story 188) so old files
open unchanged:

- **MIDI**: each track's `MidiClip` embeds as a child element — notes and CC lanes with
  breakpoints — promoting the already-written-and-tested `serializeMidiClip` /
  `deserializeMidiClip` pair (§1.2) from test-only utility to production path.
- **Transport**: pre/post-roll restore joins the existing loop/punch parsing; the tempo map
  serializes its full tempo-change and meter-change lists (position, value, transition type), the
  single beat-0 attributes retained as the backward-compatible degenerate case.
- **Takes**: per-track take lanes, take groups, and comp selections serialize alongside clips,
  referencing takes by id so the comping surface reopens exactly as left. Segment-file durability
  for the referenced audio is story 323's output (Book 2); 334's schema stores the ordered file list
  the capture side finalises.

### 4.7 The recovery gate on the open pipeline

Story 332's component: one gate, executed by *every* open path (§2.6), sequenced **before** the
journal coordinator's open/rotation hooks (fixing §1.6's self-erasure):

```
   any open request (Welcome ▸ Continue │ Hub ▸ Open │ File ▸ Open │ archive restore │ Recover tile)
        │
        ▼
   ┌───────────────────────────────────────────────────────────────────────────────────────────────┐
   │ RECOVERY GATE (virtual thread; FX never scans)                                                │
   │   journal present for this project?                                                           │
   │     no  ──────────────────────────────────────────────► plain open                            │
   │     yes: sealed with a session seal, nothing after last checkpoint marker?                    │
   │     yes ──────────────────────────────────────────────► plain open (rotate stale segments)    │
   │     no  ──► RecoveryDialog (§ PM book 4.6.1): Replay all │ Checkpoint only │ Inspect │ Cancel │
   └───────────────────────────────────────────────────────────────────────────────────────────────┘
        │ only after the decision is applied (replay committed via §4.1, or discard chosen)
        ▼
   journal opens for the NEW session; rotation listener arms; deserialize → rehydrate (§4.3) → UI cascade
```

- `useJournaledPersistence` defaults **ON** (§2.5); the preference remains an opt-out. With it off,
  the gate falls back to the lock-staleness heuristic and says so in its log.
- The 10-minute staleness window and the Welcome-only entry point are retired as *detection*; the
  Welcome Recover tile remains an explicit entry to the same gate.
- Journal rotation is forbidden while un-replayed records from a prior session exist — rotation
  keys off the recovery decision, never off the first checkpoint of the new session.

### 4.8 The exit coordinator and retention unification

- **Exit coordinator** (story 333): owns the §5.4 ordered protocol, installed as the primary
  stage's close-request handler, the `File ▸ Exit` action, and the application-stop fallback. It
  is the *only* caller of the prompt + `closeProject` sequence; workspace switch and project close
  reuse it. `setOnHidden` remains dispose-only — disposal happens *after* the protocol, never
  instead of it.
- **Retention unification** (stories 331 + 335): the checkpoint index seeds from the on-disk
  `checkpoints/` directory at start so pruning counts files across sessions (§1.8); the Settings
  ▸ Backups `BackupRetentionPolicy` is delivered to `CheckpointManager.setRetentionPolicy` for the
  *real* per-project checkpoints directory. The `~/.daw/autosaves` target either gains its writer
  (only if a concrete design claims it) or — the default decision — the retention surface is
  re-pointed and re-labelled to govern what actually exists. Snapshots never rotate (Project Manager
  book §6.4).

---

## 5. The behavioural contract

### 5.1 The round-trip contract

§3.1 is the inventory; this is the acceptance rule. For every row marked with an owner story: build
state → save → close → reopen → assert. The reviewer's question for any persistence PR is
"which rows does this change, and where is each row's round-trip assertion?"

| Guarantee | Assertion after save → reopen |
|---|---|
| Audio | Every clip plays and renders its waveform identically; missing sources produce the §4.3 report, never silence-with-success |
| MIDI | Piano-roll notes, CC lanes, per-track MIDI effect chains identical |
| Transport | Tempo map (all changes), time signatures, loop, punch, pre/post-roll identical |
| Mixer | Insert chains identical in content **and order**, including plugin-backed slots (resolved or placeholder) |
| Takes | Take lanes, groups, comp selections identical; comp tool resumes where it left off |
| App level | Installed plugin registry survives restart; project opens on another machine when the folder travels (§3.3) |

### 5.2 The write contract

Every durable write in the system, normalised:

| Write | Trigger | Thread | Isolation (§2.3) | Atomicity (§2.2) | Failure surface (§6.2) |
|---|---|---|---|---|---|
| Head (`project.daw`) | manual save; exit protocol | capture FX → write worker | immutable capture | atomic replace | strip Saved cell error state + notification |
| Checkpoint | schedule; post-save | capture FX → virtual thread | immutable capture | atomic replace (new file) | `recordSaveFailed` → strip; `Throwable` contained |
| Journal append | every undoable mutation | dedicated writer thread | record is immutable by construction | append + fsync; back-pressure per PM book §6.3 | strip journal cell warning |
| Snapshot (named) | user action (story 299) | virtual thread | immutable capture | atomic replace; never auto-deleted | dialog + strip |
| Session seal | exit protocol; project switch | journal writer | n/a | fsync before window close proceeds | exit aborts visibly if seal cannot be written |
| Registry / settings | install flow; apply | virtual thread / FX | n/a | atomic replace | notification |

Invariants: one writer per file (PM book §5.3); the FX thread never performs disk I/O; a single
manual save produces exactly **one** head write and at most **one** checkpoint (§1.5 retired);
every failure row lands in a surface the user can see.

### 5.3 The open contract (ordered)

Skipping a step is allowed only where marked; re-ordering is not.

```
  1. LOCK        acquire / negotiate via the conflict dialog (existing; keep)
  2. RECOVER     the §4.7 recovery gate — decision applied before anything can rotate the journal
  3. PARSE       deserialize head (or recovered head) — migrations via the story-188 registry
  4. REHYDRATE   queue clip rehydration (§4.3), async, per-clip completion to FX
  5. ACCOUNT     missing-assets report surfaced once (skippable only when empty)
  6. JOURNAL     open the new session's journal; rotation listener arms (never before step 2's decision)
  7. CASCADE     the Control Synchronization book §5.7 full-load cascade rebuilds VMs and views
  8. ANNOUNCE    qualified open outcome: success, or success-with-N-missing-assets, or recovered-with-replay
```

Step 2 before step 6 is the load-bearing order — it is precisely the inversion of today's
`loadProjectFromPath`, whose journal-open hook lets the first checkpoint destroy the evidence
(§1.6).

### 5.4 The exit protocol (ordered)

One protocol for window [X], `File ▸ Exit`, project close, and workspace switch. The close request
is **consumed** until the protocol decides.

```
  1. INTERCEPT   close-request consumed; protocol begins (never setOnHidden — that cannot veto)
  2. GUARD       dirty check → prompt: Save and close │ Discard and close │ Cancel   (veto point)
                   ▸ maturity rule (see below): with journal ON and healthy, the prompt yields to
                     autosave-and-close + session seal — PM book §2.5 reversibility
  3. FLUSH       chosen save runs via §5.2 (atomic, off FX, progress in the strip if > 500 ms)
                   ▸ LockStolenException here → blocking Save-As flow (pre-filled sibling path);
                     never a transient toast (§1.7)
  4. SEAL        journal writes and fsyncs the session seal; segment rotates
  5. RELEASE     closeProject(): checkpoint scheduler stops, lock releases  — the §1.7 leak ends
  6. DISPOSE     VMs, timers, buses — the existing setOnHidden work, now guaranteed to run last
  7. HIDE        the stage actually closes; application exits
```

**Reconciling the prompt with the Project Manager book.** That book's §2.5/§8 reject the "Save
changes?" modal in favour of autosave + an undoable close — an end-state *conditional on
trustworthy capture* (default-on journal, observable checkpoints). Today neither holds and the
shipping behaviour is silent total loss, so the prompt ships first (story 333) as the strictly
better interim. Once story 332's journal is live and healthy for the session, step 2 honours the
PM-book behaviour — seal-and-close without interrogation — with the prompt retained only for
degraded cases (journal opted off, save failure, stolen lock). The prompt is scaffolding with a
defined demolition condition; this paragraph is the record of that intent.

### 5.5 The recovery decision table

| Journal state at open | Seal present | User choice offered | Outcome |
|---|---|---|---|
| absent (opted off / new project) | n/a | none | plain open; gate logs degraded mode |
| present, sealed, nothing after last checkpoint | yes | none | plain open; stale segments rotate |
| present, records after last checkpoint | no | Replay all / Checkpoint only / Inspect / Cancel (PM book §4.6.1) | replay commits via §4.1 then normal open; discard deletes journal then opens; Inspect/Cancel re-present Welcome (existing flow, keep) |
| present, unsealed, lock held **fresh** by another live session | no | conflict dialog first (lock wins) | recovery gate re-runs after lock resolution |

### 5.6 The settings-truth table (story 335)

| Setting | Authority (the enforcer) | Read at startup | Read on apply | Additional contract |
|---|---|---|---|---|
| Autosave interval | `CheckpointManager` config | **yes** (constructed from the persisted value, not `AutoSaveConfig.DEFAULT`) | yes (existing reconfigure) | strip countdown always matches actual cadence |
| Backups retention (six rows) | checkpoint retention via `setRetentionPolicy` (§4.8) | yes | yes | disk-usage visual reads the directory the policy governs |
| Default tempo | new-project template only | n/a | **only when the row was edited**, and routed through an undoable command | Apply of unrelated rows never touches the open project's transport |
| Tempo input validation | settings row validator | n/a | rejected value → visible row-level error; text retained | no silent drops (§1.9) |
| Master CPU degradation policy | the budget enforcer consumes it — or the row is deleted | yes | yes | a persisted-but-never-read policy is a §2.8 violation either way |
| UI scale | a layout-aware scaling mechanism (root font-size / rem-token scaling — not a bare origin-pivot transform) | **yes** | yes | every advertised scale renders an unclipped, fully laid-out window |
| Journaled persistence | journal coordinator | yes (default ON, §2.5) | yes | opt-out only |

---

## 6. Cross-cutting behaviour

### 6.1 Threading

Rules inherit from the Project Manager book §2.1/§5.3 and the Control Synchronization book
§2.6/§4.5:

- The FX thread *captures*; workers *serialize and write* (§4.2). The FX thread never blocks on
  disk — including manual save and the exit protocol's flush, which shows progress rather than
  freezing.
- Rehydration (§4.3) fans out on virtual threads; per-clip completion crosses to FX through the
  single dispatcher seam, coalesced. No persistence code calls the platform's run-later directly.
- The journal writer remains the single dedicated writer with non-blocking enqueue and the PM book
  §6.3 back-pressure ladder.
- Nothing in `daw-core` persistence takes a JavaFX dependency; outcome surfaces live in `daw-app`
  and subscribe (Control Synchronization book §4.2).

### 6.2 Failure surfacing (binds FAILURE_SURFACING_DESIGN_BOOK.md)

Every failure row in §5.2 terminates in a visible surface: the strip cells for
save/checkpoint/journal state, the notification bar for action-required events, the dialog layer for
veto points. This book defines *what* must surface; `FAILURE_SURFACING_DESIGN_BOOK.md` owns *how*
— the global exception handlers that make §1.4-style silent escapes structurally impossible
(story 336), production notification injection (339), and the dialog-dismissibility conformance
keeping every prompt here closable (313/339). Persistence PRs never invent bespoke error channels;
they publish into those.

### 6.3 Schema evolution

Every format addition in §4.6 lands as a schema-version bump through the story-188 migration
registry with a pre-migration backup (existing, keep). Readers tolerate absent new elements
(defaults); the §4.4 parity harness pins the *current* schema only — migrations own the past.
Forward compatibility (new files in old builds) is out of scope; the registry's version check
refusing with a clear message is the accepted behaviour.

### 6.4 Test strategy

- **Parity harness + round-trip family** (§4.4) — the permanent regression wall for §3.1.
- **Crash-shaped tests**: kill the write between temp-write and move (atomicity); inject a mutation
  during capture (isolation); open with an unsealed journal on each open path (gate coverage); steal
  the lock before the exit flush (Save-As flow).
- **Wiring tests over unit tests for §5.6**: rows get startup-wiring tests in the established
  `MainControllerAudioSettingsWiringTest` style — §1.9's bugs were all wiring bugs with green
  unit suites.
- FX-dependent assertions follow the repo's headless-test conventions; persistence logic stays
  testable JavaFX-free in `daw-core`.

---

## 7. Integration with the other books

### 7.1 With the Project Manager book (the book this one extends)

| PM book section | What it defines | What this book adds |
|---|---|---|
| §3.2 project layout | directories: audio/, checkpoints/, journal/, snapshots/ | path semantics inside the format (§3.3); recordings land in audio/ via Book 2's 323 |
| §5.1 three persistence layers | journal → checkpoint/snapshot → head | trust semantics per artifact + the session seal (§3.2) |
| §5.2 "writes are atomic" | the principle | the shared writer, universally applied, with a story (§4.1, 331) |
| §5.3 one writer per file; snapshot scaling | threading + CoW strategy | checkpoint snapshot isolation as shipped behaviour (§4.2, 331) |
| §2.3 crashes are routine | the stance | the recovery gate on *every* open path + seal-based detection (§4.7, 332) |
| §4.6.1 recovery dialog | the surface (built, story 298) | reachability and default-on activation (332) |
| §2.5 reversibility before confirmation | the end-state exit UX | the maturity rule reconciling the interim prompt with it (§5.4, 333) |
| §4.4/§4.5 status strip + HUD | the visibility surface (built, story 295) | the failure paths that must feed it and currently bypass it (§1.4, §5.2) |
| §6.4 rotation policy | retention rules | retention pointed at real directories, seeded across sessions (§4.8, 331/335) |
| §8 rejection list | lifecycle anti-patterns | §9 extends it with the round-trip/durability anti-patterns |

### 7.2 With the four sibling audit books

- **AUDIO_ENGINE_WIRING_DESIGN_BOOK.md** — its 314 wires the engine to the live project; this
  book's 329 guarantees the project the engine receives after a reopen actually *contains audio*.
  Complementary, ordered either way.
- **RECORDING_RELIABILITY_DESIGN_BOOK.md** — its 323 makes captured audio exist durably under the
  project; 329 reloads it, 334 persists the take/comp structure over it. Capture-to-disk is Book 2;
  reopen-from-disk is this book.
- **FAILURE_SURFACING_DESIGN_BOOK.md** — owns the surfacing machinery §6.2 publishes into; its
  336 catches any persistence escape this book's containment misses.
- **INTERACTION_COMPLETENESS_DESIGN_BOOK.md** — its menu-truth story (345) polices that every menu
  item lands, including the items 333 adds; waveform visual truth (342) consumes the rehydrated data
  329 provides.

### 7.3 With the remaining existing books

- **CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md** — open outcomes ride the §5.7 full-load cascade;
  `ProjectVM.dirty` is the §5.4 guard's input; cross-thread completion goes through its one
  marshalling seam.
- **SETTINGS_VIEW_DESIGN_BOOK.md** — §5.6 instantiates its apply contract: apply-only-edited,
  visible rejection, restart-class honesty.
- **PLUGIN_VIEW_DESIGN_BOOK.md** — the §4.5 descriptor persists what its editor surface edits;
  placeholder slots render in its rack idiom.
- **UI_DESIGN_BOOK.md** — every surface named here uses its tokens; §1.7's inline-hex indicator
  is corrected in passing when touched.

---

## 8. Migration path

Seven stages, one story each, in dependency order. Each is independently shippable; each states
scope, proof-it-landed, and what it unblocks. Existing backlog stories are woven in where a stage
depends on or completes them.

### Stage 1 — Audio Reloads on Project Open (story 329)

**Scope.** The §4.3 clip rehydrator: opening a project reads each clip's persisted source back into
playable audio data on background virtual threads; waveforms render as data lands; the
deserializer's missing-file list surfaces as a report with relink actions; the open notification
becomes outcome-qualified; serialized paths normalise project-relative (§3.3) on next save.
Completes the audio-reload and relink halves of **story 063** (existing, unimplemented —
cross-reference it; its remaining serialization scope is Stages 2/6). The archive consolidation flow
(**story 189**, landed) is linked from the relink surface as the recovery-of-last-resort, not
duplicated.

**Proof.** Round-trip test: import audio → save → close → reopen → the clip plays and
renders identically (first §5.1 row green). Delete a source file → reopen → report appears with
correct count; relink restores playback; the toast is qualified. No FX-thread stall on a many-clip
open.

**Unblocks.** The loudest audit blocker dies; Stage 6's take persistence and Book 1's engine wiring
(314) stop being undermined by silent reopens; Book 5's waveform-truth work (342) gets real data.

### Stage 2 — MIDI and Transport Settings Round-Trip (story 330)

**Scope.** Track `MidiClip`s (notes, velocities, channels, CC lanes) embed in the project file via
the existing-but-test-only serialize/deserialize pair; `midi-effect-chain` gains its reader;
pre/post-roll restores. The §4.4 write/read parity harness lands here and retroactively pins the
whole current schema. Schema additions ride the **story 188** (landed) migration registry with
version bump + backup.

**Proof.** Parity harness green (and demonstrably red when an element loses its reader); piano-roll
edits + arpeggiator settings + pre-roll config survive save→reopen. §3.1's MIDI and transport
rows flip to OK.

**Unblocks.** MIDI work is finally *savable* — a precondition for every MIDI feature in the
backlog (and Book 5's MIDI-editing parity, 346); the harness becomes the permanent wall Stage 6
builds against.

### Stage 3 — Atomic Saves and Autosave Snapshot Isolation (story 331)

**Scope.** The §4.1 atomic-replace writer in `daw-core`, adopted by head, checkpoint, and metadata
writes; §4.2 snapshot isolation for the checkpoint supplier with `Throwable` containment reaching
`recordSaveFailed`; manual save collapses to one capture → one atomic write → one checkpoint,
off the FX thread; the checkpoint index seeds from disk at start so pruning works across sessions.
This is the reliability core **story 019** (existing) always asked for. The **story 299** SPEC's
snapshot store specifies exactly this writer — landing the shared helper here means 299 implements
against it rather than duplicating; **story 190**'s (landed) snapshot browser gains torn-checkpoint
immunity immediately.

**Proof.** Kill-between-write-and-move leaves the prior head intact; a
concurrent-edit-during-checkpoint test produces a *visible* failed checkpoint (strip) rather than a
silent CME; one Ctrl+S → exactly one head write + one checkpoint, no FX block beyond capture; the
on-disk checkpoint cap holds across two app runs.

**Unblocks.** Stage 4 (a journal is only as trustworthy as the checkpoints it replays onto), Stage 5
(the exit save is the collapsed single save), Stage 7 (retention needs the seeded index).

### Stage 4 — Journaled Persistence On by Default, Recovery on Every Open Path (story 332)

**Scope.** `useJournaledPersistence` defaults ON (opt-out preserved); the §4.7 recovery gate runs
on every open path before the journal can open or rotate; the session seal (§3.2) replaces the
10-minute lock-staleness heuristic as crash detection; rotation never discards un-replayed records.
Executes the deliberately deferred default-promotion of **story 298** (landed dark —
cross-reference its §7 Stage-4 exit criteria); dialog, scanner, and replay flows are 298's, reused
untouched.

**Proof.** Crash-shaped test per open path (Continue / Hub / File▸Open / archive restore):
unsealed journal → RecoveryDialog *before* any rotation; relaunch 30 seconds after a kill offers
recovery (blind window gone); clean close opens plainly; a stock install writes journal records with
no settings visit.

**Unblocks.** Crash recovery exists for real users, not just the one Welcome-screen path with lucky
timing; Stage 5's seal has its reader.

### Stage 5 — The App-Exit Protocol: Close Guard, Save As, Lock Lifecycle (story 333)

**Scope.** The §5.4 protocol: primary-stage close-request handler + `File ▸ Exit` + `File ▸
Close Project` route through the guard prompt, the single save, the session seal, and
`closeProject()` (checkpoint stop + lock release — its first production caller). Project-level
`File ▸ Save As` lands, and the stolen-lock path drives the blocking Save-As flow the
`LockStolenException` contract has documented all along. The §5.4 maturity rule is recorded in the
story: prompt now, PM-book §2.5 autosave-and-close once Stage 4 is proven in the field.

**Proof.** Close via [X] with unsaved changes → prompt; Cancel aborts; Save produces one atomic
head write; the lock file is gone after exit; the journal is sealed (next launch's gate reports
clean). Steal the lock, then exit → Save-As flow preserves the work. The three new menu items
exist and land (feeds Book 5's 345 conformance).

**Unblocks.** Quitting stops being a data-loss event; the health scanner stops seeing phantom stale
locks from every normal exit, sharpening Stage 4's detection; workspace switching (PM book §4.4)
gets its safe close primitive.

### Stage 6 — Full-State Persistence: Plugin Inserts, Take Stacks, Tempo Map (story 334)

**Scope.** The §4.5 plugin-slot descriptor + unresolved placeholder (chain order inviolate);
app-level installed-plugin registry persistence; §4.6 take lanes / groups / comp selections; the
tempo-map schema. All additions extend the Stage-2 parity harness in the same PRs. Registry
persistence closes the flagged gap from **story 303** (landed with a truthful no-persistence notice
— the notice comes out when the gap closes). Take-stack audio durability depends on Book 2's
**story 323** (recordings on disk, project-relative, all segments) and its capture-workflow sibling
**328**; migration rides **188**.

**Proof.** A chain of built-in + external-JAR + CLAP inserts reopens identical in content and order;
uninstall one plugin → reopen → placeholder holds its position, reinstall restores it.
Loop-record a take stack (Book 2 landed), comp, save, reopen → comping resumes. Mid-song
tempo/meter changes round-trip and the ruler agrees. App restart → installed registry intact.

**Unblocks.** The last §3.1 BROKEN rows flip; the mix topology becomes trustworthy data Book 1's
mixer-truth (322) and master-chain (321) work can bind to without fear of load-shifted chains.

### Stage 7 — Settings Truth: Every Setting Read by the Thing It Claims to Control (story 335)

**Scope.** The §5.6 table, row by row: checkpoint manager constructed from the persisted interval
at startup; Backups retention delivered to the real checkpoints directory through
`setRetentionPolicy` (§4.8's re-pointing decision) with the disk-usage visual reading the same
directory; default-tempo applied only when edited, undoably; invalid tempo gets row-level rejection
feedback; master CPU degradation policy consumed by the enforcer or its row removed (decision
recorded in the story); UI scale applies at startup via a layout-aware mechanism that never clips
the window. Re-points the retention machinery of **story 191/308** (landed) at the directory Stage 3
made countable; the Settings View book's apply contract governs the only-when-edited rule.

**Proof.** Startup-wiring tests per row (§6.4): fresh launch shows the persisted interval in the
strip countdown; changing only the theme leaves the open project's tempo untouched; the Backups rows
visibly change what is kept on disk across two sessions; a 2.0 UI scale renders a fully usable
window.

**Unblocks.** The settings surface stops lying about persistence-adjacent behaviour — the trust
dividend that makes users believe the rest of the guarantee. Closes out the book.

---

## 9. Rejection list (do not bring these back)

1. **In-place truncating writes of any durable file.** `Files.writeString` straight over
   `project.daw` (§1.3) is how a crash eats a project. Every durable write goes through the §4.1
   writer.
2. **Serializing the live model from another thread.** The §1.4 CME roulette. Capture on the owning
   thread; write from the worker (§2.3).
3. **Write-only persistence.** An element the serializer emits and the deserializer ignores (§1.2)
   is a lie on disk. The parity harness (§4.4) makes it a build failure; never waive it.
4. **Silently dropping unresolvable state on load.** A slot or element the reader cannot honour
   becomes a visible placeholder or reported degradation (§2.4, §4.5) — never a quiet deletion
   that shifts its neighbours.
5. **Absolute or OS-temp asset paths inside the project file.** §1.2's evaporating takes.
   Project-relative always (§3.3); temp directories are for data whose loss is acceptable, which
   describes nothing in a project.
6. **Timing heuristics as crash detection.** The 10-minute stale-lock window (§1.6) misclassifies
   in both directions; crash detection is the positive session-seal fact (§3.2).
7. **Safety features shipped default-off indefinitely.** A dark journal protected nobody for a full
   release cycle (§1.6). Default-on, opt-out (§2.5); "one release behind a flag" requires a dated
   flip criterion.
8. **Multiple full saves per user gesture.** One Ctrl+S = one capture, one head write, one
   checkpoint (§1.5); if two managers both want to save, one delegates.
9. **Exit paths that bypass the protocol.** Hiding the primary stage, switching workspace, or
   terminating without running §5.4 recreates §1.7. `setOnHidden` is for disposal only.
10. **Retention policies pointed at directories nothing writes.** §1.8's ghost pruning. A retention
    surface names its governed directory and a test proves files there come and go.
11. **Unconditional success announcements.** "Opened project" over missing audio, a healthy strip
    over dead checkpoints (§1.1, §1.4). Outcomes are qualified or they are lies.
12. **Apply-time writes to unrelated live state.** The tempo reset on every Apply (§1.9). Apply
    touches edited facts only, undoably where they hit the project.
13. **Bare origin-pivot scale transforms as "UI scale".** §1.9's clipped window. Scaling is a
    layout concern (§5.6).
14. **Swallowing a typed exception into its supertype's generic handler.** `LockStolenException`
    caught as `IOException` → toast (§1.7) destroyed the documented Save-As contract. Typed
    failures get typed handling.

---

## Appendix A — Mapping to existing code

Where each construct in this book attaches to today's tree.

| This book | Today's code | What changes |
|---|---|---|
| Atomic-replace writer (§4.1) | recovery-only pattern in `ProjectLifecycleController.java:1368` onward; bare writes at `ProjectManager.java:493`, `:527`, `CheckpointManager.java:279` | pattern moves into `daw-core` persistence; all writers adopt it (story 331) |
| Checkpoint snapshot isolation (§4.2) | live-model supplier `MainController.java:829-836`; `CheckpointManager.java:288` IOException-only catch; `DawProject.java:270-271` live views | FX-thread capture + worker write; `Throwable` → `notifyFailed` (331) |
| Single-save collapse (§4.2) | `ProjectLifecycleController.java:358-359` double call; `ProjectManager.java:290-307`, `:317-332`; sync I/O per `CheckpointManager.java:256-262` | one canonical save entry, off-FX (331) |
| Clip rehydrator (§4.3) | absent — `ProjectDeserializer.java:539` path-only clips; consumers `RenderPipeline.java:894-895`, `:1069`, `ClipOverlayRenderer.java:152`; `AudioClip.java:327` never load-called | new load-side service in the open pipeline (329) |
| Missing-assets surface (§4.3) | collected at `ProjectDeserializer.java:532`, `:535`; accessor `:133` test-only; unconditional success `ProjectLifecycleController.java:866` | report + relink UI; qualified open outcome (329) |
| Parity harness (§4.4) | asymmetries: `ProjectSerializer.java:341-343` vs no reader; `:252-254` vs no reader; `serializeMidiClip` `:1062` test-only | schema parity + round-trip test family in `daw-core` (330) |
| Plugin-slot descriptor (§4.5) | `InsertEffectFactory.java:86-107` null types; `ProjectSerializer.java:510-512` guard; `ProjectDeserializer.java:893-902` drop; `PluginRegistry.java:25-37` in-memory registry | descriptor + placeholder + persisted registry (334) |
| Tempo-map / take schema (§4.6) | `Transport.java:74` map unserialized (`ProjectSerializer.java:235`); `Track.java:98-99` takes unserialized; ruler already map-driven `TimelineRulerModel.java:58` | schema additions under migration registry (334) |
| Recovery gate (§4.7) | Welcome-only `ProjectLifecycleController.java:1248`; staleness gate `ProjectHealthScanner.java:173-178` + `ProjectLockManager.java:65`; rotation hazard `ProjectJournalCoordinator.java:358-372`; dark default `SettingsModel.java:78`, `ProjectJournalCoordinator.java:185-191` | gate inside `loadProjectFromPath` before journal open; seal-based detection; default ON (332) |
| Exit coordinator (§4.8) | `MainController.java:1007` dispose-only; `DawApplication.java:98`; `ProjectManager.java:339-359` zero callers; no Exit/Save As in `MenuConstructionService.java:141-182`; stolen-lock swallow `ProjectLifecycleController.java:370-376`, `ProjectManager.java:346-349`, `LockStolenException.java:6-11`, `LockStatusIndicator.java:110-111` | close-request protocol + menu items + Save-As flow + first `closeProject` caller (333) |
| Retention unification (§4.8) | unseeded index `CheckpointManager.java:53`, `:82-105`; no-op policy `:360-364`; orphan setter `:243`; ghost directory `BackupRetentionController.java:94-95`, `:127-129`; rows `SettingsDialog.java:146` | index seeds from disk (331); policy delivered to real directory (335) |
| Settings truth (§5.6) | `MainController.java:822` + `AutoSaveConfig.java:23-24` vs `SettingsModel.java:70`; `LiveSettingsApplier.java:30-33`, `:35-41`, `:42`; `SettingsDialog.java:2042`; `SettingsModel.java:492-493` + `MainController.java:2197` | per-row authority wiring at startup + apply (335) |

---

## Appendix B — Cross-references

| Section | Reference | Application |
|---|---|---|
| §2.3, §4.2, §6.1 | `javafx-application-design` §11 | FX thread never blocks on I/O; capture-then-worker pattern |
| §4.3, §6.1 | Control Synchronization book §4.5 | one marshalling seam; per-clip completions coalesced |
| §5.3 step 7 | Control Synchronization book §5.7 | full-load cascade order after a (possibly recovered) open |
| §3.2, §4.7 | Project Manager book §5.1, §4.6.1; story 298 | artifact layers; recovery dialog reused |
| §2.2, §4.1 | Project Manager book §5.2 | atomic writes made universal |
| §2.3, §4.2 | Project Manager book §5.3 | one writer per file; CoW/dirty-flag snapshot strategy |
| §5.4 | Project Manager book §2.5, §8 | prompt-vs-autosave reconciliation with a demolition condition |
| §5.6 | Settings View book (apply contract) | apply-only-edited; visible rejection; restart-class honesty |
| §4.5 | Plugin View book; stories 300–304 | manifest identity, install flow, editor/rack idioms |
| §6.2 | `FAILURE_SURFACING_DESIGN_BOOK.md` | notification injection, global handlers, dialog dismissibility |
| §7.2 | `AUDIO_ENGINE_WIRING_DESIGN_BOOK.md`, `RECORDING_RELIABILITY_DESIGN_BOOK.md`, `INTERACTION_COMPLETENESS_DESIGN_BOOK.md` | engine consumes rehydrated audio; capture produces what reload consumes; menu/visual truth polices the new items |
| §3.3, §6.3 | `research-daw` §3 (project file format) | versioned custom layout; portable project directories |
| §1.10, §4.5 | `dawg-annotations-reflection` | reflective parameter snapshot as the built-in persistence path |
| §8 stages | `javafx-application-design` §2 | each stage ships as an independent increment |
| §8 | stories 019, 063, 188, 189, 190, 191, 298, 299, 303; Book 2 stories 323/328 | existing-story weave points per stage |

---

*End of book.*

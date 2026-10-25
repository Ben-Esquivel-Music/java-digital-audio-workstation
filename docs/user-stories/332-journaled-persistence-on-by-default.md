---
title: "Journaled Persistence On by Default, Recovery on Every Open Path"
labels: ["bug", "persistence", "reliability", "crash-recovery"]
---

# Journaled Persistence On by Default, Recovery on Every Open Path

## Motivation

Story 298 built the write-ahead journal, `RecoveryScanner`, and `RecoveryDialog` end to end — and none of it can run on a stock install. The safety system is simultaneously built, disabled, unreachable, and self-erasing:

- **Off by default.** `DEFAULT_USE_JOURNALED_PERSISTENCE = false` (`SettingsModel.java:78`), and the coordinator's `openFor` returns immediately unless the preference is true (`ProjectJournalCoordinator.java:185-191`; the supplier is solely the preference, `MainController.java:684`). No journal, no recovery — ever — unless the user finds a hidden toggle.
- **Even flag-on, recovery is reachable from exactly one place** — the Welcome Recover tile (`ProjectLifecycleController.java:1248`) — and a project only *classifies* as recoverable when its lock is more than 10 minutes stale (`ProjectHealthScanner.java:173-178`, `ProjectLockManager.java:65`). Relaunch within 10 minutes of a crash — the overwhelmingly common case — and the project presents as a plain "Continue".
- **Every other open path erases the evidence.** Continue, Hub, File ▸ Open, and archive restore all funnel through `loadProjectFromPath` (`ProjectLifecycleController.java:844`), which never runs the recovery scan but *does* open the journal — whose rotation listener rotates on the **first successful checkpoint** (`ProjectJournalCoordinator.java:358-372`), permanently discarding the crashed session's un-replayed records within minutes of the plain reopen.

For a studio engineer this means a crash still costs everything since the last checkpoint, even though the machinery built to prevent exactly that is complete and tested. Story 298 deliberately shipped dark for one release (its §7 Stage-4 exit criterion); that release has shipped. The flag flips.

## Goals

- **`useJournaledPersistence` defaults ON** (design book §2.5). The preference remains an opt-out for experts. A stock install writes journal records with no settings visit.
- **The recovery gate (book §4.7) runs on every open path** — Welcome ▸ Continue, Hub ▸ Open, File ▸ Open, archive restore, and the Recover tile all execute the same gate on a virtual thread (FX never scans) **before** the journal coordinator's open/rotation hooks. The Welcome Recover tile remains as an explicit entry to the same gate, no longer the only one.
- **Crash detection becomes the session seal, not a timing heuristic** (book §3.2): a final sealed journal record — fsync-ordered, written when a session's journal closes cleanly (project switch and in-app project close now; the app-exit write lands with story 333's protocol). Absence of a seal, or records after the last checkpoint marker, classifies the project as a recovery candidate — regardless of how fresh the leftover lock looks. The 10-minute lock-staleness window and the Welcome-only entry point are retired as *detection*; lock staleness is demoted to an informational fact for the conflict dialog.
- **Rotation never discards un-replayed records.** Journal rotation is forbidden while un-replayed records from a prior session exist; rotation keys off the applied recovery decision (replay committed or discard chosen), never off the first checkpoint of the new session.
- **The recovery decision table (book §5.5) is implemented as specified**: absent journal → plain open with the gate logging degraded lock-heuristic mode; sealed → plain open with stale-segment rotation; unsealed with trailing records → the story-298 `RecoveryDialog` (Replay all / Checkpoint only / Inspect / Cancel — reused untouched); unsealed but lock held fresh by another live session → conflict dialog first, gate re-runs after lock resolution.
- **The open contract's ordering holds** (book §5.3): step 2 RECOVER strictly precedes step 6 JOURNAL — precisely the inversion of today's `loadProjectFromPath`, whose journal-open hook lets the first checkpoint destroy the evidence.
- **The gate handles the temps of story 331's writer** (story 331, rulings M1–M3). On a writable open with the project lock held, the recovery gate:
  1. handles every `AtomicFileWriter` temp of `project.daw`'s real file, any number of them, not one. When `project.daw` is a symbolic link (story 331, ruling M3), these temps sit beside the link's final target and are named after it (`.<target name>.<16 hex>.tmp`);
  2. also handles the `AtomicFileWriter` temps of the project folder's other files and of `snapshots/`, matching the exact temp grammar only;
  3. treats a complete head temp newer than `project.daw` as recovery evidence;
  4. leaves no temp behind after its decision, and logs at INFO how many it handled.
- **Rotation and replay follow a checkpoint's capture point** (story 331, ruling G12). (a) The journal's "since last checkpoint" count reset and its rotation point are tied to the checkpoint's capture point — the revision or journal sequence number at capture — not to when the checkpoint's write finished; today `ProjectJournalCoordinator`'s rotation listener rotates on `onAfterCheckpoint`, when the write has finished. (b) Replay onto a checkpoint starts after that checkpoint's capture point: a replay that applies every segment applies the records captured before the checkpoint twice unless every record is idempotent, and this story decides which. Story 331 keeps the captured revision with every checkpoint and reports it (`CheckpointEvent.Written.revision()`).
- **The replay base is the checkpoint the session started from** (story 331, rulings G3 and H4). Replaying onto the newest checkpoint is right only when that checkpoint is the state the session started from; many heads have none that matches — one the recovery writer or an archive restore wrote, a save whose checkpoint failed, a folder copied without `checkpoints/`, a save from before story 331 — and the gate never gives such a head a stale base. The newest checkpoint is not chosen by the local-time stamp in its name alone, which sorts wrongly in the hour repeated when daylight saving time ends or after a clock step: the index is monotonic for every checkpoint a recorded fact covers, and the fact never covers what a run writes after a seed that could not list `checkpoints/`, because that run numbers its checkpoints on from the highest index it read before the failure. Checkpoints written before story 331, whose index restarted at 001 on every launch, keep the stamp order (ruling F5); the index rule must not reorder them (ruling H, item 7 of what its fix round must not do). Today `RecoveryScanner.findNewestCheckpoint` takes the newest by `CheckpointFileName.ORDER`, the stamp first. Which checkpoints the index orders is decided from a recorded fact, never inferred from the names: before story 331 each launch also deleted its own oldest checkpoints past the cap, so a folder from then can hold indices that never repeat, 011–060 from one launch and 001–005 from the next (ruling O); checkpoints written before that fact was first recorded, story 331's own included, keep the stamp order, and whichever of stories 332 and 335 lands first has the checkpoint writer record it. The checkpoints the fact covers come after every other checkpoint, which keeps the stamp order, and story 335 orders `CheckpointManager`'s seeded checkpoints across a restart by the same rule.
- **A read-only open writes no session manifest** (story 331, ruling H, item 2 of what its fix round must not do; flagged for stories 332 and 333). `MainController.refreshSessionManager(true)` calls `SessionManager.openSession` on every load, an `OPEN_READ_ONLY` open included: unless it continues a recent session, it writes a session of its own, and it seals the lock holder's active session once that session started more than `SessionManager.DEFAULT_SESSION_GAP` (4 h) ago (an active manifest has no end time, so its start counts as its last activity). A read-only open writes no manifest and seals nothing.
- **The replay base and rotation are defined with autosave disabled** (story 331, ruling H3). With autosave off a save writes no checkpoint (no `AFTER_SAVE`), so only File ▸ Create Checkpoint… or a failed save's safety copy writes one, and nothing else rotates the journal; this story states which checkpoint is the replay base and when the journal rotates in that case.
- **Journal segment names, and the retention policy's numbers, are ASCII under every locale** (found by story 331's prober; on `main` already). `JournalWriter.openSegment` names a segment `String.format("segment-%03d.bin", index)` in the default format locale, and `RecoveryScanner.listSegments` matches `segment-(\d+)\.bin`, ASCII digits only. Under a default locale with other digits — `ar-SA`, `fa-IR` or `th-TH-u-nu-thai`, for example — the writer names `segment-٠٠٧.bin`, which the scan never lists, so recovery silently sees no journal. `BackupRetentionPolicyStore.toJson` formats its numbers in the same locale, so the policy file holds digits that are not JSON, which only this class's own reader (`Integer.parseInt`, `Long.parseLong`) reads back. Both are written with `Locale.ROOT`, as story 331's `CheckpointFileName` is.

## Goals — Tests

- **Crash-shaped test per open path**: for each of Continue, Hub ▸ Open, File ▸ Open, and archive restore, an unsealed journal with records after the last checkpoint marker presents the `RecoveryDialog` *before* any journal open or segment rotation occurs (assert no rotation has happened when the dialog shows).
- **Blind-window-gone test**: relaunch ~30 seconds after a simulated crash (lock fresh, journal unsealed) offers recovery — the 10-minute staleness heuristic no longer gates classification.
- **Clean-close test**: a journal ending in a session seal with nothing after the last checkpoint marker opens plainly with no dialog, and stale segments rotate.
- **Default-on test**: a fresh install (no settings visit) writes journal records during an editing session; setting the opt-out preference suppresses journaling and the gate logs its degraded lock-heuristic mode.
- **Rotation-guard test**: with un-replayed prior-session records present, the new session's first successful checkpoint does *not* rotate the journal; rotation occurs only after the recovery decision is applied (replay committed, or discard chosen and the journal deleted).
- **Seal-write test**: switching projects (and closing a project in-app) appends and fsyncs the seal record before the new session's journal opens; the next open of that project classifies as clean.
- **Decision-table coverage**: one test per §5.5 row, including the fresh-foreign-lock row where the conflict dialog resolves first and the gate re-runs.
- **Writer-temp test** (story 331, rulings M1–M3): a writable open of a folder holding several `AtomicFileWriter` temps of `project.daw` — one of them a complete head newer than `project.daw` — temps of another project-folder file and of `snapshots/`, and near-miss names: the newer complete head is treated as recovery evidence, no temp of the grammar is left after the decision, every near miss is untouched, and one INFO line gives the count. Again with `project.daw` a symbolic link to `../shared/Song.daw` (gated on being able to create links): the temps named after `Song.daw` beside it are handled.
- **Capture-point test** (story 331, ruling G12): records appended while a checkpoint's write is held after its capture survive the rotation that checkpoint triggers, and a replay onto it applies each record exactly once.
- **Replay-base test** (story 331, rulings G3 and H4): a head the recovery writer wrote, with no matching checkpoint, is never replayed onto the newest checkpoint as its base; and of two checkpoints that a recorded fact covers and whose stamps fall in the repeated daylight-saving hour in reverse order, the higher index is the newest; and in a folder written before story 331 — 001–050 stamped yesterday, 001–003 stamped today — the newest is today's 003; and again with 011–060 stamped yesterday and 001–005 stamped today, where no index repeats (ruling O), the newest is today's 005; and continuing that folder, with 061 written by `CheckpointManager` with its clock seam stepped back to 2000-01-01, the newest is 061.
- **Read-only session test** (story 331, ruling H, item 2 of what its fix round must not do): opening a project read-only while another session's active manifest started more than 4 h ago writes no manifest and seals none.
- **Autosave-off test** (story 331, ruling H3): with autosave disabled, saves, a Create Checkpoint and a crash: the replay base and the journal's rotation are the ones this story defines.
- **Locale test** (found by story 331's prober): under the default locale `ar-SA`, and again `th-TH-u-nu-thai`, the journal writes `segment-000.bin`, the recovery scan lists it, and `BackupRetentionPolicyStore.toJson` writes ASCII digits.

## Non-Goals

- **The recovery dialog, scanner, replay, and discard-then-load flows** — story 298 (landed); this story changes their reachability and activation, not their behaviour. The re-present-Welcome flow on Inspect/Cancel (`ProjectLifecycleController.java:1248-1312`) is kept as is.
- **Atomic commit of the replayed head** — replay commits through the shared atomic-replace writer landed by story 331 (Stage 3 prerequisite).
- **The app-exit seal write and the exit protocol** — story 333 owns the close-request handler, `closeProject()`, and the exit-side SEAL step; this story lands the seal record format, its clean-close writers on the project-switch/close paths, and its reader (the gate).
- **Journal writer internals and back-pressure** — `PROJECT_MANAGER_DESIGN_BOOK.md` §6.3 (existing, keep).
- **Dialog-dismissibility conformance for the RecoveryDialog** — Book 4 story 339's conformance test covers every `DawgDialog` host.
- **Clip rehydration and the missing-assets report after a (possibly recovered) open** — story 329.

## Technical Notes

- Implements **Stage 4 — Journaled Persistence On by Default, Recovery on Every Open Path** of `docs/design/PERSISTENCE_INTEGRITY_DESIGN_BOOK.md` (§2.5 default-on, §2.6 every-open-is-a-potential-recovery, §3.2 session seal, §4.7 recovery gate, §5.3 open contract, §5.5 decision table).
- Files: `SettingsModel.java:78` (default flip); `ProjectJournalCoordinator.java:185-191` (`openFor` remains the opt-out seam), `:358-372` (rotation listener re-keyed off the recovery decision); `ProjectLifecycleController.java:844` (`loadProjectFromPath` gains the gate ahead of its journal-open hook), `:1248` (Recover tile routes into the same gate); `ProjectHealthScanner.java:173-178` + `ProjectLockManager.java:65` (staleness demoted to informational for the conflict dialog); `MainController.java:684` (preference supplier unchanged, now defaulting true).
- **Why the seal is a journal record, not a marker file** (book §3.2): a separate `.clean-close` file is a second source of truth that can drift; the journal already is the authoritative record of un-checkpointed work, and a final fsync-ordered record makes "clean close" a positive fact in the stream the recovery scan reads anyway. With the journal opted off, the gate degrades to the lock heuristic — explicitly, as the documented lesser mode.
- Cross-refs: **story 298** (landed dark — this story executes its deliberately deferred default promotion), **story 331** (Stage 3 — replay commits via the §4.1 writer), **story 333** (Stage 5 — writes the seal at app exit; its clean exits also stop feeding the health scanner phantom stale locks), **story 313 / 339** (Book 4 — dialog dismissibility for every prompt this path shows).
- Rejections bound here: book §9.6 (timing heuristics as crash detection), §9.7 (safety features shipped default-off indefinitely).
- Threading per book §6.1/§4.7: the gate runs on a virtual thread; the FX thread never scans; outcome surfaces publish through the established dispatcher seam.

## Implementation and acceptance evidence — 2026-10-25

The application enables journaling for a fresh settings node. Every disk-open
door, including explicit Recover and archive extraction's follow-up open, uses
`ProjectManager.PreparedOpen`: an independent target lock lease negotiates
conflicts and runs `OpenRecoveryGate` before the outgoing project is retired.
Cancel and Inspect release only that target lease, preserve the outgoing model,
lock and live recorder, and re-present Welcome. The main controls are disabled
while the asynchronous handover is pending. Refused requests do not invalidate
the active open attempt, and an older migration/reload continuation cannot clear
a newer attempt's installation guard.

The worker then seals the outgoing journal, parses the accepted head, starts
rehydration and accounting through the FX dispatcher, opens the new journal on
the worker, and performs the UI cascade. A direct request to reopen the active
folder is refused without scanning its live journal. Migration rollback has an
explicit close/discard/reload transition. App disposal uses an **unsealed**
drain; story 333 still owns an app-exit protocol that can safely certify a final
save or discard.

The journal records a checksummed, fsynced `$session-seal` only after all accepted
records drain successfully. Missing seals, torn bytes after a seal, or mutations
after the last captured checkpoint boundary remain recovery evidence. A seal
alone cannot conceal uncheckpointed edits. A new writer refuses nonempty prior
segments until the recovery decision has been applied. A failed replay or atomic
head commit preserves the evidence and opens the last saved head **read-only**,
with the existing qualified failure announcement; it starts neither a writable
journal nor a session manifest.

### Capture points, replay bases and ordering

`EventBus.onPublication` gives the recorder a synchronous, non-I/O publication
fence. It avoids the ordinary bus subscription's asynchronous delivery and
overflow window. Scheduled captures, save captures and Create Checkpoint captures
report their revision on the model owner. A successful checkpoint's journal
marker records the sequence observed at that capture, its filename and its
SHA-256 digest. The journal's count reset excludes only records represented by
that capture; later edits survive even when the checkpoint's atomic write was
held. Replay uses a matching, hash-verified checkpoint marker and dispatches only
records after its boundary.

Each new journal also keeps an immutable `journal/session-base.daw`, verified by
the `$session-start` record. This is the replay base when the session did not
start from a matching checkpoint, including recovery-written, archive-restored
and legacy heads. With autosave disabled, ordinary saves create no checkpoint
marker and do not rotate: the immutable session-start base remains authoritative
until a successful explicit Create Checkpoint (or safety-copy checkpoint) supplies
a verified capture point. The underlying story-298 replay fidelity is unchanged:
current notification-only event families are dispatched and counted but do not
reconstruct missing state deltas. The new tests distinguish exactly-once dispatch
from state already present in a verified checkpoint or head.

`checkpoints/.checkpoint-order` explicitly lists the filenames certified by
complete directory enumeration followed by new writes. Certified files sort
after all uncertified files and by monotonic index; uncertified files retain
stamp order, including pre-331 folders whose indices happen never to repeat.
Files from a partially enumerated run are never certified. Before such a run
publishes a name previously certified (or a name certified before a crashed
publish), it atomically revokes that certification. Malformed or unreadable facts
fall back to stamp order. A failed fact publication prevents the new checkpoint
from being published.

### Atomic-writer temps and read-only sessions

The writable, locked gate inspects every exact `AtomicFileWriter` temp in the
project folder, snapshots and checkpoints. For a linked `project.daw`, it also
checks the final `.daw` target's siblings, restricted to that target's name.
Complete project XML newer than the head is recovery evidence. Temp-only Replay
promotes that document atomically. When both a newer complete temp and an
unsealed journal exist, their capture order cannot be inferred: the gate keeps
the temp byte-for-byte in `snapshots/recovered-head-<request-id>.daw`, logs its
path, and replays the journal from its verified base. It cleans exact temps only
after the decision succeeds and emits one INFO count; near-miss names remain.

Read-only opens perform no recovery mutation, temp cleanup, journal creation or
session-manifest write. `MainController.refreshSessionManager` captures that
policy before scheduling its worker and fences both work and FX publication by
the current session generation, so a delayed writable refresh cannot reattach
its session after a read-only open.

### Regression suites

| Contract | Behavioral evidence |
|---|---|
| Every real open door; unchanged evidence inside the dialog; no premature journal open/rotation | `EveryOpenDoorRunsRecoveryFirstTest` (File, Hub, Welcome Continue, actual archive extraction, Recover) |
| Enabled-gate Cancel/Inspect, same-folder refusal, refused second load, replay/commit failure fallback | `EveryOpenDoorRunsRecoveryFirstTest` |
| Absent/clean/replay/discard/foreign-lock rows; fresh leftover lock; degraded logs | `OpenRecoveryGateTest` |
| All temp names, complete newer head, symlink target, mixed temp/journal preservation, INFO count | `OpenRecoveryGateTest` |
| Fresh settings activate a real bus recorder; opt-out suppresses it | `JournalCaptureIntegrationTest` |
| Real save with held checkpoint write; autosave-off save → Create Checkpoint → save → crash | `JournalCaptureIntegrationTest` |
| Project-switch seal and app-disposal unsealed drain | `JournalCaptureIntegrationTest` |
| Capture-boundary dispatch, immutable base, changed checkpoint/base digest, stalled asynchronous subscriber, seal/drain fsync failures, torn tail, locales | `JournalSessionRecoveryTest` |
| Legacy repeated and unique indices, repeated DST hour, backward clock, restart, partial enumeration, ghost/overwritten certification, malformed/unreadable/failed fact | `CheckpointOrderingProvenanceTest` |
| Actual MainController read-only refresh with a >4-hour active manifest; stale callback after read-only open | `ReadOnlySessionRefreshTest` |

The implementation uses the repository's `research`, `research-daw` and
`javafx-application-design` skills: persistence and provenance stay in `daw-core`,
application dialogs and FX publication stay in `daw-app`, immutable captures stay
on the model owner, and file work uses final virtual-thread APIs (JEP 444).
The studio-engineer review drove the publication fence, seal/failure ordering,
independent target lease, mixed-temp preservation and read-only session checks.
No external persistence library or preview/incubator API was added.

The focused reactor run on JDK 27 and Maven 3.10.0 passed **124 tests** (31
`daw-core`, 93 `daw-app`), including **50 new acceptance cases**, with no failures,
errors or skips. It ran the suites in the table plus
`JournalAppendFsyncRotationTest`, `RecoveryReplayStructuredTest`,
`JournaledPersistencePreferenceGateTest`, `ProjectJournalCoordinatorTest` and
`SettingsModelTest`. Reports are under each module's `target/surefire-reports/`.

The final related regression completed **563 cases: 562 passed and one skipped**,
with no remaining failures or errors: 296 core cases (one POSIX-permission case
skipped on Windows) and 267 application cases. All 50 new acceptance cases are
included. The skipped existing case is
`SessionManagerTest.aStaleSessionTheUserCannotWriteIsStillSealedAndANewSessionStarts`.
Production sources were unchanged between the green core run and the final green
application run; the intervening changes only updated fixture checkpoint filters
and explicit replay-base provenance while preserving their original assertions.
The core results are retained in `target/story332-regression.log` (whose earlier
application failures were subsequently corrected); the final application-only
run is `target/story332-app-regression.log` and ends in `BUILD SUCCESS`.

The broad reactor command was:

```powershell
mvn -B -pl daw-app -am test '-Dtest=*Journal*,*Recovery*,*Checkpoint*,*ProjectLifecycle*,*SessionManager*,SettingsModelTest,*ProjectHealth*,*EventBus*,*ProjectManager*,*ReadOnly*,EveryOpenDoorRunsRecoveryFirstTest,*PendingSwitch*,*LateLoad*,*Migration*,*SaveFlow*,*Welcome*,*Hub*' '-Dsurefire.failIfNoSpecifiedTests=false' '-DskipNativeBuild=true'
```

After the core phase passed, the final application phase selected the 41
application suites reported that day and used their fully qualified names. This
compiled reactor dependencies without needlessly rerunning the green core tests:

```powershell
$appTestSuites = Get-ChildItem daw-app/target/surefire-reports -File -Filter 'TEST-*.xml' |
    Where-Object LastWriteTime -GT (Get-Date).Date |
    ForEach-Object {
        [xml]$report = Get-Content -LiteralPath $_.FullName
        [string]$report.testsuite.name
    } | Sort-Object -Unique
mvn -B -pl daw-app -am test "-Dtest=$($appTestSuites -join ',')" '-Dsurefire.failIfNoSpecifiedTests=false' '-DskipNativeBuild=true'
```

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
- **The recovery decision table (book §5.5) is implemented as specified**: absent journal → plain open with the gate logging degraded lock-heuristic mode; sealed → plain open with stale-segment rotation; unsealed with trailing records → the story-298 `RecoveryDialog` (Replay all / Checkpoint only / Inspect / Cancel — the four choices and their labels reused; the dialog body and, when the last clean save is newer than the recovery point, the default button changed in PR 988 round 3); unsealed but lock held fresh by another live session → conflict dialog first, gate re-runs after lock resolution.
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

- **The recovery dialog, scanner, replay, and discard-then-load flows** — story 298 (landed); this story changes their reachability and activation, not their behaviour. The re-present-Welcome flow on Inspect/Cancel (`ProjectLifecycleController.java:1248-1312`) is kept as is. PR 988 round 3 is a scoped exception: the choices, their labels and the Inspect/Cancel flow are untouched, but the dialog body, the plan-based replay, Restore checkpoint only's refusal without `project.daw`, the interrupted-save retention and, as an interim step, the default button when the last clean save is newer than the recovery point changed.
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
the temp byte-for-byte as an "Interrupted save" snapshot (`SnapshotStore`; its
file is `snapshots/<yyyyMMdd-HHmmss>-interrupted-save.daw`), logs its path, and
replays the journal from its verified base; Restore checkpoint only keeps it the
same way. It cleans exact temps only
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

The initial **selected** related regression completed **563 cases: 562 passed and one skipped**,
with no failures or errors among those selected cases: 296 core cases (one POSIX-permission case
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

### Full default-suite validation follow-up

The 563-case validation above used explicit `-Dtest` selection. It did **not**
run the full repository test suite. Subsequent full-suite execution exposed
additional save/backup instrumentation that treated every publication other
than a checkpoint as a head or backup, or expected only two total publications.
Those tests now assert the complete typed sequence, including the ordering fact:
backup → head → ordering fact → checkpoint, or ordering fact → safety checkpoint
after a failed backup. They still reject unknown writes, duplicate heads or
checkpoints, head writes after a failed backup, and publications on the owner
thread. A failed backup still leaves the backup owed for the next save.

Checkpoint-stop, autosave-disabled and event-channel fault/hold seams explicitly
target checkpoint filenames, so they continue to stall or fail the checkpoint's
atomic publication rather than accidentally intercepting its metadata first.
The independent ordering-fact publication-failure tests remain in place.

Recovery-link and unread-item announcement fixtures now supply hash-verified
checkpoint capture markers instead of assuming an unrelated newest checkpoint
is the session's base. Their existing link-preservation, refusal, backup and
qualified-warning assertions remain intact. Profile import exercises the real
default-on setting changing to an explicit opt-out. The existing menu test
harness now propagates FX-thread assertion failures to JUnit, with a dedicated
regression; its structure assertions describe the seven-menu hierarchy already
present before story 332. No production menu or persistence behavior was changed
by these full-suite follow-up corrections.

Full default-reactor validation uses **no test selector** and disables snapshot
creation and updates:

```powershell
mvn -B test '-DskipNativeBuild=true' '-Dsnapshots.autoBaseline=false' '-Dsnapshots.update=false'
```

This is the default test suite; the repository's opt-in `long-tests` profile is
separate. The first unfiltered run, retained in `target/story332-full-test.log`,
completed 14,314 cases with four failures, no errors and 43 skips. Its four
failures were the recovery-link, profile-import and unread-item fixtures
described above. The corrected affected application suites then passed all 44
cases, including the added FX-assertion propagation regression, in
`target/story332-full-suite-app-corrections.log`.

The final unfiltered default-reactor run of that exact command completed with
**BUILD SUCCESS: 14,315 cases, 14,272 passed, 43 skipped, zero failures and zero
errors**. Module totals were 1,348 SDK cases (two skipped), 106 acoustics cases,
8,649 core cases (38 skipped), 29 FX cases and 4,183 application cases (three
skipped). The skips cover optional native-library availability, platform-specific
cases and explicitly disabled cases; none of the 50 new story-332 acceptance
cases was skipped. Snapshot creation and updates were disabled throughout.
The complete green run is retained in `target/story332-full-test-final.log`.

These full-suite follow-up changes affect tests and this validation record only;
production sources remain unchanged from implementation commit `5cee116c`.
The opt-in `long-tests` profile was not run. No changes were committed or pushed
by the agents.

### CI checkpoint-start publication follow-up

The Ubuntu [PR 988 CI run](https://github.com/Ben-Esquivel-Music/java-digital-audio-workstation/actions/runs/40247765377/job/121332875538?pr=988)
on commit `c435f965` exposed a checkpoint-start race in
`LastGoodCheckpointTest`: a fast writer could report `Seeded` before
`startGeneration` published its running session. The status listener correctly
rejected a fact whose generation was not running yet, leaving the last-good
checkpoint unset. Increasing the test's wait would not recover that lost fact.

Checkpoint start now publishes the session and enqueues its seed job while
holding the same state lock used to accept checkpoint requests, then starts the
writer drain. The session is published before the seed becomes visible to an
already-running drain, and a concurrent request cannot overtake the seed. No
disk work or listener notification is performed inside that critical section.
The default writer still runs on a virtual thread; a package-private controlled
executor permits deterministic scheduling in the regression tests.

Both new regressions failed before the ordering fix: a seeded listener observed
no running generation, and a checkpoint requested by that listener was refused
because no project directory was open. The expected failing run is retained in
`target/story332-ci-seed-race-red.log`. After the fix, both pass and the
listener-requested checkpoint follows seeding at the next persisted index. The
original off-FX last-good-checkpoint test and its timeout remain unchanged.

The focused follow-up passed **38 cases** (27 core and 11 application), with no
failures, errors or skips, using:

```powershell
mvn -B -pl daw-app -am test '-Dtest=CheckpointStartPublicationTest,CheckpointEventChannelTest,CheckpointStopFenceTest,SaveCheckpointGenerationTest,ReplacingAProjectEndsItsCheckpointsFirstTest,LastGoodCheckpointTest,CheckpointFactsFollowTheirGenerationTest,CheckpointCountdownAcrossReplacementsTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DskipNativeBuild=true' '-Dsnapshots.autoBaseline=false' '-Dsnapshots.update=false'
```

The green focused log is `target/story332-ci-seed-race-green.log`. This local
run used Windows, JDK 27 and Maven 3.10.0; it does not claim that the remote
Ubuntu CI job has rerun successfully.

The subsequent **unfiltered local reactor verification passed** with
**14,317 cases: 14,274 passed, 43 skipped, zero failures and zero errors**.
It included 1,348 SDK cases (two skipped), 106 acoustics cases, 8,651 core
cases (38 skipped), 29 FX cases and 4,183 application cases (three skipped),
followed by the JAR and `jlink` runtime-image phases. The skip categories remain
optional native-library availability, platform-specific cases and explicitly
disabled cases. The exact command was:

```powershell
mvn -B verify '-DskipNativeBuild=true' '-Dsnapshots.autoBaseline=false' '-Dsnapshots.update=false'
```

The complete `BUILD SUCCESS` log is `target/story332-ci-verify.log`. This was a
Windows/JDK-27 verification without `clean`, using existing native binaries;
native rebuilding and `jpackage` were skipped, and snapshot creation and updates
were disabled. It was not an Ubuntu CI rerun. The linked CI result still refers
to failed head `c435f965`; validating the correction there requires the user's
commit/push and another CI run. Agents made no commits or pushes.

### PR 988 review 5658601036 — local resolution, 2026-10-26

The ten findings in [the linked review](https://github.com/Ben-Esquivel-Music/java-digital-audio-workstation/pull/988#pullrequestreview-5658601036)
are addressed in the working tree. Java 27 agents made the code changes;
the studio-engineer review checked recording/take preservation, journal evidence,
lease ownership and failure announcements. The JavaFX application-design and
DAW-architecture skills informed the worker/FX separation. No audio callback
path or persisted project schema changed.

| Review comment | Resolution | Regression evidence |
| --- | --- | --- |
| `4382168153` — manifest writes after retirement | Session refresh captures the lock manager and exact lease UUID. `withHeldLease` excludes retirement/rebinding through the complete `openSession` publication. A missing captured lease permits history-only refresh. | `ReadOnlySessionRefreshTest` stalls the worker inside `openSession`; retirement waits, and the same folder's subsequent read-only refresh changes no manifests. `ProjectLeaseRetirementTest` rejects queued work using a retired lease. |
| `4382168256` — opted-out evidence deleted during temp recovery | Both accepted head-temp decisions preserve journal segments and the immutable base when journaling is opted out. | Two `OpenRecoveryGateTest` cases compare segment/base bytes after Replay all and Checkpoint only. |
| `4382168350` — active-folder alias bypass | Worker-side `Files.isSameFile` runs before acquiring any target lease; aliases of the active folder are refused. | `ActiveProjectAliasTest` exercises open/discard through symbolic links and Windows junctions without lock negotiation or journal mutation. |
| `4382168400` — New Project edits during drain | The installation/command guard and disabled root precede recorder detachment and stay set through replacement, initial XML publication and journal readiness. Recording/take state is rechecked before detachment and after seal. | `ProjectReplacementJournalSafetyTest` stalls seal, then starts recording or take-writing; model, lease and evidence remain, with cleanup and an explicit paused-journal warning. Seal failure is also covered. |
| `4382168451` — release before seal on Import/rollback | Import and no-backup rollback install their replacements only after the outgoing seal finishes and the worker retires the lease. | Two `ProjectReplacementJournalSafetyTest` cases hold seal and verify the outgoing lock remains until the continuation. |
| `4382168542` — discard deletes a live journal | Confirmed discard first rejects the active directory identity, negotiates an independent target lease, and holds its writable lease through deletion. Cancel/read-only preserve evidence; explicit Take Over remains available for fresh crash locks. | `LockedRecoveryDiscardTest` covers active, foreign Cancel/read-only and fresh Take Over; alias cases also verify byte/lock preservation. |
| `4382168613` — direct-open cancellation releases A | Unsupplied opens prepare an independent target lease and parse before replacing the outgoing lease. Failed parse or recent-project publication also preserves the outgoing project. | `PreparedProjectLeaseTest` covers cancelled B, parse failure and failed recent-project publication with A still held. |
| `4382168677` — metadata-only replay base | New Project starts journaling only after its initial full XML save succeeds. The guard remains until `openForRequired` confirms the enabled journal is ready for that directory. | `ProjectReplacementJournalSafetyTest` stalls XML publication and journal startup, deserializes the immutable base, and covers save/startup failure; `ProjectJournalCoordinatorTest` exercises a real obstructed journal directory, missing bus and explicit opt-out. |
| `4382168718` — abandoned scheduler threads | Rejected/unconsumed target leases and retired managers are closed; ownership transfers only after acceptance. Closing a consumed preparation leaves the accepted lease live. | `PreparedProjectLeaseTest` checks cancellation, unconsumed close and accepted transfer dispose the appropriate schedulers. |
| `4382168816` — readable XML link rejected | Temp inspection follows readable links without applying the replacement writer's `.daw` restriction. The commit writer still enforces that restriction. | `RecoveryTempsReadLinksTest` opens `project.daw` linked to `Song.xml`, finds its newer temp, and verifies replacement remains refused. |

A failed seal or a recording/take that begins despite the installation guard can
leave the outgoing journal closed. The project, take, lease and recovery evidence
are retained; the UI is re-enabled and explicitly reports that journaled
persistence is paused until the project is reopened. No prior segments are
cleared merely to restart recording. The legacy `forceFlush` escape hatch is not
used as a handover barrier.

Final focused validation passed on Windows with Maven 3.10.0 and JDK 27+35:
**170 cases: 169 passed, one skipped, zero failures and zero errors**. Core ran
72 cases (one skip) and the application ran 98 cases (no skips), including all
32 new regression cases. The existing skip is
`ProjectLockManagerTest.aStaleLockTheUserCannotWriteIsStillTakenOverAndKeptFresh`,
which requires Linux/POSIX permission bits. Both symbolic-link and Windows
junction scenarios ran successfully. The exact final command was:

```powershell
mvn -pl daw-app -am test '-Dtest=PreparedProjectLeaseTest,ProjectLeaseRetirementTest,RecoveryTempsReadLinksTest,ProjectManagerLockingTest,ProjectManagerTest,ProjectFileSymbolicLinkTest,ProjectLockManagerTest,ProjectManagerDurableWritesTest,CheckpointsNeedTheProjectLockTest,ReadOnlySessionRefreshTest,OpenRecoveryGateTest,ProjectReplacementJournalSafetyTest,LockedRecoveryDiscardTest,ActiveProjectAliasTest,EveryOpenDoorRunsRecoveryFirstTest,NewProjectPromptsForLocationTest,MigrationRollBackAndRecoveryGuardTest,OpenAnnouncedOnceThroughAMigrationRollBackTest,ProjectLifecycleControllerTest,SingleSaveRequestTest,JournalCaptureIntegrationTest,ProjectJournalCoordinatorTest,RecoverSessionIsTheOnePendingSwitchTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DskipNativeBuild=true'
```

The successful log is `target/pr988-final-review-tests.log`. This is targeted
local regression validation, not a new unfiltered suite, native rebuild,
packaging run or remote CI run. Changes remain uncommitted and unpushed; GitHub
review discussions were not modified.

### PR 988 review 5659203633 and CI 40401778571 — local resolution, 2026-10-26

The six findings in [the follow-up review](https://github.com/Ben-Esquivel-Music/java-digital-audio-workstation/pull/988#pullrequestreview-5659203633)
are addressed against `8483a644cd2636da6ff50a1e5e167973ba6726a0`. Java 27 agents
made the code changes; studio-engineer review traced the disk-open doors,
recording/take ownership, detached windows and checkpoint recovery boundaries.
The JavaFX application-design and DAW-architecture skills informed the FX
capture, worker publication and live input-guard boundaries.

| Review comment | Resolution | Regression evidence |
| --- | --- | --- |
| `4382620291` — disk open retires a take started during seal | Every gated disk-open door rechecks recording and take-writing on FX after the outgoing seal, before lease retirement or target installation. The same-folder migration branch checks before clearing its journal. | `EveryOpenDoorRunsRecoveryFirstTest` drives File, Hub, Continue, Archive and Recover through held seal, then starts recording or take-writing. The original model, audio generation, lease and evidence survive; the target lease is released and no incoming journal starts. Migration rollback has the same checks. |
| `4382620350` — accelerators edit/save during installation | Dynamic guards wrap registered shortcuts, retained palette handlers, menu actions and workspace actions. Direct save/checkpoint, undo/redo and transport dispatch are fenced; Stop remains available. Floating dock and immersive A/B roots inherit the installation property. A/B closes when its project is replaced. Autosave skips capture while the FX model is between projects. | `ProjectInstallationInputFenceTest` exercises actual registered accelerators, retained handlers, direct menu actions, held-seal floating controls and A/B, plus the interval when ProjectManager owns B while FX still displays A. Save, Undo, Delete and autosave leave B's bytes and A's model intact; normal saving resumes after installation. |
| `4382620407` — revision reused as capture identity | Each checkpoint request carries an immutable `CheckpointManager.Capture` containing its revision and journal sequence. Explicit saves, user checkpoints and scheduled captures retain that exact token through completion. Journal notifications also check the current checkpoint generation. | `CheckpointCaptureIdentityTest` covers explicit and scheduled captures. `JournalCaptureIntegrationTest` uses two transport-only captures with the same revision and both completion orders, a failed-save retry and a retired-generation event. |
| `4382620469` — newest appended marker selects an older state | Recovery chooses the highest hash-verified capture boundary. Equal boundaries retain the latest durable marker; invalid or missing candidates fall back to the next verified checkpoint and ultimately the immutable session base. Retention protects the highest captured recovery base. | `JournalSessionRecoveryTest` covers reversed marker order and an invalid higher candidate. `JournalCaptureIntegrationTest` delays an older explicit save behind a newer user checkpoint, then verifies replay starts from the newer boundary. |
| `4382620517` — seal failure silently leaves journaling paused | Disk-open failure tracks recorder detachment and explicitly reports preserved recovery evidence and paused journaled persistence before restoring editing. It retains the outgoing lease and model. | All five real disk-open doors and migration rollback inject seal failure in `EveryOpenDoorRunsRecoveryFirstTest`, asserting the warning/error, original ownership and evidence, no journal startup and guard cleanup. |
| `4382620583` — certification metadata grows after pruning | Successful or confirmed-missing deletion removes its certification. Complete startup enumeration compacts absent names; uncertain or partially enumerated files retain provenance. A failed publication confirmed absent is removed before the next publication. | `CheckpointCertificationCompactionTest` covers repeated retention, restart compaction, publication failure before/after physical publication, deletion refusal and the protected recovery base. |

With healthy storage deletion, an older capture completing late can temporarily
leave `cap + 1` checkpoint files: the highest captured recovery base and the
file just returned to the caller both remain until a newer capture supersedes
them. Refused deletions preserve their files and certifications, so the cap is
not an unconditional storage bound.

The failing [CI job](https://github.com/Ben-Esquivel-Music/java-digital-audio-workstation/actions/runs/40401778571/job/121849202158)
expected ProjectManager to expose B before its recent-project publication
completed. The preceding lease fix deliberately keeps A installed until B is
accepted. `SaveCheckpointGenerationTest` now checks A's model and lease before
acceptance, B afterward, and retains the original assertion that the superseded
save requests no checkpoint. Its seven cases passed locally.

Focused validation passed **155 distinct cases, zero failures, errors or
skips**: 47 core cases in `target/pr988-round2-focused.log` and 108 application
cases in `target/pr988-round2-app-tests.log`. This includes all **43 new
regression cases**. The first integrated command reached a test-compilation
error in new event-bus cleanup; that fixture was corrected and the application
selection rerun successfully. The exact commands were:

```powershell
mvn -B -pl daw-app -am test '-Dtest=CheckpointCaptureIdentityTest,CheckpointCertificationCompactionTest,SaveCheckpointGenerationTest,JournalSessionRecoveryTest,CheckpointWithAutosaveDisabledTest,JournalCaptureIntegrationTest,ProjectInstallationInputFenceTest,EveryOpenDoorRunsRecoveryFirstTest,ProjectReplacementJournalSafetyTest,CheckpointCaptureFailureReachesTheStripTest,DawMenuBarControllerTest,WorkspacesMenuTest,SingleSaveRequestTest,MigrationRollBackAndRecoveryGuardTest,OpenAnnouncedOnceThroughAMigrationRollBackTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DskipNativeBuild=true' '-Dsnapshots.autoBaseline=false' '-Dsnapshots.update=false'
mvn -B -pl daw-app -am test '-Dtest=JournalCaptureIntegrationTest,ProjectInstallationInputFenceTest,EveryOpenDoorRunsRecoveryFirstTest,ProjectReplacementJournalSafetyTest,CheckpointCaptureFailureReachesTheStripTest,DawMenuBarControllerTest,WorkspacesMenuTest,SingleSaveRequestTest,MigrationRollBackAndRecoveryGuardTest,OpenAnnouncedOnceThroughAMigrationRollBackTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DskipNativeBuild=true' '-Dsnapshots.autoBaseline=false' '-Dsnapshots.update=false'
```

The first unfiltered reactor attempt (`target/pr988-round2-verify.log`) ran
14,392 cases: 14,348 passed, 43 skipped and one failed. It exposed an older
`OpenGenerationFenceTest` expectation that New Project's success notification
was synchronous. The preceding initial-XML ordering fix deliberately emits
success after the save completes. The fixture now installs a completion
listener before invoking New and awaits that notification with a bounded latch
off the FX thread before releasing the old decodes. Its original audio,
repaint, operation, generation and exact-notification assertions remain.
Studio-engineer review confirmed that the late-completion guarantee is retained.

The next unfiltered attempt (`target/pr988-round2-verify-final.log`) completed
10,159 dependency-module cases again (10,119 passed, 40 skipped), then exposed a test compilation
error in the new listener: `getEntries()` returns a snapshot list. The fixture
now uses the supported `NotificationHistoryService.addListener` API and removes
the listener in `finally`. Its seven cases compiled and passed in
`target/pr988-round2-generation-fence.log`:

```powershell
mvn -B -pl daw-app -am test '-Dtest=OpenGenerationFenceTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DskipNativeBuild=true' '-Dsnapshots.autoBaseline=false' '-Dsnapshots.update=false'
```

The application rerun keeps dependencies in the same reactor and uses
package-qualified versions of
[Surefire's four default patterns](https://maven.apache.org/surefire/maven-surefire-plugin/examples/inclusion-exclusion.html).
Windows batch parsing rejected an initial regex spelling before Maven started;
the Ant-style command below completed **4,219 cases: 4,216 passed, three skipped,
zero failures and zero errors**, followed by JAR and `jlink` runtime-image
creation (`target/pr988-round2-all-app-verify.log`, `BUILD SUCCESS`). The per-class
comparison with the unfiltered run then identified fourteen omitted app-root
cases: `DawLauncherTest` (ten) and `DawRuntimeTest` (four). The `**/` selection
required an intervening directory in this invocation; those two classes are
verified separately below. `ThemePickerScreenshotIT` remains outside the normal
Surefire suite, as in the unfiltered run.

```powershell
mvn -B verify '-Dtest=com/benesquivelmusic/daw/app/**/Test*.java,com/benesquivelmusic/daw/app/**/*Test.java,com/benesquivelmusic/daw/app/**/*Tests.java,com/benesquivelmusic/daw/app/**/*TestCase.java' '-Dsurefire.failIfNoSpecifiedTests=false' '-DskipNativeBuild=true' '-Dsnapshots.autoBaseline=false' '-Dsnapshots.update=false'
mvn -B -pl daw-app -am test '-Dtest=DawLauncherTest,DawRuntimeTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DskipNativeBuild=true' '-Dsnapshots.autoBaseline=false' '-Dsnapshots.update=false'
```

The fourteen app-root cases passed with no skips, failures or errors
(`target/pr988-round2-app-root-tests.log`, `BUILD SUCCESS`). The two green app
selections cover the same **4,233 cases** as the unfiltered application suite;
per-class counts were compared to ensure that no case was omitted.

Final distinct local evidence therefore covers **14,392 cases: 14,349 passed,
43 skipped, zero outstanding failures or errors**. This combines the green
10,159 dependency-module cases from the last unfiltered attempt with the green
4,233 application cases above; it does not claim that one unfiltered command
completed successfully. Module totals are SDK 1,348 (two skips), acoustics 106,
core 8,676 (38 skips), FX 29, and app 4,233 (three skips).

Validation used Windows, Maven 3.10.0 and JDK 27+35 without `clean`. Native
rebuilding and `jpackage` were skipped; available existing native binaries were
used, and snapshot creation/updates were disabled. The 43 skips comprise six
unavailable/unregistered ASIO-shim cases, nine unavailable AAC-library cases,
thirteen unavailable Ogg/Vorbis-library cases, eleven Linux/POSIX-specific
cases, one opt-in strict DSP coverage check and three disabled cross-platform
snapshot cases whose reports cite Linux-golden/font-metric differences.
Remote Ubuntu CI was not rerun. No changes were staged, committed or pushed;
GitHub review discussions were not modified.

### PR 988 review — H1 recovery dialog summary, local resolution, 2026-10-26

[Review 5660245105](https://github.com/Ben-Esquivel-Music/java-digital-audio-workstation/pull/988#pullrequestreview-5660245105)
on commit `c01a897f` raised two findings, H1 (comment `4383435911`, High) and
M1 (comment `4383435942`, Medium), and
[CI run 40415778437](https://github.com/Ben-Esquivel-Music/java-digital-audio-workstation/actions/runs/40415778437)
failed on the same commit. This section and the two after it record their
local resolution, which the Goals and Non-Goals above call PR 988 round 3: an
implementation pass, a verification pass, and a fix round for what the
verification found. Java 27 agents made the code changes, and the studio
engineer ruled every design question and every verification finding. The
`javafx-application-design` skill (§11 "Threading", §14 "Accessibility,
internationalization, and quality", §15 "Anti-patterns to call out"), the
`research-daw` skill (lesson 1 "Modular Architecture (SDK + Core + App)") and
the Persistence Integrity design book's §3.2, §4.7, §5.3, §5.5, §6.1, §6.2 and
§6.4 shaped the changes. `daw-core` reads the recovery evidence and computes
its facts on the open's virtual-thread worker; `daw-app` words those facts:
the dialog's rows and sentences, on the FX thread, and the open announcement
come from `Messages.properties`, while the Restore checkpoint only refusal,
the copy-failure text and the journal-start failure are still literals in
code (story 382's extension moves them to the bundle); and the failures this
round adds reach the user through surfaces that already existed: the recovery
dialog, the failed-replay ERROR, the open announcement and `Open failed: …`.

H1 found that the dialog's summary came from `RecoveryScanner.scan`, which named
the newest checkpoint on disk and counted every journal record, while Recover
everything replayed from the verified base after its capture boundary. The
dialog now describes what each choice does:

- **One plan per gated open.** `OpenRecoveryGate.run` builds one `RecoveryPlan`
  (`RecoveryScanner.plan`) on its virtual-thread worker, after the read-only
  return, so the gate still reads and changes no recovery evidence on a
  read-only open. The dialog
  renders `plan.summary()`, and Recover everything executes that same plan
  instance. Replay re-checks the recovery point's SHA-256 against the bytes it
  then deserializes and re-reads every segment strictly; the promotion of an
  interrupted save re-checks the temp's size and last-modified time
  (`RecoveryPlan.NewerHead.readUnchanged`). A mismatch takes the existing
  `ReplayFailed` path: nothing is written, the evidence is kept, and the last
  clean save opens read-only. `RecoveryScanner.scan` and
  `RecoveryResult.summary()` were removed.
- **The recovery point is replay's.** The plan picks it as replay does: the
  highest hash-verified checkpoint capture, else the session base its
  `$session-start` certifies, else, for an older version's journal with no
  provenance, `project.daw`, else the newest checkpoint. Its changes are the
  mutation records after that point's capture boundary. Session records
  (`$session-start`, `$checkpoint`, `$session-seal`) are never counted as
  changes; the technical detail lists them on its "Session records:" row.
- **Rows and explanation.** The rows are, in order, "Last clean save:"
  (`project.daw`'s time, the file Restore checkpoint only opens), "Recovery
  point:" (the file Recover everything starts from and its time, the
  interrupted save it promotes, or "Unavailable: <reason>"), "Changes after it:"
  and "Last journaled event:". The label "Unsaved work to replay:" is retired.
  Below the rows, an explanation joins independent sentences in a fixed order,
  each only when it applies: the head relation, one sentence on what Recover
  everything does, the changes it will not rebuild, one sentence on what
  Restore checkpoint only does, that a checkpoint recovery point is not
  deleted, and what happens to an interrupted save. Every label, figure,
  sentence and Inspect events body this round added or changed is a
  `recoveryDialog.*` or `inspectEvents.*` key in `Messages.properties`,
  formatted at `Locale.ROOT` with plural-aware counts shown without grouping;
  times use `yyyy-MM-dd HH:mm` in the system zone. The title ("Recover unsaved
  work"), the header ("This project did not close cleanly"), the four button
  labels with their `ButtonData`, the result converter and Inspect events'
  "Captured events" title are unchanged literals.
- **The head relation.** For every journal recovery point the plan compares
  `project.daw` with it: IDENTICAL when their SHA-256 digests match, HEAD_OLDER
  only when `project.daw` was last modified strictly before the recovery point
  was made, HEAD_NEWER otherwise, and MISSING when there is no `project.daw`.
  HEAD_NEWER leads the explanation with "The last clean save was made after the
  recovery point: Recover everything replaces it with an older version of the
  project." IDENTICAL, with nothing rebuilt, leads with "The recovery point is
  identical to the last clean save, so both choices open the same project."
- **Journaled changes are not rebuilt.** This is story 298's replay limit,
  unchanged: no event family has a delta replay can apply (the delta map in
  `JournalReplayer` is empty), so Recover everything yields the recovery point.
  The summary's rebuilt count uses the same per-type rule replay applies
  (`JournalReplayer.rebuilds`). Whenever changes are not rebuilt, the dialog
  says "This version cannot rebuild journaled changes, so the N changes
  summarized under Inspect events… will not be in the recovered project.", and
  no sentence says that Recover everything replays changes. After such a
  replay the open announcement is the WARNING "Recovered the project from its
  recovery point; the N journaled changes after it could not be rebuilt" ("the
  journaled change after it" for one) rather than a SUCCESS that counts them as
  recovered; the gate takes that count from the same plan
  (`OpenDoor.JournalReplay.notRebuilt`). A promoted interrupted save is
  announced at SUCCESS as "Recovered an interrupted save", never as zero
  journal changes.
- **Restore checkpoint only opens `project.daw`, not the recovery point.** The
  explanation says so, for example "Restore checkpoint only opens the last
  clean save and deletes the journal.", or that it keeps the journal when
  journaled persistence is off. When there is no `project.daw`, the dialog says
  "Restore checkpoint only cannot open this project because project.daw is
  missing; the journal is kept if you choose it.", and the gate refuses that
  choice before deleting anything: the user reads "Open failed: project.daw is
  missing, so Restore checkpoint only cannot open this project; the journal was
  kept".
- **An interrupted save is never deleted without a copy.** An interrupted save
  is a complete atomic-writer temp of `project.daw` that is newer than it.
  Before a choice deletes it without making it the project, the gate keeps it,
  before deleting anything, through
  `SnapshotStore.createSnapshotFromFile(temp, <project>/snapshots, "Interrupted save")`:
  its file is `snapshots/<yyyyMMdd-HHmmss>-interrupted-save.daw` (with a
  `-<n>` suffix when that name is taken); `SnapshotStore` names it
  "interrupted save" ("interrupted save <n>" with the suffix), the Snapshots
  browser lists it by that file name and its size, and the gate logs the
  temp's path and the snapshot's.
  Restore checkpoint only keeps it, alone or beside a journal. Recover
  everything keeps it beside a journal whose replay can start (after the
  replay, before the commit), promotes it when no journal decision is owed
  (after backing `project.daw` up as `project.daw.recovered.<stamp>.bak`), and
  leaves it in place when the replay cannot start. The
  `recovered-head-<request-id>.daw` name is gone. A copy that fails aborts the
  decision with nothing deleted, and the user reads "Open failed: The
  interrupted save <temp name> could not be copied to <project>\snapshots
  (<reason>); nothing was deleted", where the reason follows the stage-reason
  rule (`JournalState.reason`: a `FileSystemException`'s reason, any other
  failure's message, or the failure's type when there is none), never a bare
  path; for example "(FileAlreadyExistsException)" when a file named
  `snapshots` is in the way. The explanation's last sentence
  says which choices keep it, in a file whose name contains
  "interrupted-save"; when the save sits beside a journal it also says when the
  save was written and that it is newer than the last clean save, and when the
  replay cannot start it adds that Recover everything leaves it in place.
- **A replay that cannot start is described before the choice.** The plan
  types the reason: the session's base is missing, it has changed, a journal
  segment is damaged (named), or there is no base to start from. A damaged
  segment is reported this way even when the recovery point itself verified.
  The rows read "Unavailable: <reason>" and "N changes (cannot be replayed)",
  and the explanation opens with "Recover everything cannot replay this
  journal: <reason>. If you choose it, the journal is kept and the last clean
  save opens read-only." If it is chosen anyway, the failed-replay ERROR names
  a damaged segment: "Recovery failed (journal segment failed checksum
  verification: <path of the segment>; recovery evidence was preserved;
  fallback is read-only); opened the last clean save instead". The other
  reasons still read "Cannot establish the session replay base" there (story
  385).
- **Inspect events** shows "N changes journaled after the recovery point; the
  journal has M segment files." and then the distinct change types, never a
  session record type; when the replay cannot start it says why, and for a
  promoted interrupted save it says that no journaled change is part of the
  recovery. Choosing it still cancels a gated open, as before.
- **The default button is an interim safety step.** When the summary is a
  replay whose last clean save is newer than its recovery point (HEAD_NEWER),
  Restore checkpoint only is the default (Enter) button and Recover everything
  is not, so Enter never replaces a newer save with an older project; in every
  other case Recover everything stays the default. The labels, their
  `ButtonData`, the title and the header are unchanged. The step is interim:
  Recover everything still replaces a newer save when it is chosen, and story
  387 retires the rule once it cannot.

The rebuilt count's shared rule is checked by code review only: while no family
has a delta, no test can tell `RecoveryPlan`'s use of `JournalReplayer.rebuilds`
apart from a hard-coded 0. Story 387 adds the test that a type with a delta
counts as rebuilt.

#### Known limits

These are categories, each named with examples; none is a closed list.

- **Replay fidelity.** No journaled change is rebuilt in this version, for
  example a mixer change journaled after the recovery point, so Recover
  everything yields the recovery point; the dialog and the announcement say so
  (story 387).
- **Recovery prompts after a session that saved its work.** Detection owes a
  decision for any change journaled after the highest checkpoint marker's
  capture boundary, sealed or not, and with autosave off a save writes no
  marker. So, for example, an autosave-off session that saved its edits reopens
  into the recovery dialog after a project switch, an in-app close or a quit.
  The interim default button keeps Enter from replacing that save (stories 387
  and 389).
- **The literal title and header.** "Recover unsaved work" and "This project
  did not close cleanly" are shown even after a sealed close that still owes a
  decision (the story 382 extension).
- **Duplicate copies after a refused commit.** A Recover everything whose
  commit is refused, for example because `project.daw` is read-only, keeps the
  journal and any interrupted save, so each retry adds another
  `project.daw.recovered.*.bak` and, when an interrupted save sits beside the
  journal, another identical "Interrupted save" snapshot. Nothing is lost
  (story 383).
- **The ungated, test-only recovery path.** Production always installs the
  gate (`MainController` calls `enableRecoveryGate`), but the path used without
  it shows the new dialog while acting on its own reading of the evidence: for
  example, its Restore checkpoint only deletes `journal/` before a load that
  may find no `project.daw`, and its failed replay opens the last clean save
  writable (story 384).
- **Evidence that cannot be read before the dialog.** For example, an older
  version's journal with no `project.daw` whose newest checkpoint cannot be
  read, or a complete head temp beside a missing `project.daw` (story 390): the
  open fails with `Open failed: …` before any dialog, and nothing is deleted.
- **The read-only-fallback wording.** The failed-replay and refused-write
  ERRORs give no next step, the base reasons other than a damaged segment
  still read "Cannot establish the session replay base", and, for example, a
  `project.daw` link to a file that is not a project opens read-only as an
  "Untitled" project that the ERROR calls the last clean save (story 385).

#### Evidence

The fix round's final targeted run on Windows (JDK 27+35, Maven 3.10.0) was one
Maven process over 35 test classes, the 29 classes of the snapshot the
verification pass reviewed plus six more. It passed 362 tests with no failures
or errors; the one skip is `JournalSessionRecoveryTest`'s POSIX-only T1 (M1
entry below). The counts come from that run's fresh surefire XML. Among them,
`RecoveryPlanTest` (33 tests) pins the plan, the head relation, the typed
reasons, the rebuilt count and replay's re-checks; `OpenRecoveryGateTest` (28)
pins one plan per open, the interrupted-save snapshot and its failed copy,
Restore checkpoint only without `project.daw`, evidence changed after the
choice, and a replay that cannot start because the session base is missing or
changed or a segment is damaged; `RecoveryDialogOptionsTest` (34),
`RecoveryDialogStringsTest` and `RecoveryInspectEventsTextTest` pin the rows,
the sentences, the default button and the bundle values; and
`OpenAnnouncementTest` and `EveryOpenDoorRunsRecoveryFirstTest` pin the
announcements, including a temp-only Recover everything through the gate.
Each of these fault checks turned its test red on Windows and was then
restored byte for byte: the default-button swap removed; one message for every
reason a replay cannot start; the announcement built without the not-rebuilt
count; a head relation that calls a save made at the recovery point's own
instant older; the plan built before the read-only return; and the temp-only
announcement mapped back to a journal replay.

On Linux (WSL Ubuntu, Temurin 27+35, Maven 3.9.12, on a copy of the tree), the
snapshot the verification pass reviewed passed twice: 293 tests in 29 classes,
none failing, erroring or skipped. The fix round's code passed there twice
too: 362 tests in 35 classes, none failing, erroring or skipped, with T1
running; and the plan built before the read-only return turned
`OpenRecoveryGateTest` red there as well (`IOException: Is a directory`). A
final pass after a second verification then tightened two tests' assertions
(the failed copy of an interrupted save, and the segment test of the M1 entry
below), added a null check to `RecoveryScanner`'s evidence record and
corrected Javadoc. That final code passed the same 35 classes on Windows (362
tests, the one skip T1) and twice on Linux (362 tests, none failing, erroring
or skipped, with T1 and the segment test's POSIX branch running).

### PR 988 review 4383435942 (M1) — local resolution, 2026-10-26

M1 found that when the session base's hash could not be read after
`JournalWriter.start()` had started the writer thread,
`ProjectJournalCoordinator.openFor` dropped the failed writer without closing
it, leaving the thread and its open segment behind. A failed start now
releases what it acquired and removes the journal evidence it can, and the
coordinator ends whatever it started:

- **A gated disk open whose journal cannot start is still refused**, through
  `openForRequired`, which throws an `IllegalStateException`, "Journaled
  persistence could not start for <project folder>: <reason>", with the
  failure as its cause; the user reads it after "Open failed: ". Opening
  writable with journaling paused instead is story 386.
- **A leftover session base is replaced, not repaired.** `start()` first
  refuses any non-empty prior segment and leaves it untouched. Only then is a
  leftover `journal/session-base.daw` deleted, never recursively: a non-empty
  directory refuses, an empty one is removed like a file, and a link goes as a
  link. The fresh base gets the directory's default permissions, so no odd
  bits carry over.
- **The base is verified before the session starts.** The `$session-start`
  certifies the SHA-256 of the head's bytes as read into memory; the published
  base is read back once, with no retry, and compared before the first segment
  opens.
- **`$session-start` is durable before the writer thread exists.** It is
  appended and fsynced on the thread that starts the journal (a worker such as
  the recovery worker or the New Project virtual thread, not the FX thread), so
  a failing project drive is reported when the project opens.
- **A failed start leaves a closed writer and removes what it made.** It holds
  no channel and no thread, discards anything buffered, deletes the segment it
  opened, and deletes the base it wrote and verified only once that segment is
  confirmed gone, so a `$session-start` never outlives its base; delete
  failures are attached as suppressed. What it cannot remove is inert: a
  segment it could not delete keeps the base beside it, and a base that failed
  its read-back is certified by no record. A start refused by a prior session's
  evidence touches neither that journal's segments nor its base. Afterwards
  `offer` and `start()` throw `IllegalStateException`, and `close()` and
  `sealAndClose` do nothing. A `close()` that runs during a start either joins
  the writer thread that start published or, when it comes first, makes the
  start fail (with "Journal writer is closed", or with the start's own I/O
  failure when the close released the segment before the `$session-start`
  record was written), so no writer thread outlives the close: the start
  publishes the thread, and `close()` takes it, under the writer's monitor. A
  `sealAndClose` that finds no writer thread (the writer never started, or its
  start is still running) has no session to seal: it is a `close()`, so it
  writes no record and a start still running fails the same way. The
  coordinator never seals such a writer, because it starts and closes journals
  under one lifecycle lock and publishes a writer only after its start has
  finished. An enabled writer accepts records only after its `start()` has
  published the writer thread: an earlier `offer` (before any start, or while
  one is still running) throws `IllegalStateException`, so a `close()` or
  `sealAndClose` never writes a record into a segment that does not exist or
  that a failing start deletes; either `onCheckpointSucceeded` on an enabled
  writer that has not started throws the same exception and changes nothing.
- **A recorder that cannot attach ends the session it started.** After a
  successful `start()`, a recorder that fails to attach to the bus, with a
  `RuntimeException` or with an `Error` (which is then rethrown), makes the
  coordinator seal the session with `sealAndClose`, so the next open offers no
  recovery for a session that recorded nothing. If the seal fails, a WARNING
  is logged and the seal failure is attached to the original one. Under
  `openForRequired` the caller's "Open failed: …" is the one surface; `openFor`
  still shows "Journaled persistence could not start: <message>".
- **A reason is never a bare path.** The `daw-core` stage messages, which
  already end with the file, give their reason through `JournalState.reason`
  (now public): a `FileSystemException`'s reason, any other failure's message,
  or the failure's type when there is none, never the path; this is the rule
  of daw-app's `RecordCoordinator.ioReason`. The coordinator's top-level reason
  keeps the path: the failure's message, with the type added for a
  `FileSystemException` that gives no reason, or the type alone when there is
  no message.
- **New Project says what happened.** When its journal cannot start, the
  ERROR is "New project created; journaled persistence is paused until this
  project is reopened. " followed by the reason, and the SUCCESS "New project
  created" is withheld.

The user-facing texts, exactly:

- `The journal's session base cannot be read back (<reason>), so a crash in this session could not be recovered: <base path>`
- `The journal's session base does not read back as written, so a crash in this session could not be recovered: <base path>`
- `The journal could not write its first record to disk (<reason>): <segment path>`
- `Journaled persistence could not start for <project folder>: <reason>`, read
  after `Open failed: `, for example `Open failed: Journaled persistence could
  not start for D:\Sessions\Song: The journal's session base cannot be read
  back (AccessDeniedException), so a crash in this session could not be
  recovered: D:\Sessions\Song\journal\session-base.daw`; the coordinator's own
  reasons are `no event bus is available` and `a journal is already open for
  <project folder>`
- `New project created; journaled persistence is paused until this project is reopened. <reason>`
- `Journaled persistence could not start: <message>`, from `openFor` only
- `Journal writer is closed`, for developers only

`JournalSessionRecoveryTest` covers a `$session-start` that cannot be made
durable (through the `Syncer` seam), a start that fails before its segment
opens, a closed writer that cannot start, a prior session's evidence left
untouched, a segment that cannot be deleted
(`aFailedStartWhoseSegmentCannotBeDeletedKeepsTheBaseItWrote`), a close during
a start parked after its fsync
(`aCloseThatRunsWhileAStartIsParkedAfterItsFsyncMakesTheStartFailAndLeavesNoWriterThread`)
and the stage-reason rule
(`aStageReasonIsTheFileSystemReasonOrTheMessageOrTheTypeAndNeverThePath`).
`ProjectJournalCoordinatorTest` pins an obstructed journal directory's message
and both recorder-failure surfaces: under `openForRequired` no notification of
the coordinator's own, and under `openFor` exactly one ERROR, "Journaled
persistence could not start: EventBus is closed", with the session sealed.
`ProjectReplacementJournalSafetyTest` pins the New Project ERROR.

T1, `aSessionBaseLeftWriteOnlyIsReplacedByOneTheReplayCanReadAndVerify`, is
POSIX-only: it assumes POSIX permission bits and a base its user cannot read,
so it is skipped on Windows. It ran and passed in every Linux (WSL) run of this
round, and the GitHub CI's Ubuntu job runs it.

The rule that a failed start keeps its base while its segment exists is pinned
by `aFailedStartWhoseSegmentCannotBeDeletedKeepsTheBaseItWrote` on both
branches. On Windows it makes the segment undeletable with the DOS read-only
flag, and deleting the base anyway deletes it. On POSIX it removes write
permission from the journal directory, and deleting the base anyway attaches a
second suppressed failure where the test allows exactly one, the segment's.

These are checked by code review only; no shipped test reaches them or tells
them apart from a broken version:

- `JournalState.saveBase`'s read-back branches, unreadable and mismatched (the
  unreadable branch was seen red only in a fault the manager ran on a local WSL
  copy, before its text was reworded);
- `endUnrecordedSession`'s fallback to `close()` when the seal fails;
- the coordinator's `Error` branch after a successful start;
- `JournalSegment.open` closing its channel when `ch.size()` or the
  constructor throws;
- the coordinator's `close()` after a failed start;
- the shared rebuilt rule (H1 entry above).

Evidence: the final Windows run of the H1 entry passed these classes. In a
separate fault batch on Windows, three fault checks turned their tests red
and were restored: deleting the base while its segment exists, dropping the
close-during-start re-check, and swapping the two recorder-failure surfaces.
On Linux (WSL), deleting the base while its segment exists turned the POSIX
branch red too. On Linux (WSL, as above), the implementation pass's M1 change
passed twice, and the fix round's `daw-core`, journal-coordinator and test
changes passed three times (140 tests in 15 classes), with T1, the
close-during-start test and the segment test's POSIX branch running, not
skipped.

The close-out pass (2026-10-27) added six `JournalSessionRecoveryTest` cases
for how a writer whose thread does not exist answers a seal, an offer or a
checkpoint (the failed-start bullet above), and for a disabled writer's
offer: a seal that runs while a start is parked after its fsync, and a seal
of a writer that never started
(`aSealThatRunsWhileAStartIsParkedAfterItsFsyncSealsNothingAndMakesTheStartFail`,
`aSealOfAWriterThatNeverStartedWritesNoRecordAndClosesIt`); an offer refused
before the writer thread exists, with no start and with a start parked after
its fsync
(`anEnabledWriterThatNeverStartedRefusesRecordsAndClosesWithoutTouchingTheDisk`,
`anOfferWhileAStartIsParkedAfterItsFsyncIsRefusedAndOnlyLaterRecordsReachTheSession`);
a disabled writer that answers a record offered to it directly with
`DISABLED` (`aDisabledWriterReportsARecordOfferedDirectlyAsDisabledWithoutStarting`);
and a refused checkpoint that publishes no queued count and takes no sequence
number (`aCheckpointReportedToAnEnabledWriterThatNeverStartedIsRefusedAndChangesNothing`).
On Windows four fault checks turned these tests red, each restored byte for
byte afterwards:
`sealAndClose` without its no-thread branch (and with its earlier null-checked
join back) made the parked seal wait for the start and the never-started seal
throw a `NullPointerException` (F-S); the offer guard deleted turned both
offer tests red (F-O1); the offer guard moved above the disabled return turned
the disabled-writer test red (F-O2); and both checkpoint checks moved after
the queued count is published turned the refused-checkpoint test red (F-O3).
One Windows Maven run of twelve journal and recovery classes in `daw-core` and
`daw-app` then passed 153 tests with no failures or errors, the one skip T1,
and the full-suite validation at the end of this story ran this code on
Windows and on Linux.

### CI 40415778437 — local resolution, 2026-10-26

[CI run 40415778437](https://github.com/Ben-Esquivel-Music/java-digital-audio-workstation/actions/runs/40415778437)
(Ubuntu, `xvfb-run --auto-servernum mvn -B clean verify`) failed two of the four
`AppWritersFollowAProjectFileLinkTest` cases on commit `c01a897f`: both journal
replay tests found a publish of `checkpoints/.checkpoint-order` they did not
expect. On Windows all four passed. At `c01a897f` both tests called the
ungated, test-only `replayJournal` directly, and its fallback after a refused
write opens the last clean save writable.

Production code is unchanged by this fix. A writable recovered open may revoke
a ghost certification in `checkpoints/.checkpoint-order` while its checkpoints
seed, on `daw-checkpoint-writer`, in no fixed order with the open's
installation; a read-only fallback starts no checkpoint generation and revokes
nothing. The tests now say so:

- both journal replay tests drive the production recovery gate:
  `enableRecoveryGate`, `beginRecovery`, and a `Window` listener that answers
  the one recovery dialog "Recover everything", captures any failure for the
  test to rethrow, and is removed in `finally`;
- the refused-link test asserts that the last clean save opened read-only,
  that no checkpoint generation started and no journal opened, and only then
  that nothing was published; the segment, the verified base and the ordering
  fact, with its ghost certification, stay byte-identical;
- the success test captures the outgoing project's checkpoint generation before
  `beginRecovery`, awaits `CheckpointEvent.Seeded` for a newer generation
  (`SaveFlowFixture.awaitCheckpointsSeeded`), and only then asserts the
  complete typed write sequence: the `project.daw.recovered.*.bak` backup and
  the recovered `.daw` file on the gate's worker, then the ordering fact on
  `daw-checkpoint-writer`, none on the FX thread;
- `SaveFlowFixture.currentProjectIs` reads the current project once, so the
  installation waits in this class and three others cannot dereference a
  project the gate's worker has just cleared.

The success test's announcement expectation changed with the H1 fix round: it
now expects one WARNING, "Recovered the project from its recovery point; the 3
journaled changes after it could not be rebuilt", and still no ERROR.

Evidence: at `c01a897f` the two tests failed in three of three runs on Linux
(WSL), at the same lines and with the same messages as the CI log; with this
fix's test changes, before that announcement change, all four passed in five of
five runs there. On Windows, four fault checks turned the tests red: the
read-only fallback replaced by a writable lease that does not run the gate
again, seeding that skips the ordering-fact compaction, the success test
without the seeding wait (so the wait is load-bearing on Windows too), and a
gate that clears the journal after a refused commit. The verification pass's
prober also turned the success test red by letting the outgoing generation's
`Seeded` satisfy the wait, and kept all four tests green with the checkpoint
writer's seeding compaction delayed by 1.5 s, which emulates the Linux ordering
on Windows and is not a Linux run. After the announcement change the class
passed 4 of 4 in the final Windows run of the H1 entry and in each of two
Linux (WSL) runs of the fix round's code.
Confirmation on the GitHub CI's Ubuntu job needs the user's push and a new CI
run; it is not recorded here.

Full-suite validation of round 3 (the H1, M1 and CI 40415778437 entries
above) ran the whole default reactor on the final tree, the close-out pass
included, three times: once on Windows and twice on Linux. Every run used
this command, which has no test selector and no `clean`:

```
mvn -B verify -Dmaven.test.failure.ignore=true -DskipNativeBuild=true -Dsnapshots.autoBaseline=false -Dsnapshots.update=false
```

With `-Dmaven.test.failure.ignore=true` a failing test does not fail the
build, so each run's result is its test counts, not its `BUILD SUCCESS`.

On Windows (Windows 10, Maven 3.10.0, Temurin JDK 27+35, in the working tree
itself, from the repository root), the run started at 02:59:42 on 2026-10-27
and finished at 03:13:32 (13:48 min) with `BUILD SUCCESS`, all six reactor
modules SUCCESS, and the `daw-core` and `daw-app` JARs rebuilt (03:08:05 and
03:13:12). Counted from the `TEST-*.xml` files newer than the run's start (the
console totals agree; one older `daw-core` report, from a deleted verifier
probe test, predates the run and is not counted), it ran **14,498 tests in
1,503 suites: 14,454 passed, 44 skipped, zero failures and zero errors**. By
module: SDK 1,348 tests (two skipped), acoustics 106, core 8,722 (39
skipped), FX 29 and app 4,293 (three skipped), each with zero failures and
zero errors. The 44 skips are, by category (class counts from the XML): ASIO
shim and driver cases (`AsioFormatChangeShimTest` 2,
`AsioEngineStreamingIntegrationTest` 1, `AsioStreamingIntegrationTest` 1);
native codec libraries that are not present (`NativeLibraryDetectorTest` 2,
`OggVorbisFileReaderTest` 4, `OggVorbisExporterTest` 8, `AacExporterTest` 8,
`DefaultAudioExporterTest` 2); POSIX-only persistence cases
(`AtomicFileWriterTest` 7, `JournalSessionRecoveryTest` 1, which is the M1
entry's T1, `ProjectLockManagerTest` 1, `SessionManagerTest` 1) and cases
disabled on Windows (`ProjectPathsTest` 1,
`ProjectRelativePersistenceContractTest` 1); the opt-in strict DSP coverage
check (`DspRegressionCoverageTest` 1); and three snapshot tests whose goldens
are captured on Linux CI (`MasteringViewSnapshotTest`,
`MixerViewSnapshotTest`, `TelemetrySetupPanelSnapshotTest`).

On Linux (WSL Ubuntu, Maven 3.9.12, Temurin JDK 27+35, the JavaFX Headless
platform the build configures), both runs used one WSL-native LF copy of HEAD
`c01a897f` plus every changed code file; the 38 changed code files were
checked hash-equal to the Windows tree after stripping CRs.

- **The CI-equivalent run** was set up the way GitHub's Ubuntu runner is: no
  ALSA default PCM (`ALSA_CONFIG_PATH` pointed at an empty file, and
  `PULSE_SERVER` unset), and the home folder's file cache kept warm
  (`find ~ -mindepth 2 -maxdepth 2` before the run and every 15 s during it,
  because the runner's home folder is nearly empty). It ran from 02:53:54 to
  02:59:25 on 2026-10-27 and ended with `BUILD SUCCESS`, all six modules run
  and the `daw-app` JAR rebuilt (02:59:17): **14,497 tests: 14,444 passed, 53
  skipped, zero failures and zero errors**. By module: SDK 1,348 (two
  skipped), acoustics 106, core 8,722 (45 skipped), FX 29 and app 4,292 (six
  skipped).
- **The as-is run**, in this host's own environment, ran from 02:47:15 to
  02:53:54 on 2026-10-27 and ended with `BUILD SUCCESS`, all six modules run:
  **14,497 tests: 14,438 passed, 53 skipped, five failures and one error**. By
  module: SDK 1,348 (five failures, two skipped), acoustics 106, core 8,722
  (45 skipped), FX 29 and app 4,292 (one error, six skipped); acoustics, core
  and FX had no failures or errors, and the app no failures.

The app count is 4,292 on Linux and 4,293 on Windows because
`ActiveProjectAliasTest` runs four cases on Windows and three on Linux, an
OS-dependent count in a class this round does not change. Core's 45 skips on
Linux are native codec and driver libraries not built in WSL (for example the
MP3, Ogg and AAC exporters and reader, `DefaultAudioExporter`,
`NativeLibraryDetector` and ASIO), seven cases disabled on Linux and the
opt-in strict DSP check; the SDK's two and the app's six skips are the same
counts as in the unchanged-HEAD control below and in GitHub's Ubuntu CI run
on `c01a897f`.

The six cases that did not pass in the as-is run passed in the CI-equivalent
run, and neither cause is this change. A control run of unchanged HEAD
`c01a897f`, in a separate WSL copy with the same JDK and flags
(`mvn -B -pl daw-app -am test -o -Dmaven.test.failure.ignore=true …`), fails
the same six and also the CI 40415778437 pair this round fixes: SDK 1,348
(five failures, two skipped), acoustics 106, core 8,676 (45 skipped), FX 29
and app 4,232 (two failures, `AppWritersFollowAProjectFileLinkTest` :173 and
:143; one error; six skipped). GitHub's Ubuntu CI ran both classes green on
`c01a897f`.

- The five `JavaxSoundBackendTest` failures (lines 1030, 1427, 1648, 1323 and
  1004) come from this host's ALSA configuration: its default PCM, served by
  WSL's sound server, accepts the 192-channel open these five cases expect
  every host to refuse; they build `new JavaxSoundBackend()`, so their open
  of the default device reaches the host's real one. With no default PCM
  (`ALSA_CONFIG_PATH` pointed at an empty file, as on GitHub's Ubuntu runner)
  the class passes 57 of 57 on its own, and the CI-equivalent run had no SDK
  failures. This is a test-hermeticity defect in a class this pull request
  does not change; future story 391 owns it.
- The error, `BrowserTabIndicatorTest.activeTabHasAccentUnderBarPreviousDoesNot`
  (a `NullPointerException` at line 90), comes from a pre-existing production
  defect: `BrowserPanel`'s constructor scans the user's home folder on the FX
  thread, against the javafx-application-design skill's §11 "Threading". This
  WSL home folder has 201 folders and 3,903 entries one level below them; with
  a cold file cache the scan takes about 24 s, past the 5-second wait of the
  test's FX helper, which ignores the timeout and returns no result. With a
  warm cache the scan takes 15 ms, and the test passed in the CI-equivalent
  run. Neither `BrowserPanel` nor the test is changed by this pull request;
  future story 392 owns the defect.

The net effect on Linux is that the CI pair goes green, core gains 46 tests
and the app 60 (this round's new tests), and no failure is new.

Native rebuilding (`-DskipNativeBuild=true`) and `jpackage` were skipped, and
snapshot creation and updates were disabled. Remote GitHub CI (Ubuntu) was not
rerun; that needs the user's push. Nothing was staged, committed or pushed,
and no GitHub review discussion was modified.

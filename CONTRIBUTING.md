# Contributing

Thank you for your interest in contributing to **java-digital-audio-workstation**.

## Building and testing

This project targets **Java 26+** and **Apache Maven 3.9.14**. Tests that
exercise JavaFX UI classes (e.g. `daw-app`) require an active display.
On headless systems use `xvfb-run`:

```bash
xvfb-run -a mvn verify
```

### Slow test classes

A new test class that takes 1.5 seconds or more in its surefire report
on the reference machine, with no other build running, carries
`@Tag("slow")` (`org.junit.jupiter.api.Tag`) on its class declaration,
and so does any class that a run there lists at 3 seconds or more. The
margin between the two lines keeps the reports of a default build free
of classes at 3 seconds or more: class times vary from run to run, so an
untagged class that a later run lists between the lines needs no tag.
Decide a new class's tag from a run on an otherwise idle
machine, because class times rise by half or more while
another build runs. The default build leaves these classes out, so a
full local build is shorter. On the reference Windows machine on
2026-11-27 the default build took 05:51, against 22:38 for every test in
one run. The slow classes alone took 19:02 there.

```bash
mvn verify                 # every test class except those tagged slow; make test
mvn -Pslow-tests verify    # only the classes tagged slow; make slow-tests
mvn -Pall-tests verify     # every test, as CI runs it; make all-tests
```

The default build and the slow run together run every test once. A
class named with `-Dtest=` runs whether or not it is tagged slow. The
`long-tests` and `dsp-rebaseline` profiles apply no tag filter, so each
runs what it ran before the tag existed.

CI runs every test, the classes tagged `slow` included, on every pull
request, and `main` is landed only from a green CI run. That run is on
Linux. A few slow classes also test paths that exist only on Windows,
for example a folder reached through a junction or an administrative
share, a copy of a project folder that kept its name and creation time
and carries this session's own lock, a delete refused by a sharing
violation, or a file made unreadable by an ACL. No CI job runs them:
`windows-asioshim.yml` runs only for changes to its own paths or when
started by hand, and skips its build while its SDK secret is unset.
Before you push a change listed under "When to run the slow classes
locally", run the slow classes on Windows, or say in the pull request
that you did not.

#### When to run the slow classes locally

Run the slow classes (`mvn -Pslow-tests verify`; `make slow-tests` on
Linux) before pushing changes that touch:

- capture and take writing
  (`daw-core/src/main/java/com/benesquivelmusic/daw/core/recording/`,
  `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/recording/`,
  and `RecordCoordinator.java` and `TransportController.java` in
  `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/`),
- the transport
  (`daw-core/src/main/java/com/benesquivelmusic/daw/core/transport/`),
- project files on disk: save, Save As, the project lock, checkpoints,
  relink, the session journal and recovery
  (`daw-core/src/main/java/com/benesquivelmusic/daw/core/persistence/`,
  `journal/` included),
- exit, close and open in the application (`ExitCoordinator.java`,
  `ProjectFolderCloser.java`, `ProjectLifecycleController.java`,
  `SaveAsFlow.java`, `OpenRecoveryGate.java` and
  `SessionJournalEnding.java` in
  `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/`;
  `daw-app/src/main/java/com/benesquivelmusic/daw/app/DawApplication.java`;
  `daw-app/src/main/java/com/benesquivelmusic/daw/app/logging/`),
- the audio engine, devices and the clock
  (`daw-core/src/main/java/com/benesquivelmusic/daw/core/audio/`,
  `daw-sdk/src/main/java/com/benesquivelmusic/daw/sdk/audio/`, and
  `DefaultAudioEngineController.java`, `DeviceLossReactions.java`,
  `EngineHealthFeed.java` and `ClockStatusIndicator.java` in
  `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/`),
- metering and export
  (`daw-core/src/main/java/com/benesquivelmusic/daw/core/analysis/`,
  `daw-core/src/main/java/com/benesquivelmusic/daw/core/export/`),
- a test helper or fixture that a class tagged `slow` uses,
- the test selection: the surefire configuration or profiles in
  `pom.xml` or a module's `pom.xml`, or the `mvn` line of
  `.github/workflows/ci.yml`. After such a change, also check that the
  CI test total did not drop.

### Visual regression / snapshot tests

`daw-app` ships visual-regression tests that render JavaFX views into
PNGs and compare them pixel-by-pixel against committed *golden* images
under `daw-app/src/test/resources/snapshots/<TestClass>/<method>[.<theme>].png`.

Snapshots cover `ArrangementCanvas`, `MixerView`, `EditorView`,
`TelemetrySetupPanel`, and `MasteringView` in each of the bundled
themes (`dark-accessible`, `light-accessible`, `high-contrast`).
The infrastructure lives under
`daw-app/src/test/java/com/benesquivelmusic/daw/app/ui/snapshot/` —
extend `FxSnapshotTest` to add new snapshot tests.

The default tolerance is **≤ 0.5 % differing pixels with a per-channel
Δ ≤ 4**, which absorbs subpixel rendering noise without hiding real
regressions.

**Reference platform:** Linux + JDK 26 + JavaFX 26 with
`-Dprism.order=sw -Dprism.lcdtext=false` (configured in
`daw-app/pom.xml`). CI runs under `xvfb-run` on Ubuntu — the same
environment used to generate the goldens. Snapshots may differ on
macOS/Windows because of platform font rasterization and are not
expected to be cross-OS reproducible.

#### When a snapshot test fails

The test reports a per-snapshot diff message. The actual, expected,
and a red-highlighted diff PNG are written under
`daw-app/target/snapshot-failures/<TestClass>/`:

```
daw-app/target/snapshot-failures/MixerViewSnapshotTest/
  mixerWithTwoTracks.dark-accessible.expected.png
  mixerWithTwoTracks.dark-accessible.actual.png
  mixerWithTwoTracks.dark-accessible.diff.png
```

Open the diff PNG to see which pixels changed. If the change is an
**unintended regression**, fix the code — do not edit the golden.

#### Rebaselining (after an *intentional* UI change)

1. Inspect the diff to confirm the change is desired.
2. Regenerate the affected golden(s) with `-Dsnapshots.update=true`:
   ```bash
   xvfb-run -a mvn -pl daw-app test \
       -Dtest=MixerViewSnapshotTest -Dsnapshots.update=true
   ```
   Or, equivalently, delete the golden file(s) and rerun the test —
   missing goldens are auto-baselined.
3. Review the new PNG(s) (`git diff --stat -- daw-app/src/test/resources/snapshots`)
   and commit them alongside the code change.

To **disable** auto-baselining in CI (so missing goldens fail rather
than silently regenerate), set `-Dsnapshots.autoBaseline=false`.

## Code style

### Controller-class size: ~200-line soft cap

Controller classes (anything ending in `Controller`, `View`, `Service`,
`Dispatcher`, or `Handler` under `com.benesquivelmusic.daw.app.ui`) should
stay below a **~200-line soft cap**, not counting Javadoc and blank
lines. Hitting or exceeding the cap is a strong signal that the class
has accumulated multiple responsibilities and that one or more focused
collaborators should be extracted.

This is a **soft** cap, not a hard rule:

- Crossing 200 lines does **not** block a pull request.
- Crossing 200 lines **does** trigger a code-review note asking whether
  a Single-Responsibility-Principle extraction is in order.
- Genuinely cohesive code that legitimately exceeds the cap (for
  example, a single switch over a large sealed hierarchy) is accepted —
  reviewers and authors should explain in the PR description why no
  extraction is appropriate.

### Why a cap at all?

Large UI controller classes tend to become "dumping grounds" for
related-but-distinct concerns (construction, action dispatch,
enable-state, animation, hit-testing, …). Each unrelated concern in a
single class makes the class harder to test in isolation, harder to
reason about, and harder to change safely. The 200-line guideline is a
deliberately low threshold so reviewers can apply gentle pressure to
keep responsibilities focused as the codebase grows.

### Recommended cleavage patterns

Recent decompositions in this codebase illustrate the patterns that
work well:

- **`DawMenuBarController`** → `MenuConstructionService` (builds the
  menu hierarchy) + `MenuEnablementPolicy` (pure-logic mapping from
  project state to enable/disable flags) + `Host` callback (action
  dispatch).
- **`AnimationController`** → `IdleVisualizationAnimator`,
  `TransportGlowAnimator`, `ButtonPressAnimator`
  — the controller coordinates a single `AnimationTimer`; each animator
  owns one concern. (The former wall-clock time ticker was deleted by
  story 315 — the time display binds the transport's beats→time
  projection instead.)
- **`EditorView`** → `MidiEditorView` + `AudioEditorView` (delegated
  per active selection).
- **`ArrangementCanvas`** → `TrackLaneRenderer`,
  `ClipOverlayRenderer`, `ClipWaveformRenderer`,
  `ClipMidiPreviewRenderer`, `AutomationLaneRenderer`,
  `TransportOverlayRenderer`, plus separate handlers
  (`ClipInteractionController`, `ClipTrimHandler`, `SlipToolHandler`,
  …) for interaction.

### Constructor injection

New collaborators must be wired via **constructor injection**. Do not
introduce static singletons or service-locator patterns when extracting
a focused service. Tests then drive the collaborator with stubs/fakes
without touching global state.

The application's composition root is
[`DawRuntime`](daw-app/src/main/java/com/benesquivelmusic/daw/app/DawRuntime.java)
— a small hand-rolled class instantiated once during `DawApplication`
construction that owns the canonical long-lived collaborators (`ProcessorRegistry`,
`java.time.Clock`, `java.util.random.RandomGenerator`, …) and passes
them into downstream constructors.

When a collaborator's behaviour depends on **time or randomness**,
inject a `java.time.Clock` and/or `java.util.random.RandomGenerator`
through its constructor instead of calling `System.currentTimeMillis()`,
`Instant.now()`, `Math.random()`, or `new Random()` directly. Tests
then pass `Clock.fixed(...)` or a seeded `RandomGenerator` to make the
behaviour deterministic.

A few historical static singletons are being removed
incrementally — one per PR. See
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md#the-singleton-removal-migration)
for the migration pattern and current status.

### Extraction checklist

When extracting a focused service:

1. Identify a clear cleavage line — a group of fields plus the
   methods that read/write them, or a self-contained block of pure
   computation.
2. Move the block into a new class in the same package, accepting its
   dependencies via constructor.
3. Add at least one unit test that was **not possible before**
   extraction (because the prior class was too entangled to test in
   isolation).
4. Keep the extraction in **its own commit** — do not bundle multiple
   unrelated extractions in a single change.
5. Preserve behaviour: the existing public API of the original
   controller should keep working exactly as before unless the
   migration is explicit and documented.

## Commit and PR conventions

- One logical change per commit.
- PR descriptions list motivation, goals, and non-goals.
- Run the relevant module's tests under `xvfb-run` before pushing UI
  changes.

## Long-running render tests (`long-tests` profile)

End-to-end render and export tests live in a separate Maven profile so
they don't slow the per-PR feedback loop. They are kept under
`daw-app/src/test/long/` and compiled / run only when the `long-tests`
profile is active.

Run the suite locally:

```bash
mvn -Plong-tests verify
# or, equivalently:
make long-tests
```

The pipeline currently includes:

- 5-track full master render (`FullRenderLongTest`)
- 20-track batch stem export (`BatchExportLongTest`, story 186)
- DDP image export validation (`DdpExportLongTest`)
- ADM BWF export + re-import round-trip (`AdmBwfRoundTripLongTest`,
  story 170)
- Deliverable bundle export (`BundleExportLongTest`, story 181)
- Harness self-tests (`LongTestHarnessSelfTest`) — verify the
  harness cleans up temp directories and detects file-handle leaks.

Each long test declares an expected wall-clock budget via
`@LongRenderTest(budgetSeconds = …)`. The `LongTestHarness` extension
fails the test with a *performance-regression* message if the actual
wall clock exceeds **2 ×** that budget — so a slow change is caught
before it lands.

### When to run long tests locally

Run `make long-tests` before pushing changes that touch:

- the audio engine (`daw-core/src/main/java/.../audio/`),
- any exporter (`daw-core/src/main/java/.../export/`),
- the mastering chain (`daw-core/src/main/java/.../mastering/`),
- the bundle / stem rendering pipeline,
- the ADM BWF / object-based audio pipeline.

CI also runs `mvn -Plong-tests verify` automatically on every push to
`main` (workflow: `.github/workflows/long-tests.yml`) and on a daily
schedule (03:30 UTC). PRs do **not** trigger this workflow — keep the
fast loop fast.

### Updating golden files

Several long tests compare a rendered artefact byte-for-byte against
a golden file under `daw-app/src/test/long/resources/golden/`. When an
exporter intentionally changes byte layout, regenerate the goldens:

```bash
make rebaseline-long-tests
```

Then review the diff carefully — unintentional changes here are
exactly the regressions long-tests are designed to catch:

```bash
git diff daw-app/src/test/long/resources/golden/
```

Commit the regenerated goldens in the *same* commit as the code change
that caused the diff, with a short note in the commit message
explaining why the bytes shifted.

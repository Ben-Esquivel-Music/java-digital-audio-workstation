---
title: "Slow Test Classes Run on CI, Not in the Default Local Build"
labels: ["testing", "ci", "build"]
---

# Slow Test Classes Run on CI, Not in the Default Local Build

## Motivation

The full local build, `mvn -B -o -DskipNativeBuild=true verify` at the repository root, took 22:38 on the owner's Windows machine on 2026-11-27, and almost all of it is test time: 17,553 tests in 1,756 test classes, whose own times add up to about 1,237 seconds. The 96 classes that take 3 seconds or more hold 1,880 tests and about 832 seconds, 67% of the test time (54 in daw-app, 40 in daw-core, 1 in daw-sdk, 1 in daw-acoustics); the other 1,660 classes add up to about 406 seconds. The slowest are the recording, exit, save and engine-health tests, which hold real waits and timeouts: `CaptureMemoryFlatOverLongTakeContractTest` (daw-core, 57.5 s), `EngineHealthMonitorTest` (49.3 s), `ClockBadgeDuringATakeTest` (29.7 s), `ApplicationStopFallbackTest` (23.8 s) and `TakeDropoutsAndClockMarksTest` (21.4 s, the last four in daw-app). The same suite took 10:47 on the CI runner on `main` that day. The owner wants a local full build to be quick without deleting a test (decided 2026-11-27).

Today the local command and CI's command (`xvfb-run --auto-servernum mvn -B clean verify` on `ubuntu-latest`, `.github/workflows/ci.yml`) name no profile, so surefire selects the same test classes in both. No class under `src/test/java` carries a JUnit tag, and no surefire configuration names groups, includes or excludes. The profiles and switches that change that run today:

- The `long-tests` profile (root, daw-core and daw-app poms; story 209). The long render tests under `daw-app/src/test/long/java`, tagged `long` through `@LongRenderTest`, compile and run only under it. Under it daw-app compiles none of its `src/test/java` classes, the other four modules run their regular test classes and report into `target/long-test-reports/`, and daw-core sets `dsp.regression.strict=true`, which enables `DspRegressionCoverageTest.strictCoverage`.
- The `dsp-rebaseline` profile in daw-core keeps every test and switches the DSP regression tests to rewriting their goldens (owner-only).
- `ThemePickerScreenshotIT` is enabled only by `-Dtheme.screenshots=true`, and surefire's default name patterns do not match `*IT`; no module binds the failsafe plugin the root pom manages, so no build runs it unless it is named.

The other workflows: `windows-asioshim.yml` runs `mvn -B -DskipTests=false verify` on `windows-latest` when a file under its path filters changes or when started by hand, also with no profile; `long-tests.yml` runs `xvfb-run --auto-servernum mvn -B -Plong-tests verify` on pushes to `main` and daily, which runs 42 of the 96 slow classes (those outside daw-app); `release.yml` runs no tests.

## Goals

- The default local build, `mvn -B -o -DskipNativeBuild=true verify` at the repository root, does not run the slow test classes.
- A test class is slow when it takes 3 seconds or more on the owner's machine, as its surefire report gives it. Each slow class carries a mark on the class itself, so a reader of the class sees it.
- CI (`ci.yml`) still runs every test, as today.
- One command, documented in `CONTRIBUTING.md`, runs the slow classes on the local machine when someone wants them.

## Constraints

- No test is deleted or weakened, and none is changed beyond receiving its mark.
- No new third-party dependency.
- CI runs every test it runs today: the test total of the CI run is the same number before and after.
- The other workflows keep running what they run today: `windows-asioshim.yml` its whole default run, `long-tests.yml` its long render tests and the regular classes of daw-core, daw-sdk, daw-fx and daw-acoustics, `DspRegressionCoverageTest.strictCoverage` among them.
- The local default run and the slow-classes run together run every test exactly once.
- The mark, and whatever selects by it, does not reuse the `long` tag or the `long-tests` profile; the `long-tests` and `dsp-rebaseline` profiles run what they run today.
- This story makes no test faster; that is other work.

## Acceptance Criteria

1. On the owner's machine, the default local build finishes in under 10 minutes.
2. The default run's test total plus the slow run's test total equals the total of a full local run before the change, and equals the CI total, allowing only for tests that skip by operating system.
3. The CI log on the PR shows the same test total as the last `main` run.
4. A list produced from the surefire reports of a default run shows no class at 3 seconds or more.
5. The first runs of `long-tests.yml` and `windows-asioshim.yml` after the change show the same test totals as their last runs before it.

## Non-Goals

- Making any test faster, or changing its waits and timeouts.
- The per-test timeout in daw-app's `junit-platform.properties` and daw-app's fork bound, `surefire.timeout` (story 334, ruling R-T2).
- The long render tests themselves (story 209).

## Technical Notes

- Code locators: the root `pom.xml` (surefire in `pluginManagement` and `<plugins>`, failsafe in `pluginManagement`, the `long-tests` profile); `daw-core/pom.xml` (surefire `argLine`, the `dsp-rebaseline` and `long-tests` profiles); `daw-app/pom.xml` (surefire configuration, `surefire.timeout`, the `long-tests` profile's `testIncludes`); `daw-fx/pom.xml` (surefire `argLine`); `.github/workflows/ci.yml`, `windows-asioshim.yml`, `long-tests.yml`; `CONTRIBUTING.md` (Building and testing, and the long-tests section); `Makefile` (`test`, `long-tests`).
- On 2026-11-27 six classes sat within 0.1 s of the line: `ProjectManagerTest` (3.10 s), `CapturePeakMirrorTest` (3.10 s) and `PackagedLaunchLoggingTest` (3.04 s) above it; `AnExitWaitingForAnotherFoldersWritesOffersQuitAnywayTest` (2.97 s), `ProjectSchemaParityContractTest` (2.96 s) and `ProjectInstallationInputFenceTest` (2.91 s) below it.
- `AsioBackendClockLockTest` (daw-sdk, 3.9 s) matches a path filter of `windows-asioshim.yml`, so a change to it runs that lane on the PR.
- The project's `.claude/CLAUDE.md` gives the local full run's time under "Tests a story runs" (about 23 minutes); that figure changes with this story.
- Cross-refs: story 209 (the `long-tests` profile and its workflow), story 334 (ruling R-T2).

## Status

- Complete

## Resolution

**What the owner can now do.** This story changes the build, not the application: nothing in the DAW looks or behaves differently. At the repository root, `mvn -B -o -DskipNativeBuild=true verify` now runs 14,399 tests in 1,559 classes (32 skipped) and leaves out the 197 classes tagged `slow`. It took 05:51 in run 7, against 22:38 for every test in one run before the change (the baseline; both on 2026-11-27, runs as listed under Verification). `mvn -B -o -DskipNativeBuild=true -Pslow-tests verify` runs only the slow classes (3,154 tests; 19:02, run 8), `-Pall-tests` runs every test as CI now does, and a class named with `-Dtest=` always runs, tagged or not.

By-hand check (in PowerShell at the repository root, with no other build running):
1. `$start = Get-Date; mvn -B -o -DskipNativeBuild=true verify`. Expect BUILD SUCCESS; the five lines `Tests run: N, Failures: 0, Errors: 0, Skipped: M` that follow each module's `Results:` add up to 14,399 tests and 32 skipped; "Total time" under 10:00.
2. In the same window, list every class at 3 s or more from the reports this run wrote (`target/surefire-reports` also holds older runs' reports, so the command reads only files written after `$start`). Expect no line (run 7 listed none); a class it lists gets `@Tag("slow")` and nothing else (R1). Tried with `$start` set to run 7's start (18:34:23), it printed no class from run 7's 1,559 reports and 108 from run 8's, written later.
   `Get-ChildItem */target/surefire-reports/TEST-*.xml | Where-Object LastWriteTime -gt $start | ForEach-Object { ([xml](Get-Content -Raw -Encoding UTF8 $_.FullName)).testsuite } | Where-Object { [double]$_.time -ge 3 } | ForEach-Object { '{0} {1}' -f $_.time, $_.name }`
3. After the push: in the CI run on the PR, the same five `Tests run:` lines of the "Build and test" step add up to 17,553 tests, the total of the last `main` run. The next `long-tests.yml` run shows the same total as its last run before the change.

**What was built.**
- *The mark.* `@Tag("slow")` (`org.junit.jupiter.api.Tag`) and its import on 197 test classes: daw-sdk 4, daw-acoustics 1, daw-core 88, daw-app 104, daw-fx none. Each file gains those two lines and nothing else.
- *The selection, root `pom.xml`.* Properties `test.groups` (empty) and `test.excludedGroups` (`slow`) feed surefire's `<groups>` and `<excludedGroups>` in the root `pluginManagement` configuration, which every module inherits; no module pom changed. Five root profiles: `long-tests` (as before, now also clearing the exclusion), `dsp-rebaseline` (new at the root; clears the exclusion, while daw-core's own profile of that id still sets `dsp.rebaseline=true`), `slow-tests` (only `slow`), `all-tests` (no filter) and `named-tests` (activated by the `test` property; no filter). `named-tests` is declared last because the later-declared active profile's properties win, so `-Pslow-tests -Dtest=X` applies no filter.
- *Workflows.* `ci.yml` runs `xvfb-run --auto-servernum mvn -B -Pall-tests clean verify`; `windows-asioshim.yml` runs `mvn -B -Pall-tests -DskipTests=false verify`. `long-tests.yml` and `release.yml` are unchanged.
- *Makefile.* `test` keeps its command, and its help says it leaves out the slow classes; new targets `slow-tests` and `all-tests`.
- *Documents.* `CONTRIBUTING.md`: new sections "Slow test classes" (the rule, with its 1.5 s and 3 s lines; the measured times; the three commands; the partition; `-Dtest=`; CI and the Windows-only paths) and "When to run the slow classes locally" (the studio-engineer's list). `README.md` lines 38 and 115; `daw-core/native/asio/README.md` line 356 (the lane's command). `.claude/CLAUDE.md` (git-ignored): the local full run's commands and times, the slow classes with Windows-only paths that a story names in its targeted run (a category, and a `git grep` command that lists them), the CI command, and the tag rule for a new test class (1.5 s or more).
- *Tests.* None written: a test of the selection would pin pom text or a timing; the runs and their totals are the evidence.

**What differs from the story text.**
- 197 classes are tagged, not the 96 at 3 s or more on the story's date: the tagging carries a margin (every class at 1.5 s or more in the baseline or run 1, or at 3 s or more in the baseline or any of runs 1 to 6), because class times on the reference machine vary from run to run and criterion 4 checks at 3 s. The 101 added classes were all under 3 s in the baseline.
- The `named-tests` profile is new: without it, the project's targeted command, `-Dtest=<slow class> -Dsurefire.failIfNoSpecifiedTests=false`, would run nothing and report success.
- A root `dsp-rebaseline` profile: the story's profile lives only in daw-core and cannot clear the exclusion in the other modules.
- CI and the Windows lane get every test through an explicit `-Pall-tests`, so the workflow file and its log say what ran; before, their commands named no profile.
- Added beyond the story: the `all-tests` make target, the README lines, the line in `daw-core/native/asio/README.md`, and the `.claude/CLAUDE.md` lines.

**Rulings.**
- R1: a new test class that takes 1.5 s or more on the reference machine (the owner's Windows machine) with no other build running carries `@Tag("slow")` on its class declaration, and so does any class that a run there lists at 3 s or more (`CONTRIBUTING.md`, "Slow test classes"); a class that reaches 3 s later gets the tag and no other change, and an untagged class that a later run lists between the two lines needs no tag.
- R2: a class named with `-Dtest=` always runs, tagged or not.
- R3: `long-tests`, `dsp-rebaseline`, `ci.yml` and `windows-asioshim.yml` run with no tag filter; each runs what it ran before the tag.
- R4 (studio-engineer): the default local build may leave the slow classes out because CI runs every test on every pull request before `main`.
- R5 (studio-engineer): the Windows-only paths of slow classes run on no CI lane, so a story whose change falls under "When to run the slow classes locally" names those classes in its targeted run on this machine (`.claude/CLAUDE.md`).
- R6 (studio-engineer): the slow classes run locally before a push that touches capture and take writing, the transport, project files on disk, exit, close and open, the engine, devices and clock, metering and export, a helper a slow class uses, or the test selection (`CONTRIBUTING.md`, "When to run the slow classes locally").
- R7 (studio-engineer): no tagged class rewrites a golden; `DspRegressionCoverageTest` is tagged and the `@DspRegression` classes are not, so the default run still catches a change to the rendered output of a processor that a `@DspRegression` class covers; whether that processor stays real-time safe (`RealTimeSafeContractTest`, `InsertParameterRealtimeTest`) is checked only in the slow run and on CI.

**Known limits.**
- An IDE run ignores the selection (not tested). With more than one selection profile active, the one declared later in the root pom decides, whatever the order on the command line; shown only for `-Pslow-tests -Dtest=`.
- Nothing tags a class that grows past a line. Run 7, with no other build sampled, listed no class at 3 s or more and 13 untagged classes between 1.5 s and 3 s (9 in daw-app, 4 in daw-core, 1.506 to 2.219 s, all under 1.5 s in the baseline), which R1 leaves untagged. Load from another build can still push a class over 3 s: run 4 listed 33 at 130 tags.
- The root pom sets `<groups>` and `<excludedGroups>` explicitly from `test.groups` and `test.excludedGroups`; what surefire's own `-Dgroups` and `-DexcludedGroups` do on the command line is not tested. Failsafe, which the root pom manages and no module binds, carries no tag selection.
- The Windows-only paths of slow classes run on no CI lane, only in a local run on Windows that includes them. `.claude/CLAUDE.md` names them as a category, with a `git grep` over the tagged classes that lists 11 today (`AsioFormatChangeShimTest` among them, whose Windows-only test skips here without `asioshim.dll`); a Windows branch inside a shared helper (for example `ChildJvm.settingsDirectory`, used by two tagged classes) is not found by it.
- Nothing but the CI test total shows that CI still runs the slow classes: an `mvn` line that loses `-Pall-tests` stays green with 3,154 fewer tests (run 8's total).
- `-Dsnapshots.update=true` without `-Dtest` keeps the slow exclusion, so it would leave out a snapshot class if one were ever tagged (none is).
- The default run and the slow run one after the other take longer than the old single run: runs 7 and 8 took 05:51 + 19:02, against 22:38.
- `make all-tests` runs `verify` without `clean`; CI runs `clean verify`.
- Backlog story 479's acceptance criterion 4 cites `mvn -B -o -DskipNativeBuild=true verify` as the full local gate; that command now leaves out the slow classes.
- "When to run the slow classes locally" does not name every area that the 101 classes tagged after the story's 96 protect, for example processor sound and real-time safety outside the `@DspRegression` goldens, audio import, the room simulator, the mastering view's output level and meters, the xrun and clock feeds, checkpoints, snapshots and status text in the application, and the window's edit, render and close gates; CI still runs those classes on every pull request.

## Verification

Windows 10 Pro, Maven 3.10.0, JDK 27+35 (Eclipse Adoptium); Maven unpiped, one process at a time, never `clean`; all on 2026-11-27, at the repository root with `-B -o -DskipNativeBuild=true`. Counts (classes/tests/skipped) come from the surefire XMLs written after each run's start; other processes were sampled about every 30 s in runs 5 to 8. Baseline: the full run before the change, 14:23 to 14:46 (22:38), 1,756 classes, 17,553 tests, 45 skipped. Runs 1 to 6 tagged on the 3 s line alone: run 1 default, 96 tags, 08:58; run 2 default, 104 tags, 10:55, beside another build; run 3 `-Pslow-tests`, 104 tags, 20:00, another build seen at about its start; run 4 default, 130 tags, 12:33, beside another build; run 5 `-Pslow-tests`, 130 tags, 24:24, beside another build; run 6 default, 130 tags, 07:49, another build in its last three minutes. Each default run listed untagged classes at 3 s or more that were under 3 s in the baseline (8, 26, 33 and 10; run 6's include 2 in daw-core with no other build seen), which led to the margin in R1 and 67 more tags.
- Run 7 (`verify`), the default run of record, 18:34:23 to 18:40:25: exit 0, BUILD SUCCESS, "Total time: 05:51 min"; 1,559/14,399/32, 0 failures, 0 errors; daw-sdk 172/1,496/0, daw-acoustics 13/84/0, daw-core 726/8,157/29, daw-fx 2/29/0, daw-app 646/4,633/3. No class at 3.0 s or more (slowest `PreparedProjectLeaseTest`, 2.219 s). No other build was sampled (12 samples).
- Run 8 (`-Pslow-tests verify`), the slow run of record, 18:44:30 to 19:03:35: exit 0, BUILD SUCCESS, "Total time: 19:02 min"; 197/3,154/13, 0 failures, 0 errors; daw-sdk 4/59/2, daw-acoustics 1/22/0, daw-core 88/1,663/11, daw-fx 0/0/0 (still SUCCESS), daw-app 104/1,410/0. No other build was sampled (39 samples).
- Check of record, runs 7 and 8: no class in both; the union is the baseline's 1,756 class names, none missing or extra; 14,399 + 3,154 = 17,553 tests and 32 + 13 = 45 skipped; every class's tests and skipped equal its baseline row; run 8's classes are exactly the 197 files carrying `@Tag("slow")`, and run 7's are the baseline's other 1,559.
- Cheap runs (`-pl <module> -am test`, at 96 or 104 tags; the selection is unchanged since): on daw-sdk and daw-acoustics, the default selection 175/1,537 and 13/84, `-Pslow-tests` 1/18 and 1/22, `-Pall-tests` 176/1,555 and 14/106, `-Plong-tests` 176/1,555 and 14/106 into `target/long-test-reports` (run twice). A slow class named with `-Dtest=` and `-Dsurefire.failIfNoSpecifiedTests=false`, tests from the XML the run wrote: daw-acoustics `AcousticsRoomSimulatorTest` 22, daw-core `CapturePeakMirrorTest` 12 and `StopFinalizationPendingContractTest` 8, daw-app `PackagedLaunchLoggingTest` 5 and `LateLoadOutcomeReportingTest` 11; all exit 0.
- Effective POMs (`help:effective-pom`), all five modules: no profile gives `<groups/>` and `<excludedGroups>slow</excludedGroups>`; `-Pslow-tests` gives `slow` and no exclusion; `-Pall-tests`, `-Plong-tests`, `-Pdsp-rebaseline`, `-Dtest=X` and `-Pslow-tests -Dtest=X` give neither. daw-core keeps `dsp.rebaseline=true` under `-Pdsp-rebaseline` and `dsp.regression.strict=true` under `-Plong-tests`. Against a copy of HEAD's poms, `long-tests` and `dsp-rebaseline` differ only by the new lines. `-Pdsp-rebaseline` was never run.

Acceptance criteria:
1. Default build under 10 minutes: met in run 7: 05:51 on the final tree, with no other build sampled.
2. Default total plus slow total equals the full local total and the CI total: met for the local side (17,553, check of record of runs 7 and 8); the CI side is left for the owner with criterion 3.
3. The CI total on the PR equals the last `main` run's: left for the owner.
4. No class at 3 s or more in a default run: met in run 7, with no other build sampled: its reports list none (slowest 2.219 s). The earlier default runs, at fewer tags, each listed some (run 6: 10; run 4, beside another build: 33).
5. First `long-tests.yml` and `windows-asioshim.yml` totals unchanged: left for the owner. The effective POMs show `long-tests` selecting what it did before; the Windows lane skips its build while `ASIO_SDK_ZIP_BASE64` is unset, so its total cannot be seen until then.

Not verified: the baseline's 22:38 (no log was kept; its reports span 14:23:47 to 14:46:00); a full `-Pall-tests` or `-Plong-tests` run (both only on daw-sdk and daw-acoustics, at 96 or 104 tags); the make targets (they call `xvfb-run`); Linux (no WSL run: a tag changes no operating-system branch); the CI totals; the Windows lane while its secret is unset; another build that ran only between two samples of run 7 or run 8.

Left for the owner:
- The commit, as one commit: root `pom.xml`, `ci.yml`, `windows-asioshim.yml`, `Makefile`, `CONTRIBUTING.md`, `README.md`, `daw-core/native/asio/README.md`, the 197 test files (two lines each) and this story file. `.claude/CLAUDE.md` is git-ignored and already edited.
- The push and the CI run on the PR: 17,553 tests (by-hand step 3). `windows-asioshim.yml` also starts on the PR (its workflow file, `daw-core/native/asio/README.md` and the four tagged `Asio*Test` classes of daw-sdk match its path filters) and skips its build while the secret is unset.
- The first `long-tests.yml` run after the merge (it runs on pushes to `main` and daily): the same total as its last run before the change.
- The by-hand check above, on an idle machine.

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

- Open

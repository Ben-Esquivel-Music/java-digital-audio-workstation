---
title: "Long-Running Render Test Profile for End-to-End Export Tests"
labels: ["testing", "ci", "export"]
---

# Long-Running Render Test Profile for End-to-End Export Tests

## Motivation

End-to-end tests — load a real project, render a master, assert LUFS within target, assert bit-accuracy on stems — take minutes each. Running them on every push slows the feedback loop. Running them only at release time means regressions hide for weeks. The standard compromise is a separate Maven profile invoked nightly or on release branches, producing a clear pass/fail report distinct from the fast unit-test run.

## Goals

- Add a `long-tests` Maven profile in the root `pom.xml` that activates a `src/test/long/` test source set with a separate Surefire configuration.
- The long-test pipeline includes: a 5-track full-render test, a 20-track batch-export test (story 186), a DDP image export validation, an ADM BWF export + re-import round-trip (story 170), and a bundle export (story 181).
- Each long test has a documented expected wall-clock budget; tests exceeding 2× the budget fail with a performance-regression message.
- Golden files for each expected output stored under `daw-app/src/test/long/resources/golden/` with a rebaselining Makefile target.
- CI wiring: GitHub Actions workflow runs `mvn -Plong-tests verify` on `main` pushes only, not on PRs.
- Daily scheduled CI run also executes `long-tests` and posts a report to the repo's Actions tab.
- Documentation in `CONTRIBUTING.md` explaining when to run long tests locally (before touching audio-engine or export code).
- Tests-of-the-harness verify that long-test setup/teardown cleans up temp directories and does not leak file handles.

## Non-Goals

- Performance regression testing with sub-percent precision (a separate JMH benchmarks story).
- Cross-platform long-test runs (Linux CI only in MVP).
- External-service integration tests (no network in CI).

# ─────────────────────────────────────────────────────────────────────
# Convenience targets for the Digital Audio Workstation build.
#
# Most day-to-day work uses `mvn` directly. This Makefile collects the
# few multi-step or system-property-laden invocations that benefit from
# a memorable, short alias — most importantly the rebaseline target
# for the `long-tests` golden artefacts (story 209).
# ─────────────────────────────────────────────────────────────────────

.PHONY: help build test long-tests rebaseline-long-tests clean

help:
	@echo "Targets:"
	@echo "  build                    Compile and package all modules"
	@echo "  test                     Run the fast unit-test suite"
	@echo "  long-tests               Run the long-running render/export tests"
	@echo "  rebaseline-long-tests    Re-record golden files for the long-tests"
	@echo "                           suite (writes to daw-app/src/test/long/"
	@echo "                           resources/golden/ — review the diff!)"
	@echo "  clean                    mvn clean across all modules"

build:
	mvn -B -DskipTests verify

test:
	xvfb-run --auto-servernum mvn -B verify

long-tests:
	xvfb-run --auto-servernum mvn -B -Plong-tests verify

# Story 209: regenerate every golden file under
# daw-app/src/test/long/resources/golden/ from the current code.
# Use this when:
#   * an exporter's byte layout has intentionally changed,
#   * a new long test needs its initial baseline created.
# After running, review the diff in `git status` before committing —
# unintentional changes here are exactly the regressions long-tests
# are designed to catch.
rebaseline-long-tests:
	xvfb-run --auto-servernum mvn -B -Plong-tests verify -Dlongtests.rebaseline=true
	@echo
	@echo "Goldens regenerated. Review with:"
	@echo "  git status daw-app/src/test/long/resources/golden/"
	@echo "  git diff   daw-app/src/test/long/resources/golden/"

clean:
	mvn -B clean

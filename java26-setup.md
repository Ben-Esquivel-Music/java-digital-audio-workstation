# Java 26 Setup

This repository targets Java 26 and runs JavaFX tests in CI.

## Headless CI test issue (JavaFX)

If tests fail in CI with errors like `Unable to open DISPLAY`, `Toolkit not initialized`, or JavaFX/Glass startup failures, run tests under a virtual X server.

### GitHub Actions/Linux fix

Install Xvfb and execute Maven through `xvfb-run`:

```bash
sudo apt-get update -qq
sudo apt-get install -y -qq xvfb
xvfb-run --auto-servernum mvn -B clean verify
```

This is the expected pattern for headless Linux CI because JavaFX UI tests still need a display provider even when no physical display exists.

### Optional stability flags

If you still see rendering/runtime issues in CI, pass these JVM properties to test runs:

```bash
mvn -B test \
  -DargLine="-Dglass.platform=gtk -Dprism.order=sw"
```

### Important note

Do **not** force `-Djava.awt.headless=true` for JavaFX UI tests; that disables graphics initialization and usually causes more failures.

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

If you still see rendering/runtime issues in CI, pass the properties as system properties on the Maven command line. Do **not** use `-DargLine="..."` because that overrides the `argLine` already configured in the POM (which includes required flags like `--enable-native-access=ALL-UNNAMED` and `-Djava.library.path`).

```bash
xvfb-run --auto-servernum mvn -B clean verify \
  -Dglass.platform=gtk -Dprism.order=sw
```

### Important note

Do **not** force `-Djava.awt.headless=true` for JavaFX UI tests; that disables graphics initialization and usually causes more failures.

## Internal Mix Bus Precision

The DAW's internal summing bus is selectable between 32-bit single-precision and 64-bit double-precision via the **Audio Settings → Mix Bus Precision** combo box (model key `audio.mixPrecision`, enum `com.benesquivelmusic.daw.sdk.audio.MixPrecision`).

| Mode        | When to use                                                          |
| ----------- | -------------------------------------------------------------------- |
| `DOUBLE_64` | **Default.** Matches every professional DAW (Pro Tools HDX, Logic, Cubase, Studio One, Reaper, Ableton). Eliminates low-bit accumulation error on large (64+ track) sessions and during dynamic processing. |
| `FLOAT_32`  | Legacy / very low-CPU machines. Bit-exact with pre-existing DAW renders.                                                                 |

`DOUBLE_64` roughly doubles mix-bus memory bandwidth, but typical total CPU impact is **modest**: plugin DSP still runs at each plugin's preferred precision (float unless the plugin overrides `AudioProcessor.supportsDouble()`). The extra cost lives in the summing stages themselves, which are dwarfed by plugin processing in any real session.

The per-hardware-output conversion to the device's native format (typically `float32`) happens once at the output stage; only the internal summing and gain-staging loops run at 64-bit when `DOUBLE_64` is selected.

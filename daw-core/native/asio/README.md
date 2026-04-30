# asioshim — native FFM bridge for ASIO driver capability queries

This directory hosts the native shared library (`asioshim.dll`) that the
JVM loads via FFM (`SymbolLookup.libraryLookup("asioshim", arena)`) to
talk to a Steinberg ASIO driver.

The Java side lives in
`daw-sdk/src/main/java/com/benesquivelmusic/daw/sdk/audio/AsioCapabilityShim.java`
and `AsioFormatChangeShim.java`.

## Exported symbols

| Symbol | Wraps | Used by |
| --- | --- | --- |
| `int asioshim_getBufferSize(int* min, int* max, int* preferred, int* granularity)` | `ASIOGetBufferSize` | `AsioBackend.bufferSizeRange` |
| `int asioshim_canSampleRate(double rate)` | `ASIOCanSampleRate` | `AsioBackend.supportedSampleRates` |
| `int asioshim_getSampleRate(double* outRate)` | `ASIOGetSampleRate` | controller after a driver-initiated reset |
| `int asioshim_setSampleRate(double rate)` | `ASIOSetSampleRate` | dialog "apply rate" path |
| `int asioshim_openControlPanel()` | `ASIOControlPanel` | dialog "Open Driver Control Panel" button (story 212) |
| `int asioshim_getClockSources(void* outArray, int* outCount)` | `ASIOGetClockSources` | `AsioBackend.clockSources` (story 216) |
| `int asioshim_setClockSource(int reference)` | `ASIOSetClockSource` | `AsioBackend.selectClockSource` (story 216) |
| `void installAsioMessageCallback(void* callback)` | (host upcall) | `AsioFormatChangeShim` (story 218) |
| `void uninstallAsioMessageCallback()` | (host upcall) | `AsioFormatChangeShim` close |

All capability functions return `1` for `ASE_OK` and `0` otherwise,
**except** `asioshim_setClockSource`, which returns the raw `ASIOError`
(0 on `ASE_OK`, negative for the SDK's standard error codes —
`ASE_NotPresent`, `ASE_HWMalfunction`, `ASE_InvalidParameter`,
`ASE_InvalidMode`, …) so the Java side can translate each into a
mapped `AudioBackendException` message.

`asioshim_getClockSources` writes into a caller-allocated buffer of
`*outCount` entries (each entry is a fixed 48-byte struct: 32-byte
ASCII NUL-terminated `name`, then four `int32`s for `index`,
`associatedChannel`, `associatedGroup`, `isCurrentSource`). On entry
`*outCount` is the buffer capacity; on `ASE_OK` it is overwritten with
the actual entry count.

## Building

The shim links against the **Steinberg ASIO SDK**, which Steinberg's
licence forbids us from redistributing. To build:

1. Download the Steinberg ASIO SDK from
   <https://www.steinberg.net/asiosdk> and extract it somewhere local —
   for example `C:/asiosdk`.
2. Configure the top-level native build with `-DASIO_SDK_DIR=...`
   (or set the environment variable `ASIO_SDK_DIR` to the same path):

   ```bash
   cmake -S lib -B target/build -DASIO_SDK_DIR=C:/asiosdk -A x64
   cmake --build target/build --config Release
   ```

   The resulting DLL is written alongside the other native libraries
   under `target/build/native/asioshim.dll`.

3. The Maven build under `daw-app/pom.xml` then copies it to
   `daw-app/target/dist/native/windows-x64/asioshim.dll` via the
   existing `copy-native-libs-to-dist` antrun execution.

If `ASIO_SDK_DIR` is not set, the CMake configure step **silently skips
the asioshim target**. The Java backend already degrades to its
fallback range / canonical rate set when the library is absent, so a
local build without the SDK still produces a fully working DAW (just
without driver-reported capability menus).

## Non-Windows hosts

The Steinberg ASIO SDK is Windows-only. On Linux / macOS the CMake
target is silently skipped; FFM `SymbolLookup` then fails to find the
library and the Java side falls back to `BufferSizeRange.DEFAULT_RANGE`
and the canonical sample-rate menu.

## Threading

All FFM downcalls run on the calling thread (typically the JavaFX
thread when the Audio Settings dialog opens), never on the audio
render thread. Mid-stream rate changes route through
`AsioFormatChangeShim` (story 218), not these capability queries.

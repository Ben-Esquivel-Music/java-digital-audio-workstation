# asioshim — native FFM bridge for ASIO host lifecycle and capabilities

This directory hosts the native shared library (`asioshim.dll`) that the
JVM loads via FFM (`SymbolLookup.libraryLookup("asioshim", arena)`) to
talk to a Steinberg ASIO driver.

The Java side lives in `AsioDriverShim.java`, `AsioCapabilityShim.java`,
`AsioControlThread.java`, `AsioFormatChangeShim.java`, and the public
`AsioDriverInfo.java` display value under
`daw-sdk/src/main/java/com/benesquivelmusic/daw/sdk/audio/`.

## Exported symbols

| Symbol | Wraps | Used by |
| --- | --- | --- |
| `int asioshim_listDrivers(void* rows, int* count)` | SDK `AsioDrivers` registry list | `AsioBackend.listDevices` |
| `int asioshim_loadDriver(const char* name)` | indexed SDK host-glue open + `ASIOInit` | `AsioBackend.open` |
| `int asioshim_getDriverName(void* name, int capacity)` | active lifecycle state | driver display/diagnostics |
| `int asioshim_getDriverInfo(void* info)` | normalized `ASIODriverInfo` | driver display/diagnostics |
| `void asioshim_unloadDriver()` | `ASIOExit` + SDK COM release | `AsioBackend.close` |
| `int asioshim_getBufferSize(int* min, int* max, int* preferred, int* granularity)` | `ASIOGetBufferSize` | `AsioBackend.bufferSizeRange` |
| `int asioshim_canSampleRate(double rate)` | `ASIOCanSampleRate` | `AsioBackend.supportedSampleRates` |
| `int asioshim_getSampleRate(double* outRate)` | `ASIOGetSampleRate` | controller after a driver-initiated reset |
| `int asioshim_setSampleRate(double rate)` | `ASIOSetSampleRate` | dialog "apply rate" path |
| `int asioshim_openControlPanel()` | `ASIOControlPanel` | dialog "Open Driver Control Panel" button (story 212) |
| `int asioshim_getClockSources(void* outArray, int* outCount)` | `ASIOGetClockSources` | `AsioBackend.clockSources` (story 216) |
| `int asioshim_setClockSource(int reference)` | `ASIOSetClockSource` | `AsioBackend.selectClockSource` (story 216) |
| `void installAsioMessageCallback(void* callback)` | (host upcall) | `AsioFormatChangeShim` (story 218) |
| `void uninstallAsioMessageCallback()` | (host upcall) | `AsioFormatChangeShim` close |

Most capability functions return `1` (`SHIM_OK`) for `ASE_OK` and `0`
(`SHIM_FAIL`) otherwise, with two exceptions:

- **`asioshim_openControlPanel`** — returns `1` on `ASE_OK`, `0` when
  `ASE_NotPresent` (no control panel), or `-1` for any other error.
- **`asioshim_setClockSource`** — returns the raw `ASIOError` directly
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

## Driver lifecycle and ABI

`asioshim_listDrivers` writes one fixed 168-byte record per installed x64
driver:

| Offset | Type | Meaning |
| --- | --- | --- |
| `0` | `char name[128]` | SDK description and canonical Java `DeviceId`/load key |
| `128` | `char clsid[40]` | canonical `{xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx}` CLSID |

Both strings are printable ASCII and NUL-terminated. `*count` is
capacity-in / actual-written-out: the result is capped at the input capacity.
A zero-capacity call succeeds with `*count == 0`; a positive capacity requires
a non-null row buffer. Enumeration uses indexed protected operations from the
SDK's `AsioDrivers` host glue, so a registry subkey name need not equal the
display description and names are not truncated to the legacy 32-byte
`getDriverNames` helper buffer.

`asioshim_getDriverInfo` does **not** expose native `ASIODriverInfo` memory.
It writes a stable 264-byte FFM record instead:

| Offset | Type | Meaning |
| --- | --- | --- |
| `0` | `int32` | ASIO host API version |
| `4` | `int32` | vendor driver version |
| `8` | `char name[128]` | initialized driver name |
| `136` | `char errorMessage[128]` | driver status/error text |

`asioshim_loadDriver` tears down any driver previously loaded through the
native lifecycle before opening the replacement. It zeroes `ASIODriverInfo`,
sets `asioVersion = 2` and `sysRef = GetDesktopWindow()`, and only publishes
active state after `ASIOInit` returns `ASE_OK`. A failed init immediately
releases the COM driver and clears state. `asioshim_unloadDriver` is idempotent.

The Java `AudioBackend` contract rejects a second `open` while a stream is
open. To switch devices, close the backend and then open the new `DeviceId`;
the observable order is callback uninstall, `ASIOExit`, COM release, then the
next driver's load/init. `DeviceId.defaultFor("ASIO")` resolves to the first
enumerated driver and fails clearly when the snapshot is empty. If a vendor
publishes duplicate descriptions, the first SDK registry entry is the
deterministic load target because the story contract intentionally uses the
driver name as the ID.

Every capability export and `asioshim_openControlPanel` is guarded by active
driver state. Before `asioshim_loadDriver` succeeds it returns its documented
graceful-absence/failure value without calling the SDK. Settings screens that
probe a closed backend therefore see fallback buffer/rate menus and empty
clock/channel/panel capabilities; they are refreshed against the real driver
after open.

## Building

The shim links against the **Steinberg ASIO SDK**, which Steinberg's
licence forbids us from redistributing. To build:

1. Download the Steinberg ASIO SDK from
   <https://www.steinberg.net/asiosdk> and extract it somewhere local —
   for example `C:/asiosdk`.
2. Configure the top-level native build with `-DASIO_SDK_DIR=...`
   (or set the environment variable `ASIO_SDK_DIR` to the same path):

   ```bash
   cmake -S lib -B target/build -DBUILD_ASIO_SHIM=ON \
         -DASIO_SDK_DIR=C:/asiosdk -A x64
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

## Continuous integration

The dedicated **`.github/workflows/windows-asioshim.yml`** lane
provisions the Steinberg ASIO SDK from the `ASIO_SDK_ZIP_BASE64`
repository secret, sets `ASIO_SDK_DIR`, runs `mvn -B verify`, and
asserts that `daw-app/target/dist/native/windows-x64/asioshim.dll`
exists after the assembly phase. The job is path-filtered so it only
runs when the ASIO surface (`daw-core/native/asio/`,
`daw-sdk/.../Asio*.java`, `lib/CMakeLists.txt`, the workflow itself)
changes — the rest of the PR feedback loop stays on `ci.yml`.

That lane exports **`DAW_REQUIRE_ASIOSHIM=1`** so the env-gated
assertions in `NativeLibraryDetectorTest` and `AsioFormatChangeShimTest`
flip from `Assumptions.assumeTrue(...)` (skip) to hard `assertTrue(...)`
checks. Absent `asioshim.dll` therefore fails the CI lane rather than
silently degrading to the no-shim fallback path. On developer
workstations the env var is unset and those same tests remain
`assumeTrue` skips, so a fresh clone without the SDK still produces a
green build.

## Threading

All enumeration, lifecycle, capability, and control-panel FFM downcalls run on
the dedicated daemon platform thread named `asio-control`, never the JavaFX or
audio render thread. This keeps the SDK's COM initialization, driver creation,
calls, exit, and release in one apartment. `AsioFormatChangeShim` uses a shared
FFM arena because its upcall is invoked from the vendor driver's callback
thread. Windows C `long` is 32-bit even on x64, so the callback ABI is
normalized to `int32_t` / `ValueLayout.JAVA_INT`, not Java `long`.

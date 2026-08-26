# asioshim — native FFM bridge for ASIO host lifecycle and capabilities

This directory hosts the native shared library (`asioshim.dll`) that the
JVM loads via FFM (`SymbolLookup.libraryLookup("asioshim", arena)`) to
talk to a Steinberg ASIO driver.

The Java side lives in `AsioDriverShim.java`, `AsioCapabilityShim.java`,
`AsioControlThread.java`, `AsioFormatChangeShim.java`,
`AsioStreamingShim.java`, `AsioBufferSwitchShim.java`, and the public
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
| `long asioshim_messageTrampoline(long selector, long value, void* message, double* opt)` | `ASIOCallbacks::asioMessage` | the vendor driver (story 218; the callbacks table is wired to this exported symbol) |
| `int asioshim_createBuffers(const int* inputChannels, int numInputs, const int* outputChannels, int numOutputs, int bufferFrames)` | `ASIOCreateBuffers` + `ASIOCallbacks` install | `AsioStreamingShim.createBuffers` (story 311) |
| `int asioshim_getBufferInfos(void* outArray, int* outCount)` | cached `ASIOBufferInfo[]` + `ASIOGetChannelInfo` types | `AsioStreamingShim.getBufferInfos` (story 311) |
| `int asioshim_start()` | `ASIOStart` | `AsioStreamingShim.start` (story 311) |
| `int asioshim_stop()` | `ASIOStop` | `AsioStreamingShim.stop` (story 311) |
| `int asioshim_disposeBuffers()` | `ASIOStop` + `ASIODisposeBuffers` | `AsioStreamingShim.disposeBuffers` (story 311) |
| `void installAsioBufferSwitchCallback(void* callback)` | (host upcall) | `AsioStreamingShim.installBufferSwitchCallback` (story 311) |
| `void uninstallAsioBufferSwitchCallback()` | (host upcall) | `AsioStreamingShim.uninstallBufferSwitchCallback` |
| `void asioshim_bufferSwitchTrampoline(long index, long directProcess)` | `ASIOCallbacks::bufferSwitch` | the vendor driver (story 311; the callbacks table is wired to this exported symbol) |

Most capability functions return `1` (`SHIM_OK`) for `ASE_OK` and `0`
(`SHIM_FAIL`) otherwise, with two exceptions:

- **`asioshim_openControlPanel`** — returns `1` on `ASE_OK`, `0` when
  `ASE_NotPresent` (no control panel), or `-1` for any other error.
- **`asioshim_setClockSource`** — returns the raw `ASIOError` directly
  (0 on `ASE_OK`, negative for the SDK's standard error codes —
  `ASE_NotPresent`, `ASE_HWMalfunction`, `ASE_InvalidParameter`,
  `ASE_InvalidMode`, …) so the Java side can translate each into a
  mapped `AudioBackendException` message.

`asioshim_stop` and `asioshim_disposeBuffers` (story 311) follow the normal
convention with one carve-out: they return `1` when there was **nothing to
do** — no driver loaded, not started, or no buffers created — because the
Java `close()` path calls them unconditionally. They still return `0` when
the SDK call was actually made and the driver **refused** it. That
distinction is a **diagnostic**, not a stop signal: a driver that answers
`ASIOStop()` with `ASE_InvalidMode` may keep firing `bufferSwitch`, which is
worth surfacing, so `AsioBackend#tearDownStreaming` logs the refusal at
`WARNING` — naming the refused call and the driver — and then deliberately
carries on to dispose the buffers, uninstall the upcall, and free its arena.
What makes that free safe is the bounded in-flight callback barrier plus the
null callback pointer published by `uninstallAsioBufferSwitchCallback` (see
"Driver-thread entry points" below), not `ASIOStop` having succeeded. Bailing
out on a refusal would skip the very uninstall that provides the protection,
and would leak the FFM upcall arena and the `asio-input-drain` thread for the
life of the process.

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

### The SDK's `asioDrivers` global is ours to manage

The shim loads by **index** (`asioOpenDriver`) instead of calling the SDK's
`loadAsioDriver(char*)` helper, so that a driver whose registry key differs
from its display description is still loadable. That has a consequence worth
spelling out, because getting it wrong is an access violation rather than a
returned error code:

`loadAsioDriver` is the **only** place the SDK ever assigns its own
process-global `AsioDrivers* asioDrivers` (defined as
`AsioDrivers* asioDrivers = 0;` in `host/asiodrivers.cpp`). Steinberg's
`ASIOExit()` in `common/asio.cpp` then dereferences that global with no null
check:

```c
ASIOError ASIOExit(void) {
    if (theAsioDriver) {
#if WINDOWS
        asioDrivers->removeCurrentDriver();
```

and `AsioDrivers::removeCurrentDriver()` is a non-static member that reads and
writes `this->curIndex`. Bypassing `loadAsioDriver` would therefore leave the
global null and turn every `ASIOExit()` — i.e. every `close()` and every
re-`open()` — into a null-`this` member access. (Some downstream forks, such
as RtAudio's vendored `asio.cpp`, carry an `if (asioDrivers)` patch; the
Steinberg SDK this shim builds against does not.)

`asioshim.cpp` therefore owns that global's lifetime explicitly: it publishes
its own `AsioDrivers` subclass into `asioDrivers` as soon as the instance is
created — before anything can reach `ASIOInit`/`ASIOExit` — and clears it back
to `nullptr` immediately before destroying that instance, so the SDK global
never dangles. The unload path still calls `removeCurrentDriver()` itself for
the failure paths that never reached `ASIOInit`; when `ASIOExit()` did run,
that second call is a no-op because `removeCurrentDriver()` leaves
`curIndex == -1`.

## Real-time streaming (story 311)

Audio only flows once the driver's double buffers exist and the host callback
table is installed. The ordering contract, all on `asio-control`, is:

```
asioshim_createBuffers  →  asioshim_getBufferInfos  →  asioshim_start
        … streaming (driver-thread bufferSwitch callbacks) …
asioshim_stop  →  asioshim_disposeBuffers  →  asioshim_unloadDriver
```

`asioshim_createBuffers` requires a loaded driver and no already-created
buffers. It rejects negative counts, an empty channel set, a non-positive
`bufferFrames`, negative channel indices, a null channel array with a positive
matching count, and more than **64 total active channels** — the cap is on
`numInputs + numOutputs` combined, so a symmetric configuration tops out at 32
in + 32 out. All of that is checked before entering the SDK. It then builds
the `ASIOBufferInfo[]` inputs-first-then-outputs, fills the process-global
`ASIOCallbacks` table, and calls `ASIOCreateBuffers`. On `ASE_OK` it caches
each channel's `ASIOSampleType` via `ASIOGetChannelInfo`, probes
`ASIOOutputReady()` once, and publishes the created state.

A failure leaves no partial state — including the partial state the *driver*
may hold. `ASIOCreateBuffers` can fail part-way through (request 32 in + 32
out on a 32-in/16-out device and a driver may allocate the inputs, refuse the
outputs, and return `ASE_InvalidMode` while still holding those inputs and a
live pointer to the callback table), so the failure path calls
`ASIODisposeBuffers()` **before** it clears the shim's own "buffers created"
flag. Clearing first would make every later `asioshim_disposeBuffers` a no-op
and leave the device unusable — answering each corrected retry with "buffers
already created" — until the process restarts.

This is also the point at which the story-218
`asioshim_messageTrampoline` finally becomes reachable: it occupies the
`ASIOCallbacks::asioMessage` slot, and before this story no `ASIOCallbacks`
struct was ever constructed, so the driver had nowhere to deliver
`kAsioResetRequest` / `kAsioBufferSizeChange` / `kAsioResyncRequest`. The
`sampleRateDidChange` slot forwards to the same trampoline with
`kAsioResetRequest`, which is what `AsioFormatChangeShim` already documents
("sample-rate-driven resets … are reported as `DriverReset`").

`asioshim_stop` and `asioshim_disposeBuffers` return `1` when there is nothing
to do — including when no driver is loaded — so the Java `close()` path can
call them unconditionally, and `0` when the driver refused the underlying SDK
call (see the return-code note above). `asioshim_unloadDriver` performs the
same stop + dispose teardown before `ASIOExit`, so a `close()` that skips the
explicit teardown still leaves the vendor driver clean.

Each `bufferSwitch` invokes the JVM upcall and then, when the driver
advertised support at `ASIOCreateBuffers` time, calls `ASIOOutputReady()`
natively — but **only when the upcall actually ran**. With no upcall installed
(between `asioshim_start` and `installAsioBufferSwitchCallback`, or after an
uninstall the driver has not caught up with) nothing filled the output half,
so signalling output-ready would tell the driver to replay the previous
cycle's contents: audible looped garbage instead of silence.

`asioshim_getBufferInfos` reports the addresses `ASIOCreateBuffers` handed
back, one fixed 32-byte record per active channel, in exactly the order passed
to `asioshim_createBuffers` (all inputs first, then all outputs):

| Offset | Type | Meaning |
| --- | --- | --- |
| `0` | `int32` | `channel` — driver channel index |
| `4` | `int32` | `isInput` — 1 = input, 0 = output |
| `8` | `int32` | `sampleType` — the driver's `ASIOSampleType`, or `-1` when the driver refused to report one |
| `12` | `int32` | reserved, always 0 — padding so the two addresses below are 8-byte aligned |
| `16` | `int64` | `buffer0` — address of `ASIOBufferInfo.buffers[0]` |
| `24` | `int64` | `buffer1` — address of `ASIOBufferInfo.buffers[1]` |

`*outCount` is capacity-in / actual-written-out, matching
`asioshim_listDrivers` and `asioshim_getClockSources`. A zero-capacity call is
a successful no-op; the call fails with `*outCount == 0` when no driver is
loaded or buffers have not been created.

The raw addresses are deliberate: the sample-format conversion boundary lives
in Java, so the JVM reads and writes the driver's buffers directly through FFM.
They are valid only between a successful `asioshim_createBuffers` and the
matching `asioshim_disposeBuffers` / `asioshim_unloadDriver`, and carry no
alignment guarantee — the Java side must use the `*_UNALIGNED` value layouts.

### Sample-format conversion (story 312)

Story 312 converts the full `ASIOSampleType` matrix **in Java**, in
`daw-sdk`'s `AsioSampleType`, driven by the per-channel `sampleType` the record
above already reports. The shim therefore needs no
`asioshim_getChannelSampleType` export and moves no samples of its own.

| Reported `ASIOSampleType` | Codes | Driver bytes per sample |
| --- | --- | --- |
| `Int16MSB` / `Int16LSB` | 0, 16 | 2 |
| `Int24MSB` / `Int24LSB` (packed) | 1, 17 | 3 |
| `Int32MSB` / `Int32LSB` | 2, 18 | 4 |
| `Int32MSB16/18/20/24`, `Int32LSB16/18/20/24` (right-justified) | 8–11, 24–27 | 4 |
| `Float32MSB` / `Float32LSB` | 3, 19 | 4 |
| `Float64MSB` / `Float64LSB` | 4, 20 | 8 |

Anything outside that matrix — the DSD types (32, 33, 40), an unassigned code,
or the `-1` this shim reports when `ASIOGetChannelInfo` refused — fails
`AsioBackend#open` with an `AudioBackendException` naming every offending
channel. Guessing at the layout would hand the driver the engine's float bytes
reinterpreted as integers, which is loud enough to damage speakers.

The choice of Java over the native trampoline is deliberate: this shim is
skipped entirely by CMake unless `-DASIO_SDK_DIR=...` is supplied (see
*Building* below), so conversion code placed here would compile — and be
tested — on the release lane only, whereas the Java converter is covered by
`AsioSampleTypeTest` on every build.

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
the asioshim target**. A local build without the SDK still produces a
fully working DAW — it simply has no ASIO in it.
`AsioBackend.isAvailable()` needs the shim's enumeration and lifecycle
symbols to resolve, so it answers `false`, and `AudioBackendSelector`'s
availability + streaming gate (`probe.isAvailable() &&
probe.supportsStreaming()`) drops ASIO from the offered backend list
entirely. ASIO is never *offered* as a choice on such a host, and
the provision ladder heads on PortAudio (else Java Sound).

There is one state in which the user still sees ASIO, and it is
deliberate: a **previously persisted** `audio.backend=ASIO` is retained
rather than silently discarded. `SettingRow.replaceChoiceOptions`
re-inserts a current value that the freshly enumerated options no longer
contain, so the Settings dialog keeps showing the stored choice on a
host that cannot honour it — install the shim (or move the project to a
machine that has it) and the selection is still there. In that state the
row's own value is what drives the capability enumeration:
`DeviceEnumerationTask` asks the controller for `bufferSizeRange` /
`supportedSampleRates` under the name `ASIO`,
`DefaultAudioEngineController.withSdkBackend` constructs a probe
`AsioBackend` by name (`AudioBackendSelector.selectByName` instantiates
regardless of availability — the gate is on the *offered list*, not on
construction), and the shim-less backend answers
`BufferSizeRange.DEFAULT_RANGE` and the canonical sample-rate set,
logging the reason once. So those fallbacks are user-visible in exactly
that one case: menus behind a stale persisted selection, never behind a
freshly offered one.

Nothing opens on it, and nothing about the substitution is silent. At
open time the provisioning gate refuses the request:
`DefaultAudioEngineController.buildStreamingProvision` closes the probe,
raises one `NotificationManager` warning naming the ladder's actual head
("ASIO not available — falling back to PortAudio"), and carries the
refusal as a pending failed hop so a `BackendFallbackEvent` is published
for it once the winning rung is known. The stream then runs on that head
rung, and the user's ASIO request stays persisted for the next start.

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
runs when the ASIO surface changes: `daw-core/native/asio/`, the
`daw-sdk` `Asio*.java` classes and their tests, the `AudioBlockRing`
pair (`daw-sdk` main class plus `AudioBlockRingTest` — the lock-free
hand-off the `bufferSwitch` bridge is built on), the three env-gated
`daw-core` suites named below, `lib/CMakeLists.txt`, and the workflow
itself. The rest of the PR feedback loop stays on `ci.yml`.

That lane exports **`DAW_REQUIRE_ASIOSHIM=1`**. Three suites read it —
`NativeLibraryDetectorTest`, `AsioStreamingIntegrationTest` (story 311)
and `AsioEngineStreamingIntegrationTest` (story 316) — and in each the
guard on `asioshim.dll` being on the FFM library path flips from
`Assumptions.assumeTrue(...)` (skip) to a hard `assertTrue(...)`. Absent
`asioshim.dll` therefore fails the CI lane rather than silently degrading
to the no-shim fallback path. All three live in `daw-core`, the only
module whose Surefire sets `-Djava.library.path=${native.libs.dir}` and
which runs *after* the CMake native build. `AsioFormatChangeShimTest`
sits in `daw-sdk` and deliberately does *not* read the variable — not
because it could not (`System.getenv` works there like anywhere else),
but because nothing in `daw-sdk`'s own build *guarantees* the DLL is
there. `daw-sdk` is built and tested *before* the CMake native build in
reactor order, so on a cold clone or any clean CI lane the shim does not
exist yet when those tests run, and a hard assertion would fail even
though the shim builds perfectly minutes later. (The stronger claim that
the DLL "cannot exist yet" when `daw-sdk` runs would be wrong: on a warm
local tree whose earlier build had `ASIO_SDK_DIR` set,
`target/build/native/asioshim.dll` survives from that build and is
present the whole time. Its presence is just not something `daw-sdk` can
*rely* on — which reaches the same conclusion by the honest route.) Its
asioshim guard can therefore only ever be an unconditional `assumeTrue`.

What that proves depends on the host. The lane runs on a GitHub-hosted
`windows-latest` runner, which has no ASIO driver installed, so both
streaming integration tests stop at their "no ASIO driver installed"
assumption and never attempt an open — on that lane it is the
DLL-presence assertions above that hard-fail. The same variable also
turns a real driver *rejecting* the open into a hard failure instead of
a skip, but that half only bites where a driver is actually present: a
self-hosted Windows runner with an interface, or a developer running
with `DAW_REQUIRE_ASIOSHIM=1`. That is the environment in which a
regression stopping the production streaming path from opening becomes
a failure rather than a skip. On developer workstations with the
variable unset every one of these guards stays an `assumeTrue` skip, so
a fresh clone without the SDK still produces a green build.

## Threading

All enumeration, lifecycle, capability, control-panel, and streaming-control
FFM downcalls run on the dedicated daemon platform thread named `asio-control`,
never the JavaFX or audio render thread. This keeps the SDK's COM
initialization, driver creation, calls, exit, and release in one apartment.
`AsioFormatChangeShim` and `AsioBufferSwitchShim` use a shared FFM arena
because their upcalls are invoked from the vendor driver's callback thread.
Windows C `long` is 32-bit even on x64, so the callback ABI is normalized to
`int32_t` / `ValueLayout.JAVA_INT`, not Java `long`.

### Driver-thread entry points

Four functions in `asioshim.cpp` run on the *vendor driver's* real-time audio
thread rather than on `asio-control`. They are the four `ASIOCallbacks` slots
`asioshim_createBuffers` installs:

| `ASIOCallbacks` slot | Function | Exported? |
| --- | --- | --- |
| `bufferSwitch` | `asioshim_bufferSwitchTrampoline` | yes (story 311) |
| `asioMessage` | `asioshim_messageTrampoline` | yes (story 218) |
| `bufferSwitchTimeInfo` | `shimBufferSwitchTimeInfo` | no — `ASIOTime*` has no useful FFM mapping |
| `sampleRateDidChange` | `shimSampleRateDidChange` | no — forwards to the message trampoline as `kAsioResetRequest` |

**All four are deliberately lock-free.** Every host-called export in
`asioshim.cpp` takes the shim's `g_driverMutex`; these four must never take
it, for two reasons:

1. **RT violation.** A mutex acquisition inside the audio callback is an
   unbounded, priority-inversion-prone blocking operation.
2. **Deadlock.** A control thread inside `asioshim_stop` holds `g_driverMutex`
   while `ASIOStop()` waits for the driver's callback thread to quiesce — a
   callback parked on that same mutex would never return.

The only synchronisation on that path is `std::atomic`, and it takes two
cooperating mechanisms:

- **State gates.** A null callback pointer (after
  `uninstallAsioBufferSwitchCallback`) makes the trampoline a cheap no-op, and
  the "buffers created" flag is published `false` *before* the SDK teardown so
  a callback that has not started yet returns without touching disposed state.
- **An in-flight barrier.** Both gates are read once, at callback entry, so
  neither says anything about a callback that is already past them — and the
  story-311 driver-reset path really does tear down from a different thread
  while the driver is still running. Every callback therefore counts itself
  into an atomic in-flight counter for the duration of its body, and
  `asioshim_stop`, `asioshim_disposeBuffers` and
  `uninstallAsioBufferSwitchCallback` each drain that counter to zero before
  they let the SDK free the driver's buffers or let Java free the upcall
  stub's arena. The drain spins on `std::this_thread::yield()` and is bounded
  (~100 ms against a monotonic clock), so a misbehaving driver can delay, but
  never hang, `asio-control`. Draining while holding `g_driverMutex` is safe
  precisely because the callback path never takes that mutex.
  `asioshim_messageTrampoline` is guarded the same way over its own,
  deliberately separate counter, drained only by
  `uninstallAsioMessageCallback` (which, like the trampoline it drains, takes
  no mutex at all) — so the buffer teardown never waits on message callbacks,
  and `AsioFormatChangeShim.close()` may free its stub's arena only once that
  uninstall downcall has returned normally.

`ASIOOutputReady()` is likewise called natively from the trampoline rather
than from Java, so no part of the ASIO driver API is ever invoked from
inside the upcall — the Java side never calls back down into the SDK.

That is a statement about the *native* boundary, not a claim that the
upcall is nothing but `memcpy`. `AsioBufferSwitchShim.bufferSwitch` also
does two non-copy things, both deliberate and both RT-admissible:
`LockSupport.unpark(drainThread)` after the input hand-off (wait-free, and
the reason a stalled drain thread never holds up the driver), and, since
the story-316 review, a `volatile long renderedBlocksConsumed++` on the
branch where it takes an engine-rendered block — a single-writer
load-add-store, since the driver's callback thread is the only mutator.
Nothing that allocates, locks or CAS-retries may join them; such work
belongs on the drain thread.

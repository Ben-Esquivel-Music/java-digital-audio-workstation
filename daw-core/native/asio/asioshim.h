// asioshim.h — public ABI documentation for the asioshim native bridge.
//
// This header is intended for documentation and (optional) inclusion by
// other native code that calls into asioshim. The Java side does NOT
// include this header — it resolves the exported symbols at runtime via
// FFM (JEP 454) `SymbolLookup.libraryLookup("asioshim", arena)`.
//
// ──────────────────────────────────────────────────────────────────
// Calling convention
// ──────────────────────────────────────────────────────────────────
// On x86-64 Windows (the only supported target — see story 224 non-goals)
// every export uses the platform default x64 calling convention. There
// is no `__stdcall` decoration on x64; symbol names are unmangled
// because each export is declared `extern "C"` in `asioshim.cpp`.
//
// On x86-32 Windows the ABI would be `__stdcall` to match the Steinberg
// SDK's host glue, but a 32-bit Windows build is explicitly out of scope
// for the current iteration; the CMakeLists.txt only configures an x64
// `SHARED` library.
//
// ──────────────────────────────────────────────────────────────────
// Return-code conventions
// ──────────────────────────────────────────────────────────────────
// Most functions return `1` (`SHIM_OK`) for `ASE_OK` and `0`
// (`SHIM_FAIL`) otherwise. Two exceptions:
//
//   - `asioshim_openControlPanel` returns `1` on `ASE_OK`,
//     `0` on `ASE_NotPresent` (driver has no control panel),
//     `-1` for any other ASIO error.
//   - `asioshim_setClockSource` returns the raw `ASIOError` directly
//     (0 on `ASE_OK`, negative on the SDK's standard error codes).
//
// `asioshim_stop` and `asioshim_disposeBuffers` (story 311) are idempotent
// teardown steps the Java `close()` path calls unconditionally, so they
// return `1` when there is *nothing to do*: no driver loaded, not started,
// or no buffers created.
//
// They do **not** report `1` when the driver refuses. If `ASIOStop()` or
// `ASIODisposeBuffers()` returns anything other than `ASE_OK` these return
// `0`, because a driver that may still be firing `bufferSwitch` is worth
// surfacing. "Nothing to do" is a success; "the driver said no" is a failure.
//
// That failure is a diagnostic, not a stop signal. The Java caller
// (`AsioBackend#tearDownStreaming`) logs it at WARNING and continues the
// teardown: the upcall stub is protected by the bounded in-flight callback
// barrier and by the null callback pointer `uninstallAsioBufferSwitchCallback`
// publishes, not by `ASIOStop` having succeeded — so aborting on a refusal
// would skip the uninstall that makes the caller's arena free safe.
//
// ──────────────────────────────────────────────────────────────────
// Thread affinity
// ──────────────────────────────────────────────────────────────────
// All capability, control-panel, and streaming-control exports require a
// successfully loaded driver. Callers must use asioshim_loadDriver first;
// unloaded calls fail without entering the Steinberg SDK. None of these
// lifecycle/capability/streaming-control exports may be invoked on the
// audio render thread — or on the driver's own callback thread, which the
// ASIO contract forbids.
//
// Two exports are never called by the host at all. They *are* driver
// callbacks: `asioshim_createBuffers` installs them into the process-global
// `ASIOCallbacks` table and the vendor driver invokes them on its own
// real-time audio thread —
//   - `asioshim_bufferSwitchTrampoline` → `ASIOCallbacks::bufferSwitch`
//   - `asioshim_messageTrampoline`      → `ASIOCallbacks::asioMessage`
// Two further driver-thread entry points are file-local to `asioshim.cpp`
// and deliberately not exported, because their signatures have no useful
// FFM mapping: the `ASIOCallbacks::bufferSwitchTimeInfo` and
// `ASIOCallbacks::sampleRateDidChange` slots. All four are lock-free; see
// the REAL-TIME DISCIPLINE banner in `asioshim.cpp`.
//
// ──────────────────────────────────────────────────────────────────
// Pointer ABIs
// ──────────────────────────────────────────────────────────────────
//
// `asioshim_listDrivers` writes a fixed 168-byte struct per entry:
//   offset   0..128  char name[128]   ASCII, NUL-terminated
//   offset 128..168  char clsid[40]   canonical GUID string, NUL-terminated
// `*outCount` is capacity-in / actual-written-out. Actual is always capped
// at the input capacity. Capacity zero is a successful no-op; negative
// capacity or a null output buffer with positive capacity fails. Driver names
// are the canonical load keys and device ids; CLSIDs are diagnostic metadata.
//
// `asioshim_getDriverInfo` writes a fixed 264-byte normalized record:
//   offset   0..4    int32 asioVersion
//   offset   4..8    int32 driverVersion
//   offset   8..136  char name[128]          ASCII, NUL-terminated
//   offset 136..264  char errorMessage[128]  ASCII, NUL-terminated
// This is deliberately not the native ASIODriverInfo memory layout.
//
// `asioshim_getClockSources` writes a fixed 48-byte struct per entry:
//   offset 0..32   char name[32]    ASCII, NUL-terminated
//   offset 32..36  int32 index
//   offset 36..40  int32 associatedChannel
//   offset 40..44  int32 associatedGroup
//   offset 44..48  int32 isCurrentSource (0 or 1)
//
// `asioshim_getChannelInfo` writes a fixed 56-byte struct:
//   offset  0..4   int32 channel
//   offset  4..8   int32 isInput  (1 = input, 0 = output)
//   offset  8..12  int32 isActive
//   offset 12..16  int32 channelGroup
//   offset 16..20  int32 type      (ASIOSampleType)
//   offset 20..24  int32 reserved (padding so name starts at byte 24)
//   offset 24..56  char  name[32]  ASCII, NUL-terminated
//
// `asioshim_getBufferInfos` writes a fixed 32-byte struct per active
// channel (story 311). Records appear in exactly the order passed to
// `asioshim_createBuffers`: all inputs first (in `inputChannels` order),
// then all outputs (in `outputChannels` order):
//   offset  0..4   int32 channel     driver channel index
//   offset  4..8   int32 isInput     (1 = input, 0 = output)
//   offset  8..12  int32 sampleType  driver `ASIOSampleType`, -1 if the
//                                    driver refused to report one
//   offset 12..16  int32 reserved    always 0 — padding so the two
//                                    addresses below are 8-byte aligned
//   offset 16..24  int64 buffer0     address of `ASIOBufferInfo.buffers[0]`
//   offset 24..32  int64 buffer1     address of `ASIOBufferInfo.buffers[1]`
// The two addresses are the driver's own double-buffer halves; the JVM
// reads and writes them directly because the sample-format conversion
// boundary lives in Java. Story 312 converts the whole ASIOSampleType
// matrix there (`AsioSampleType`), keyed off the `sampleType` field above,
// which is why no `asioshim_getChannelSampleType` export exists. They are
// valid only between a successful `asioshim_createBuffers` and the matching
// `asioshim_disposeBuffers` / `asioshim_unloadDriver`.
//
// All struct layouts are normalised to 32-bit integer fields (plus the
// two 64-bit addresses above) so the Java FFM layer can use
// `ValueLayout.JAVA_INT` / `JAVA_LONG` without tracking the SDK's
// platform-dependent `long` width.

#ifndef ASIOSHIM_H_
#define ASIOSHIM_H_

#include <stdint.h>

#if defined(_WIN32)
#  if defined(ASIOSHIM_EXPORTS)
#    define ASIOSHIM_DECL __declspec(dllexport)
#  else
#    define ASIOSHIM_DECL __declspec(dllimport)
#  endif
#else
#  define ASIOSHIM_DECL
#endif

#ifdef __cplusplus
#  define ASIOSHIM_API extern "C" ASIOSHIM_DECL
#else
#  define ASIOSHIM_API ASIOSHIM_DECL
#endif

// ── Windows-only `long` precondition ──────────────────────────────
// The two callback typedefs below spell Steinberg's native `long` so they
// stay ABI-exact against `ASIOCallbacks`. asioshim is a Windows-x64-only
// artefact (story 224 non-goals) and Windows is LLP64, so `long` is 32 bits
// and maps to `ValueLayout.JAVA_INT` on the Java FFM side — never Java
// `long`. On an LP64 host `long` would be 64 bits and that mapping would
// silently become false, so the precondition is asserted here for anyone who
// includes this header, as well as in `asioshim.cpp`.
#if defined(_WIN32)
#  if defined(__cplusplus)
static_assert(sizeof(long) == 4,
              "asioshim requires the Windows LLP64 32-bit C long ABI");
#  elif defined(__STDC_VERSION__) && __STDC_VERSION__ >= 201112L
_Static_assert(sizeof(long) == 4,
               "asioshim requires the Windows LLP64 32-bit C long ABI");
#  endif
#endif

// ── Driver discovery and lifecycle (story 310) ────────────────────
ASIOSHIM_API int  asioshim_listDrivers(void* outArray, int* outCount);
ASIOSHIM_API int  asioshim_loadDriver(const char* driverName);
ASIOSHIM_API int  asioshim_getDriverName(void* outName, int nameCapacity);
ASIOSHIM_API int  asioshim_getDriverInfo(void* outInfo);
ASIOSHIM_API void asioshim_unloadDriver(void);

// ── Capability queries (story 130 / 213) ──────────────────────────
ASIOSHIM_API int  asioshim_getBufferSize(int* min, int* max,
                                          int* preferred, int* granularity);
ASIOSHIM_API int  asioshim_canSampleRate(double rate);
ASIOSHIM_API int  asioshim_getSampleRate(double* outRate);
ASIOSHIM_API int  asioshim_setSampleRate(double rate);

// ── Driver control panel (story 212) ──────────────────────────────
ASIOSHIM_API int  asioshim_openControlPanel(void);

// ── Hardware clock source (story 216) ─────────────────────────────
ASIOSHIM_API int  asioshim_getClockSources(void* outArray, int* outCount);
ASIOSHIM_API int  asioshim_setClockSource(int reference);

// ── Channel info (story 215) ──────────────────────────────────────
ASIOSHIM_API int  asioshim_getChannelCount(int* outInputs, int* outOutputs);
ASIOSHIM_API int  asioshim_getChannelInfo(int channelIndex, int isInput,
                                           void* outInfo);

// ── Format-change host-callback bridge (story 218) ────────────────
// Signature for the upcall pointer the JVM hands to
// `installAsioMessageCallback`. Matches Steinberg's `asioMessage`:
// Keep the native spelling as `long` so this function-pointer type is exactly
// compatible with Steinberg's ASIOCallbacks::asioMessage declaration. Windows
// uses LLP64, so `long` remains 32 bits on x64 and maps to JAVA_INT at the Java
// FFM boundary (see the static_assert above, and its twin in asioshim.cpp).
//   long asioMessage(long selector, long value,
//                    void* message, double* opt);
typedef long (*asio_message_fn)(long selector, long value,
                                void* message, double* opt);

ASIOSHIM_API void installAsioMessageCallback(void* callback);

// Clears the installed upcall and then waits (bounded) for any asioMessage
// callback already inside `asioshim_messageTrampoline` to return — the
// mirror of `uninstallAsioBufferSwitchCallback` below — so the caller can
// safely free the upcall stub's arena as soon as this returns normally.
ASIOSHIM_API void uninstallAsioMessageCallback(void);

// This function *is* Steinberg's `ASIOCallbacks::asioMessage` slot, not a
// wrapper around it: `asioshim_createBuffers` (story 311) assigns it into
// the process-global callback table, and the vendor driver calls it directly
// on its own thread. It loads the callback installed by the JVM and forwards
// the selector / value / message / opt arguments verbatim, returning 0 when
// no callback is installed.
ASIOSHIM_API long asioshim_messageTrampoline(
        long selector, long value, void* message, double* opt);

// ── Real-time streaming (story 311) ───────────────────────────────
// Lifecycle contract, all on the `asio-control` thread:
//   asioshim_createBuffers → asioshim_getBufferInfos → asioshim_start
//     … streaming … → asioshim_stop → asioshim_disposeBuffers
// `asioshim_createBuffers` requires a loaded driver and no already-created
// buffers; it also populates the process-global `ASIOCallbacks` table, which
// is what finally makes `asioshim_messageTrampoline` above reachable.
// `numInputs + numOutputs` is capped at 64 **total active channels** — the
// cap is on the two directions combined, so a symmetric configuration tops
// out at 32 in + 32 out.
// `asioshim_stop` and `asioshim_disposeBuffers` return `1` when there is
// nothing to do, so `close()` can call them unconditionally, and `0` when
// the driver refused the SDK call (see "Return-code conventions" above).
// `asioshim_unloadDriver` runs the same stop + dispose teardown before
// `ASIOExit`.
//
// Every teardown step waits (bounded, best-effort) for any `bufferSwitch`
// already executing on the driver's thread to return before it lets the SDK
// or the JVM free anything that callback touches.
ASIOSHIM_API int  asioshim_createBuffers(const int* inputChannels,
                                          int numInputs,
                                          const int* outputChannels,
                                          int numOutputs,
                                          int bufferFrames);
ASIOSHIM_API int  asioshim_getBufferInfos(void* outArray, int* outCount);
ASIOSHIM_API int  asioshim_start(void);
ASIOSHIM_API int  asioshim_stop(void);
ASIOSHIM_API int  asioshim_disposeBuffers(void);

// Signature of the JVM upcall the shim invokes from each bufferSwitch.
// The native `long` spelling is retained so the trampoline below is ABI-exact
// against Steinberg's `ASIOCallbacks::bufferSwitch` (`ASIOBool` is itself a
// `long`); Windows is LLP64, so `long` is 32 bits on x64 and maps to
// `ValueLayout.JAVA_INT` at the Java FFM boundary — see the static_assert
// above, and its twin in asioshim.cpp.
//   void bufferSwitch(long doubleBufferIndex, ASIOBool directProcess);
typedef void (*asio_buffer_switch_fn)(long bufferIndex, long directProcess);

ASIOSHIM_API void installAsioBufferSwitchCallback(void* callback);

// Clears the installed upcall and then waits (bounded) for any callback
// already inside the shim to return, so the caller can safely free the
// upcall stub's arena as soon as this returns.
ASIOSHIM_API void uninstallAsioBufferSwitchCallback(void);

// This function *is* Steinberg's `ASIOCallbacks::bufferSwitch` slot, exactly
// as `asioshim_messageTrampoline` is the `asioMessage` slot:
// `asioshim_createBuffers` assigns it into the callback table and the vendor
// driver invokes it on its real-time audio thread. It is exported rather than
// file-local so both driver-invoked trampolines are visible in the DLL's
// symbol table for diagnostics.
//
// Together with `asioshim_messageTrampoline` (and the two non-exported slots
// listed under "Thread affinity" above) it runs on the driver's thread and
// deliberately takes no lock, while every host-called export locks the shim's
// driver mutex: a lock here would be an RT violation and would deadlock
// against a concurrent `asioshim_stop`. See the story-311 banner in
// asioshim.cpp.
ASIOSHIM_API void asioshim_bufferSwitchTrampoline(long bufferIndex,
                                                   long directProcess);

#endif  // ASIOSHIM_H_

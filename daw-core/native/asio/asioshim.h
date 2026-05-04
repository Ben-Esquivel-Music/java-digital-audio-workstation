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
// ──────────────────────────────────────────────────────────────────
// Pointer ABIs
// ──────────────────────────────────────────────────────────────────
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
// Both struct layouts are normalised to 32-bit integer fields so the
// Java FFM layer can use `ValueLayout.JAVA_INT` without tracking the
// SDK's platform-dependent `long` width.

#ifndef ASIOSHIM_H_
#define ASIOSHIM_H_

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
//   long asioMessage(long selector, long value,
//                    void* message, double* opt);
typedef long (*asio_message_fn)(long selector, long value,
                                 void* message, double* opt);

ASIOSHIM_API void installAsioMessageCallback(void* callback);
ASIOSHIM_API void uninstallAsioMessageCallback(void);

// Trampoline routed into Steinberg's `ASIOCallbacks::asioMessage` slot
// by the SDK glue when asioshim is built with
// `-DASIOSHIM_TRAMPOLINE`; loads the callback installed by the JVM and
// forwards the selector / value / message / opt arguments verbatim.
ASIOSHIM_API long asioshim_messageTrampoline(long selector, long value,
                                              void* message, double* opt);

#endif  // ASIOSHIM_H_

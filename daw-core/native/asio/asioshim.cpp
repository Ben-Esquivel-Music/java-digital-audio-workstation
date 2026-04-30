// asioshim.cpp — thin native bridge to the Steinberg ASIO SDK exposed
// to Java via FFM (JEP 454). See README.md and the Java counterpart
// daw-sdk/.../AsioCapabilityShim.java for the contract.
//
// Story 130 / 213 — Driver-Reported Buffer Size and Sample-Rate
// Enumeration. Story 218 — Format-change host-callback bridge.
//
// Threading: all symbols are called from the JVM thread that holds the
// Audio Settings dialog (typically the JavaFX thread). They must never
// be called from the audio render thread.

#if defined(_WIN32)
#  define ASIOSHIM_EXPORT extern "C" __declspec(dllexport)
#else
#  define ASIOSHIM_EXPORT extern "C" __attribute__((visibility("default")))
#endif

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <atomic>

// The Steinberg ASIO SDK headers live under ${ASIO_SDK_DIR}/common.
// We forward-declare the few entry points we use so this translation
// unit compiles without the SDK on disk; CMake links the actual
// implementations from the SDK's host glue (asio.cpp / asiodrivers.cpp).
//
// The Steinberg SDK uses the type ASIOError (an int) and ASIOSampleRate
// (a double).
typedef long ASIOError;
typedef double ASIOSampleRate;

extern "C" ASIOError ASIOGetBufferSize(long* minSize, long* maxSize,
                                       long* preferredSize, long* granularity);
extern "C" ASIOError ASIOCanSampleRate(ASIOSampleRate sampleRate);
extern "C" ASIOError ASIOGetSampleRate(ASIOSampleRate* sampleRate);
extern "C" ASIOError ASIOSetSampleRate(ASIOSampleRate sampleRate);
extern "C" ASIOError ASIOControlPanel(void);

// ASIOClockSource as defined in Steinberg's asio.h. The Steinberg
// declaration uses long (32-bit) integer fields and a 32-byte
// fixed-length name buffer (ASCII, NUL-terminated). The shim's FFM
// contract normalises that layout into a fixed 48-byte struct with
// int32 fields so the Java side does not need to track the SDK's
// platform-dependent long width.
struct ASIOClockSource {
    long index;
    long associatedChannel;
    long associatedGroup;
    long isCurrentSource;
    char name[32];
};
extern "C" ASIOError ASIOGetClockSources(ASIOClockSource* clocks, long* numSources);
extern "C" ASIOError ASIOSetClockSource(long reference);

// Steinberg's ASE_OK is 0 in the SDK, but the FFM contract documented
// in AsioCapabilityShim and AsioFormatChangeShim normalises "OK" to 1
// (so the Java side can treat a missing symbol or RPC failure as 0
// without ambiguity). Translate here at the boundary.
namespace {
    constexpr int SHIM_OK = 1;
    constexpr int SHIM_FAIL = 0;
    constexpr ASIOError ASE_OK = 0;
    // Subset of Steinberg ASIO error codes used at the FFM boundary.
    // Steinberg defines ASE_NotPresent = -1000 in asio.h, but the shim
    // contract documented in AsioBackend / AsioCapabilityShim normalises
    // "driver does not provide a control panel" to 0 so the Java side
    // can treat any negative value as a generic failure without parsing
    // the SDK's full error enum.
    constexpr ASIOError ASE_NotPresent = -1000;
    constexpr int SHIM_NOT_PRESENT = 0;
    constexpr int SHIM_GENERIC_FAIL = -1;
}

ASIOSHIM_EXPORT int asioshim_getBufferSize(int* min, int* max,
                                           int* preferred, int* granularity) {
    if (!min || !max || !preferred || !granularity) {
        return SHIM_FAIL;
    }
    long mn = 0, mx = 0, pr = 0, gr = 0;
    ASIOError err = ASIOGetBufferSize(&mn, &mx, &pr, &gr);
    if (err != ASE_OK) {
        return SHIM_FAIL;
    }
    *min = static_cast<int>(mn);
    *max = static_cast<int>(mx);
    *preferred = static_cast<int>(pr);
    *granularity = static_cast<int>(gr);
    return SHIM_OK;
}

ASIOSHIM_EXPORT int asioshim_canSampleRate(double rate) {
    return (ASIOCanSampleRate(static_cast<ASIOSampleRate>(rate)) == ASE_OK)
           ? SHIM_OK : SHIM_FAIL;
}

ASIOSHIM_EXPORT int asioshim_getSampleRate(double* outRate) {
    if (!outRate) {
        return SHIM_FAIL;
    }
    ASIOSampleRate sr = 0.0;
    ASIOError err = ASIOGetSampleRate(&sr);
    if (err != ASE_OK) {
        return SHIM_FAIL;
    }
    *outRate = static_cast<double>(sr);
    return SHIM_OK;
}

ASIOSHIM_EXPORT int asioshim_setSampleRate(double rate) {
    return (ASIOSetSampleRate(static_cast<ASIOSampleRate>(rate)) == ASE_OK)
           ? SHIM_OK : SHIM_FAIL;
}

// Bridges Steinberg's ASIOControlPanel() so the JVM can launch the
// active driver's vendor-supplied modal control panel (story 212).
// The native call blocks the calling thread until the user closes the
// panel; the Java side dispatches it onto a daemon platform thread so
// neither the JavaFX thread nor the audio render thread is pinned.
//
// Return-code mapping at the FFM boundary:
//   SHIM_OK (1)            — ASE_OK; panel was shown and closed normally.
//   SHIM_NOT_PRESENT (0)   — ASE_NotPresent; driver has no control panel.
//   SHIM_GENERIC_FAIL (-1) — any other ASIOError; driver-side failure.
ASIOSHIM_EXPORT int asioshim_openControlPanel(void) {
    ASIOError err = ASIOControlPanel();
    if (err == ASE_OK) {
        return SHIM_OK;
    }
    if (err == ASE_NotPresent) {
        return SHIM_NOT_PRESENT;
    }
    return SHIM_GENERIC_FAIL;
}

// ─── Hardware clock-source bridge (story 216) ───────────────────────
//
// Layout the Java side reads via FFM (matches the comment on
// AsioCapabilityShim.getClockSources): 48 bytes per entry,
//   [0..32)  char name[32]   ASCII, NUL-terminated
//   [32..36) int32 index
//   [36..40) int32 associatedChannel
//   [40..44) int32 associatedGroup
//   [44..48) int32 isCurrentSource (0/1)
//
// outArray must point to a buffer of at least (capacity * 48) bytes
// pre-allocated by the caller. The shim writes up to *outCount entries
// and updates *outCount with the actual entry count reported by the
// driver. Capacity is passed in via *outCount on entry.
//
// Returns SHIM_OK (1) on ASE_OK, SHIM_FAIL (0) otherwise. On failure
// *outCount is set to 0.
ASIOSHIM_EXPORT int asioshim_getClockSources(void* outArray, int* outCount) {
    if (!outArray || !outCount) {
        if (outCount) *outCount = 0;
        return SHIM_FAIL;
    }
    int capacity = *outCount;
    if (capacity <= 0) {
        *outCount = 0;
        return SHIM_FAIL;
    }
    // Allocate a temporary SDK-shaped array; the SDK's struct has a
    // platform-dependent long width that we must not assume matches
    // our normalised int32 layout. Up to 32 sources is the practical
    // ceiling for any hardware shipping today.
    constexpr int MAX = 32;
    if (capacity > MAX) capacity = MAX;
    ASIOClockSource scratch[MAX];
    long n = capacity;
    ASIOError err = ASIOGetClockSources(scratch, &n);
    if (err != ASE_OK || n <= 0) {
        *outCount = 0;
        return SHIM_FAIL;
    }
    if (n > capacity) n = capacity;
    auto* bytes = static_cast<unsigned char*>(outArray);
    for (long i = 0; i < n; ++i) {
        unsigned char* row = bytes + (i * 48);
        // Copy name verbatim (32 bytes); ensure NUL-termination of the
        // last byte so a buggy driver that skips the trailing NUL does
        // not stream past the end on the Java side.
        for (int k = 0; k < 32; ++k) {
            row[k] = static_cast<unsigned char>(scratch[i].name[k]);
        }
        row[31] = '\0';
        // Pack int32 fields little-endian (FFM ValueLayout.JAVA_INT
        // matches host endian, but ASIO only ships on little-endian
        // hosts — Windows x64 / x86 / ARM64 / macOS — so a direct
        // memcpy preserves the contract).
        int32_t idx       = static_cast<int32_t>(scratch[i].index);
        int32_t chan      = static_cast<int32_t>(scratch[i].associatedChannel);
        int32_t group     = static_cast<int32_t>(scratch[i].associatedGroup);
        int32_t isCurrent = static_cast<int32_t>(scratch[i].isCurrentSource);
        std::memcpy(row + 32, &idx, 4);
        std::memcpy(row + 36, &chan, 4);
        std::memcpy(row + 40, &group, 4);
        std::memcpy(row + 44, &isCurrent, 4);
    }
    *outCount = static_cast<int>(n);
    return SHIM_OK;
}

// Wraps ASIOSetClockSource(long reference). Returns the raw ASIOError
// returned by the driver — the Java side translates non-zero codes
// into AudioBackendException with mapped messages
// (ASE_InvalidMode → driver rejects clock change while streaming,
//  ASE_NotPresent  → unknown clock source id, etc).
ASIOSHIM_EXPORT int asioshim_setClockSource(int reference) {
    return static_cast<int>(ASIOSetClockSource(static_cast<long>(reference)));
}

// ─── Format-change host-callback bridge (story 218) ─────────────────
//
// The JVM passes a single function pointer matching ASIO's
// asioMessage(long, long, void*, double*) -> long signature. We store
// it in a process-global slot the SDK's installed callback table
// reads when the driver fires kAsioResetRequest / kAsioBufferSizeChange
// / kAsioResyncRequest. The Java upcall is responsible for mapping
// each selector onto AudioDeviceEvent.FormatChangeRequested.

extern "C" {
    typedef long (*asio_message_fn)(long selector, long value,
                                    void* message, double* opt);
}

namespace {
    std::atomic<asio_message_fn> g_asioMessageCallback{nullptr};
}

ASIOSHIM_EXPORT void installAsioMessageCallback(void* callback) {
    g_asioMessageCallback.store(reinterpret_cast<asio_message_fn>(callback),
                                std::memory_order_release);
}

ASIOSHIM_EXPORT void uninstallAsioMessageCallback() {
    g_asioMessageCallback.store(nullptr, std::memory_order_release);
}

// The SDK's ASIOCallbacks struct contains a slot called asioMessage
// that the host-side glue (asiodrivers.cpp) routes through this
// trampoline. The trampoline is referenced from the SDK glue when
// asioshim is built with -DASIOSHIM_TRAMPOLINE.
ASIOSHIM_EXPORT long asioshim_messageTrampoline(long selector, long value,
                                                void* message, double* opt) {
    asio_message_fn cb = g_asioMessageCallback.load(std::memory_order_acquire);
    if (cb == nullptr) {
        return 0;
    }
    return cb(selector, value, message, opt);
}

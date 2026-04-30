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

// Steinberg's ASE_OK is 0 in the SDK, but the FFM contract documented
// in AsioCapabilityShim and AsioFormatChangeShim normalises "OK" to 1
// (so the Java side can treat a missing symbol or RPC failure as 0
// without ambiguity). Translate here at the boundary.
namespace {
    constexpr int SHIM_OK = 1;
    constexpr int SHIM_FAIL = 0;
    constexpr ASIOError ASE_OK = 0;
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

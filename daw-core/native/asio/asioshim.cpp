// asioshim.cpp — thin native bridge to the Steinberg ASIO SDK exposed
// to Java via FFM (JEP 454). See README.md and the Java counterpart
// daw-sdk/.../AsioCapabilityShim.java for the contract.
//
// Story 130 / 213 — Driver-Reported Buffer Size and Sample-Rate
// Enumeration. Story 218 — Format-change host-callback bridge.
// Story 311 — Real-time streaming (ASIOCreateBuffers / ASIOStart /
// ASIOStop / ASIODisposeBuffers + the bufferSwitch trampolines).
//
// Threading: all host downcalls are serialized by the JVM's dedicated
// `asio-control` platform thread. They must never be called from the audio
// render thread. The cross-thread entrypoints are the four ASIOCallbacks
// slots the vendor driver invokes on its own real-time thread: the
// story-218 asioshim_messageTrampoline (asioMessage), the story-311
// asioshim_bufferSwitchTrampoline (bufferSwitch), and the two file-local
// slots shimBufferSwitchTimeInfo and shimSampleRateDidChange. All four are
// deliberately lock-free; see the banner above the story-311 section.

#define ASIOSHIM_EXPORTS
#include "asioshim.h"
#include "asiosys.h"
#include "asio.h"
#include "asiodrivers.h"

#include <windows.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

#define ASIOSHIM_EXPORT ASIOSHIM_API

// Defined by the SDK's common/asio.cpp; the indexed host-glue open path
// assigns the single process-wide IASIO instance before ASIOInit.
extern IASIO* theAsioDriver;

// Defined by the SDK's host/asiodrivers.cpp as `AsioDrivers* asioDrivers = 0;`.
// asiodrivers.h does not declare it, so we mirror the same extern declaration
// that Steinberg's own common/asio.cpp carries.
//
// story 311 — WHY WE OWN THIS GLOBAL'S LIFETIME. The stock Steinberg
// ASIOExit() is:
//
//     ASIOError ASIOExit(void) {
//         if (theAsioDriver) {
//     #if WINDOWS
//             asioDrivers->removeCurrentDriver();   // no null check
//     ...
//
// and `asioDrivers` is assigned *only* inside loadAsioDriver(char*).
// ShimAsioDrivers::loadAt deliberately bypasses loadAsioDriver — it calls the
// protected asioOpenDriver() so a driver whose registry key differs from its
// description is still loadable by index, and assigns theAsioDriver itself —
// so the SDK global would stay null for the whole process lifetime and
// ASIOExit() would perform a null-`this` member access on
// AsioDrivers::removeCurrentDriver(), which reads and writes this->curIndex.
// We therefore publish our own instance into it on load and clear it before
// destroying that instance, so the SDK's own teardown works and the global
// never dangles. (rtaudio's vendored copy of asio.cpp carries an
// `if (asioDrivers)` patch; the SDK we build against does not.)
extern AsioDrivers* asioDrivers;

static_assert(sizeof(long) == 4,
              "asioshim requires the Windows LLP64 32-bit C long ABI");

// Steinberg's ASE_OK is 0 in the SDK, but the FFM contract documented
// in AsioCapabilityShim and AsioFormatChangeShim normalises "OK" to 1
// (so the Java side can treat a missing symbol or RPC failure as 0
// without ambiguity). Translate here at the boundary.
namespace {
    constexpr int SHIM_OK = 1;
    constexpr int SHIM_FAIL = 0;
    // Subset of Steinberg ASIO error codes used at the FFM boundary.
    // Steinberg defines ASE_NotPresent = -1000 in asio.h, but the shim
    // contract documented in AsioBackend / AsioCapabilityShim normalises
    // "driver does not provide a control panel" to 0 so the Java side
    // can treat any negative value as a generic failure without parsing
    // the SDK's full error enum.
    constexpr int SHIM_NOT_PRESENT = 0;
    constexpr int SHIM_GENERIC_FAIL = -1;

    constexpr int DRIVER_CAPACITY = 64;
    constexpr int DRIVER_NAME_BYTES = 128;
    constexpr int DRIVER_CLSID_BYTES = 40;
    constexpr int DRIVER_RECORD_STRIDE = DRIVER_NAME_BYTES + DRIVER_CLSID_BYTES;
    constexpr int DRIVER_INFO_STRIDE = 264;

    std::mutex g_driverMutex;
    class ShimAsioDrivers final : public AsioDrivers {
    public:
        long count() {
            return asioGetNumDev();
        }

        bool nameAt(long index, char* name, int capacity) {
            return asioGetDriverName(index, name, capacity) == 0;
        }

        bool clsidAt(long index, CLSID* clsid) {
            return asioGetDriverCLSID(index, clsid) == 0;
        }

        bool loadAt(long index) {
            void* driver = nullptr;
            if (asioOpenDriver(index, &driver) != 0 || !driver) {
                return false;
            }
            theAsioDriver = static_cast<IASIO*>(driver);
            curIndex = index;
            return true;
        }
    };

    std::unique_ptr<ShimAsioDrivers> g_drivers;
    bool g_driverLoaded = false;
    ASIODriverInfo g_driverInfo{};
    std::array<char, DRIVER_NAME_BYTES> g_activeDriverName{};

    ShimAsioDrivers& driverList() {
        if (!g_drivers) {
            g_drivers = std::make_unique<ShimAsioDrivers>();
            // Keep the SDK's process-global in step with ours the moment the
            // manager exists — see the extern declaration at the top of this
            // file (story 311). g_drivers non-null always implies
            // asioDrivers == g_drivers.get().
            asioDrivers = g_drivers.get();
        }
        return *g_drivers;
    }

    void copyFixedAscii(unsigned char* destination, std::size_t capacity,
                        const char* source,
                        std::size_t sourceCapacity = static_cast<std::size_t>(-1)) {
        std::memset(destination, 0, capacity);
        if (!source || capacity == 0) {
            return;
        }
        std::size_t count = 0;
        while (count + 1 < capacity && count < sourceCapacity
                && source[count] != '\0') {
            unsigned char value = static_cast<unsigned char>(source[count]);
            destination[count] = (value >= 0x20 && value <= 0x7e) ? value : '?';
            ++count;
        }
    }

    std::array<char, DRIVER_CLSID_BYTES> formatClsid(const CLSID& value) {
        std::array<char, DRIVER_CLSID_BYTES> clsid{};
        wchar_t wide[DRIVER_CLSID_BYTES]{};
        if (StringFromGUID2(value, wide, DRIVER_CLSID_BYTES) <= 0) {
            return clsid;
        }
        int converted = WideCharToMultiByte(
                CP_ACP, 0, wide, -1, clsid.data(), DRIVER_CLSID_BYTES,
                nullptr, nullptr);
        if (converted <= 0) {
            clsid.fill('\0');
        }
        clsid.back() = '\0';
        return clsid;
    }

    // ─── Real-time streaming state (story 311) ──────────────────────
    //
    // g_buffersCreated, g_outputReadySupported and g_callbacksInFlight are
    // std::atomic because the driver's real-time bufferSwitch trampoline
    // reads and writes them without taking g_driverMutex (see the story-311
    // banner near the bottom of this file). Everything else here is written
    // and read only on the `asio-control` thread while g_driverMutex is held.
    //
    // MAX_STREAM_CHANNELS caps inputs + outputs *combined*, so a symmetric
    // configuration tops out at 32 in + 32 out.
    constexpr int MAX_STREAM_CHANNELS = 64;
    constexpr int BUFFER_INFO_STRIDE = 32;
    // Not a valid ASIOSampleType; means "the driver refused to report a
    // sample type for this channel". The Java side treats anything other
    // than ASIOSTFloat32LSB as unsupported (story 312 generalises it).
    constexpr int32_t SAMPLE_TYPE_UNKNOWN = -1;
    // Upper bound on how long a control-path teardown waits for in-flight
    // driver callbacks to drain. Best-effort by design: a driver whose
    // bufferSwitch never returns must not hang the `asio-control` thread.
    constexpr int DRAIN_TIMEOUT_MS = 100;

    // The driver retains &g_callbacks for the whole lifetime of the
    // created buffers, so the table must outlive asioshim_createBuffers.
    ASIOCallbacks g_callbacks{};
    std::array<ASIOBufferInfo, MAX_STREAM_CHANNELS> g_bufferInfos{};
    std::array<int32_t, MAX_STREAM_CHANNELS> g_sampleTypes{};
    int g_bufferInfoCount = 0;
    std::atomic<bool> g_buffersCreated{false};
    std::atomic<bool> g_outputReadySupported{false};
    // Number of driver callbacks currently executing inside
    // shimBufferSwitchBody. Teardown drains this to zero so it can be sure
    // no callback is still writing into the driver's buffers or calling the
    // JVM upcall stub the Java side is about to free.
    std::atomic<int> g_callbacksInFlight{0};
    bool g_started = false;

    // RAII counter for shimBufferSwitchBody: increments on construction and
    // decrements on *every* exit path, including an early return. Lock-free
    // and allocation-free, so it is safe on the driver's real-time thread.
    class CallbackInFlightGuard final {
    public:
        // seq_cst on the way in: it has to participate in the total order
        // described on drainInFlightCallbacks(). Plain release is enough on
        // the way out — the decrement happens after everything the body
        // touched, and a draining thread only ever needs to not see it too
        // early.
        CallbackInFlightGuard() {
            g_callbacksInFlight.fetch_add(1, std::memory_order_seq_cst);
        }

        ~CallbackInFlightGuard() {
            g_callbacksInFlight.fetch_sub(1, std::memory_order_release);
        }

        CallbackInFlightGuard(const CallbackInFlightGuard&) = delete;
        CallbackInFlightGuard& operator=(const CallbackInFlightGuard&) = delete;
    };

    // Waits until no driver callback is inside shimBufferSwitchBody.
    //
    // This is the barrier the "buffers created" flag alone cannot provide:
    // the flag is read once at callback entry, so flipping it does nothing
    // for a callback that is already past the check.
    //
    // Six operations are sequentially consistent so that they share one
    // total order: the counter increment, the g_buffersCreated load and the
    // g_bufferSwitchCallback load in the callback; the g_buffersCreated
    // store in clearBufferStateLocked(); the g_bufferSwitchCallback store in
    // uninstallAsioBufferSwitchCallback(); and the counter load below. The
    // consequence is that a callback which still observes the pre-teardown
    // value of either gate must appear before the corresponding store in
    // that total order, hence its increment does too, hence this counter
    // load sees it. A release store followed by an acquire load would leave
    // the classic store-buffer hole — StoreLoad is the one reordering
    // acquire/release do not forbid.
    //
    // Safe to call while holding g_driverMutex: the callback path never
    // takes that mutex (see the story-311 real-time banner), so a draining
    // control thread can never be waiting on a callback parked behind it.
    // Bounded by DRAIN_TIMEOUT_MS against a monotonic clock, so the loop
    // always terminates.
    void drainInFlightCallbacks() {
        std::chrono::steady_clock::time_point deadline =
                std::chrono::steady_clock::now()
                        + std::chrono::milliseconds(DRAIN_TIMEOUT_MS);
        while (g_callbacksInFlight.load(std::memory_order_seq_cst) != 0) {
            if (std::chrono::steady_clock::now() >= deadline) {
                return;
            }
            std::this_thread::yield();
        }
    }

    // Zeroes every cached streaming fact. Callers must already hold
    // g_driverMutex. g_buffersCreated is published false first so a late
    // driver callback observes "no buffers" before the cache is torn down;
    // callers that then re-enter the SDK must also drainInFlightCallbacks()
    // to be sure no callback is still mid-body.
    void clearBufferStateLocked() {
        g_buffersCreated.store(false, std::memory_order_seq_cst);
        g_outputReadySupported.store(false, std::memory_order_release);
        g_bufferInfos.fill(ASIOBufferInfo{});
        g_sampleTypes.fill(SAMPLE_TYPE_UNKNOWN);
        g_bufferInfoCount = 0;
    }

    // Returns false only when the driver itself refused ASIOStop(). A stop
    // with nothing running is a successful no-op.
    bool stopStreamingLocked() {
        if (!g_started) {
            return true;
        }
        // Clear the flag first so a subsequent failure inside the SDK still
        // leaves the shim in the "stopped" state (idempotency contract).
        g_started = false;
        ASIOError err = ASIOStop();
        // A conforming driver has already joined its callback thread by the
        // time ASIOStop returns. Drain anyway: the story-311 driver-reset
        // path tears down from a different thread while the driver is still
        // running, so a callback really can be mid-body here.
        drainInFlightCallbacks();
        return err == ASE_OK;
    }

    // Returns false when the driver refused ASIOStop() or
    // ASIODisposeBuffers(); true when there was simply nothing to dispose.
    bool disposeBuffersLocked() {
        bool stopped = stopStreamingLocked();
        bool hadBuffers = g_buffersCreated.load(std::memory_order_seq_cst);
        clearBufferStateLocked();
        if (!hadBuffers) {
            return stopped;
        }
        // No callback may still be inside the body when the driver frees
        // the very buffers that body writes into.
        drainInFlightCallbacks();
        ASIOError err = ASIODisposeBuffers();
        return stopped && err == ASE_OK;
    }

    void unloadDriverLocked() {
        if (g_driverLoaded) {
            // story 311: stop and dispose before ASIOExit so a close() that
            // skips the explicit teardown still leaves the vendor driver
            // clean rather than exiting with live buffers. The status is
            // deliberately ignored — unload is unconditional.
            (void) disposeBuffersLocked();
            ASIOExit();
        }
        clearBufferStateLocked();
        g_started = false;
        g_callbacks = {};
        if (g_drivers) {
            // When ASIOExit() ran above it already reached this same object
            // through the SDK's `asioDrivers` global; removeCurrentDriver()
            // leaves curIndex == -1, so this second call is a no-op. It
            // still matters for the paths that never got as far as ASIOInit.
            g_drivers->removeCurrentDriver();
            // Clear the SDK global before destroying the object it points
            // at — see the extern declaration at the top of this file.
            asioDrivers = nullptr;
            g_drivers.reset();
        }
        g_driverLoaded = false;
        g_driverInfo = {};
        g_activeDriverName.fill('\0');
    }
}

// ─── Driver enumeration and lifecycle (story 310) ──────────────────────────

ASIOSHIM_EXPORT int asioshim_listDrivers(void* outArray, int* outCount) {
    if (!outCount) {
        return SHIM_FAIL;
    }
    int capacity = *outCount;
    *outCount = 0;
    if (capacity < 0) {
        return SHIM_FAIL;
    }
    if (capacity == 0) {
        return SHIM_OK;
    }
    if (!outArray) {
        return SHIM_FAIL;
    }
    capacity = std::min(capacity, DRIVER_CAPACITY);
    std::lock_guard<std::mutex> lock(g_driverMutex);
    try {
        // Closed-state enumeration is a self-contained COM snapshot. Construct
        // and destroy it on asio-control rather than retaining CoInitialize
        // until a later load. While a driver is active, reuse its manager so
        // the SDK's process-global `asioDrivers` pointer is not disturbed.
        std::unique_ptr<ShimAsioDrivers> localSnapshot;
        ShimAsioDrivers* snapshot = nullptr;
        if (g_driverLoaded && g_drivers) {
            snapshot = g_drivers.get();
        } else {
            localSnapshot = std::make_unique<ShimAsioDrivers>();
            snapshot = localSnapshot.get();
        }
        std::array<std::array<char, DRIVER_NAME_BYTES>, DRIVER_CAPACITY> storage{};
        long found = snapshot->count();
        int written = std::min(capacity,
                static_cast<int>(std::max(0L, found)));
        auto* bytes = static_cast<unsigned char*>(outArray);
        for (int index = 0; index < written; ++index) {
            if (!snapshot->nameAt(index, storage[index].data(), DRIVER_NAME_BYTES)) {
                storage[index][0] = '\0';
            }
            unsigned char* row = bytes + (index * DRIVER_RECORD_STRIDE);
            copyFixedAscii(row, DRIVER_NAME_BYTES, storage[index].data());
            CLSID driverClsid{};
            auto clsid = snapshot->clsidAt(index, &driverClsid)
                    ? formatClsid(driverClsid)
                    : std::array<char, DRIVER_CLSID_BYTES>{};
            copyFixedAscii(row + DRIVER_NAME_BYTES, DRIVER_CLSID_BYTES,
                           clsid.data());
        }
        *outCount = written;
        return SHIM_OK;
    } catch (...) {
        *outCount = 0;
        return SHIM_FAIL;
    }
}

ASIOSHIM_EXPORT int asioshim_loadDriver(const char* driverName) {
    if (!driverName || driverName[0] == '\0') {
        return SHIM_FAIL;
    }
    std::lock_guard<std::mutex> lock(g_driverMutex);
    try {
        // The Steinberg host glue supports one current driver. Re-loading on
        // the same control thread always exits and releases the old COM object
        // before attempting the replacement.
        unloadDriverLocked();
        std::array<char, DRIVER_NAME_BYTES> mutableName{};
        copyFixedAscii(reinterpret_cast<unsigned char*>(mutableName.data()),
                       mutableName.size(), driverName);
        g_drivers = std::make_unique<ShimAsioDrivers>();
        // story 311: publish our manager into the SDK's process-global
        // `asioDrivers` before anything can reach ASIOExit(), which
        // dereferences it without a null check. See the extern declaration
        // at the top of this file.
        asioDrivers = g_drivers.get();
        long matchingIndex = -1;
        long count = std::min(driverList().count(),
                              static_cast<long>(DRIVER_CAPACITY));
        for (long index = 0; index < count; ++index) {
            std::array<char, DRIVER_NAME_BYTES> installedName{};
            if (!driverList().nameAt(index, installedName.data(), DRIVER_NAME_BYTES)) {
                continue;
            }
            std::array<char, DRIVER_NAME_BYTES> installedAscii{};
            copyFixedAscii(
                    reinterpret_cast<unsigned char*>(installedAscii.data()),
                    installedAscii.size(), installedName.data(), installedName.size());
            if (std::strcmp(installedAscii.data(), mutableName.data()) == 0) {
                matchingIndex = index;
                break;
            }
        }
        if (matchingIndex < 0 || !driverList().loadAt(matchingIndex)) {
            unloadDriverLocked();
            return SHIM_FAIL;
        }

        g_driverInfo = {};
        g_driverInfo.asioVersion = 2;
        g_driverInfo.sysRef = GetDesktopWindow();
        ASIOError status = ASIOInit(&g_driverInfo);
        if (status != ASE_OK) {
            unloadDriverLocked();
            return SHIM_FAIL;
        }
        g_driverLoaded = true;
        copyFixedAscii(
                reinterpret_cast<unsigned char*>(g_activeDriverName.data()),
                g_activeDriverName.size(), mutableName.data());
        return SHIM_OK;
    } catch (...) {
        unloadDriverLocked();
        return SHIM_FAIL;
    }
}

ASIOSHIM_EXPORT int asioshim_getDriverName(void* outName, int nameCapacity) {
    if (!outName || nameCapacity <= 0) {
        return SHIM_FAIL;
    }
    std::lock_guard<std::mutex> lock(g_driverMutex);
    auto* destination = static_cast<unsigned char*>(outName);
    if (!g_driverLoaded) {
        destination[0] = '\0';
        return SHIM_FAIL;
    }
    copyFixedAscii(destination, static_cast<std::size_t>(nameCapacity),
                   g_activeDriverName.data());
    return SHIM_OK;
}

ASIOSHIM_EXPORT int asioshim_getDriverInfo(void* outInfo) {
    if (!outInfo) {
        return SHIM_FAIL;
    }
    std::lock_guard<std::mutex> lock(g_driverMutex);
    auto* row = static_cast<unsigned char*>(outInfo);
    std::memset(row, 0, DRIVER_INFO_STRIDE);
    if (!g_driverLoaded) {
        return SHIM_FAIL;
    }
    int32_t asioVersion = static_cast<int32_t>(g_driverInfo.asioVersion);
    int32_t driverVersion = static_cast<int32_t>(g_driverInfo.driverVersion);
    std::memcpy(row, &asioVersion, sizeof(asioVersion));
    std::memcpy(row + 4, &driverVersion, sizeof(driverVersion));
    copyFixedAscii(row + 8, DRIVER_NAME_BYTES,
                   g_driverInfo.name[0] == '\0'
                           ? g_activeDriverName.data() : g_driverInfo.name,
                   g_driverInfo.name[0] == '\0'
                           ? g_activeDriverName.size()
                           : sizeof(g_driverInfo.name));
    copyFixedAscii(row + 136, DRIVER_NAME_BYTES, g_driverInfo.errorMessage,
                   sizeof(g_driverInfo.errorMessage));
    return SHIM_OK;
}

ASIOSHIM_EXPORT void asioshim_unloadDriver(void) {
    std::lock_guard<std::mutex> lock(g_driverMutex);
    unloadDriverLocked();
}

ASIOSHIM_EXPORT int asioshim_getBufferSize(int* min, int* max,
                                           int* preferred, int* granularity) {
    if (!min || !max || !preferred || !granularity) {
        return SHIM_FAIL;
    }
    std::lock_guard<std::mutex> lock(g_driverMutex);
    if (!g_driverLoaded) {
        *min = *max = *preferred = *granularity = 0;
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
    std::lock_guard<std::mutex> lock(g_driverMutex);
    if (!g_driverLoaded) {
        return SHIM_FAIL;
    }
    return (ASIOCanSampleRate(static_cast<ASIOSampleRate>(rate)) == ASE_OK)
           ? SHIM_OK : SHIM_FAIL;
}

ASIOSHIM_EXPORT int asioshim_getSampleRate(double* outRate) {
    if (!outRate) {
        return SHIM_FAIL;
    }
    std::lock_guard<std::mutex> lock(g_driverMutex);
    if (!g_driverLoaded) {
        *outRate = 0.0;
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
    std::lock_guard<std::mutex> lock(g_driverMutex);
    if (!g_driverLoaded) {
        return SHIM_FAIL;
    }
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
    std::lock_guard<std::mutex> lock(g_driverMutex);
    if (!g_driverLoaded) {
        return SHIM_GENERIC_FAIL;
    }
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
    std::lock_guard<std::mutex> lock(g_driverMutex);
    if (!g_driverLoaded) {
        *outCount = 0;
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
    if (err != ASE_OK) {
        *outCount = 0;
        return SHIM_FAIL;
    }
    if (n <= 0) {
        *outCount = 0;
        return SHIM_OK;
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
        // matches host endian, and ASIO is Windows-only — x64 / x86 /
        // ARM64, all little-endian — so a direct memcpy preserves the
        // contract).
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
    std::lock_guard<std::mutex> lock(g_driverMutex);
    if (!g_driverLoaded) {
        return static_cast<int>(ASE_NotPresent);
    }
    return static_cast<int>(ASIOSetClockSource(static_cast<long>(reference)));
}

// ─── Channel-info bridge (story 215) ────────────────────────────────
//
// asioshim_getChannelCount mirrors ASIOGetChannels(numInputs, numOutputs)
// so the controller knows how many channel indices to enumerate before
// calling asioshim_getChannelInfo per index.
//
// Returns SHIM_OK (1) on ASE_OK, SHIM_FAIL (0) otherwise. On failure
// both *outInputs and *outOutputs are set to 0.
ASIOSHIM_EXPORT int asioshim_getChannelCount(int* outInputs, int* outOutputs) {
    if (!outInputs || !outOutputs) {
        if (outInputs) *outInputs = 0;
        if (outOutputs) *outOutputs = 0;
        return SHIM_FAIL;
    }
    std::lock_guard<std::mutex> lock(g_driverMutex);
    if (!g_driverLoaded) {
        *outInputs = 0;
        *outOutputs = 0;
        return SHIM_FAIL;
    }
    long ins = 0, outs = 0;
    ASIOError err = ASIOGetChannels(&ins, &outs);
    if (err != ASE_OK) {
        *outInputs = 0;
        *outOutputs = 0;
        return SHIM_FAIL;
    }
    if (ins < 0) ins = 0;
    if (outs < 0) outs = 0;
    *outInputs = static_cast<int>(ins);
    *outOutputs = static_cast<int>(outs);
    return SHIM_OK;
}

// asioshim_getChannelInfo wraps ASIOGetChannelInfo(ASIOChannelInfo*).
// The channel index and direction (input vs output) are passed as
// explicit parameters — the caller does NOT pre-fill any fields in
// outInfo. The shim populates the SDK struct from the parameters,
// calls ASIOGetChannelInfo, and writes the driver's answer into the
// normalised FFM layout at outInfo (56 bytes):
//   [0..4)   int32 channel
//   [4..8)   int32 isInput          (1 = input, 0 = output)
//   [8..12)  int32 isActive
//   [12..16) int32 channelGroup
//   [16..20) int32 type             (ASIOSampleType)
//   [20..24) int32 reserved (padding so name starts at byte 24)
//   [24..56) char name[32]          ASCII, NUL-terminated
//
// Returns SHIM_OK (1) on ASE_OK, SHIM_FAIL (0) otherwise.
ASIOSHIM_EXPORT int asioshim_getChannelInfo(int channelIndex, int isInput,
                                            void* outInfo) {
    if (!outInfo || channelIndex < 0) {
        return SHIM_FAIL;
    }
    std::lock_guard<std::mutex> lock(g_driverMutex);
    if (!g_driverLoaded) {
        return SHIM_FAIL;
    }
    ASIOChannelInfo info{};
    info.channel = channelIndex;
    info.isInput = (isInput != 0) ? 1 : 0;
    ASIOError err = ASIOGetChannelInfo(&info);
    if (err != ASE_OK) {
        return SHIM_FAIL;
    }
    auto* row = static_cast<unsigned char*>(outInfo);
    int32_t ch        = static_cast<int32_t>(info.channel);
    int32_t isIn      = static_cast<int32_t>(info.isInput);
    int32_t active    = static_cast<int32_t>(info.isActive);
    int32_t group     = static_cast<int32_t>(info.channelGroup);
    int32_t type      = static_cast<int32_t>(info.type);
    int32_t pad       = 0;
    std::memcpy(row + 0,  &ch,     4);
    std::memcpy(row + 4,  &isIn,   4);
    std::memcpy(row + 8,  &active, 4);
    std::memcpy(row + 12, &group,  4);
    std::memcpy(row + 16, &type,   4);
    std::memcpy(row + 20, &pad,    4);
    for (int k = 0; k < 32; ++k) {
        row[24 + k] = static_cast<unsigned char>(info.name[k]);
    }
    row[24 + 31] = '\0';  // defensive NUL termination of the last byte
    return SHIM_OK;
}

// ─── Format-change host-callback bridge (story 218) ─────────────────
//
// The JVM passes a single function pointer matching ASIO's
// asioMessage(long, long, void*, double*) -> long signature. Retaining the
// SDK's native `long` spelling makes the trampoline function-pointer type
// directly assignable to ASIOCallbacks::asioMessage; the static_assert above
// guarantees that Java's JAVA_INT descriptor remains ABI-exact. We store it
// in a process-global slot the SDK's installed callback table
// reads when the driver fires kAsioResetRequest / kAsioBufferSizeChange
// / kAsioResyncRequest. The Java upcall is responsible for mapping
// each selector onto AudioDeviceEvent.FormatChangeRequested.

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

// This IS the ASIOCallbacks::asioMessage slot, not a wrapper around it:
// asioshim_createBuffers assigns this function into g_callbacks.asioMessage
// (see `g_callbacks.asioMessage = asioshim_messageTrampoline;` below) and
// the vendor driver calls it directly, on its own thread. It loads the
// callback installed by the JVM and forwards selector / value / message /
// opt verbatim. Like the bufferSwitch trampoline it takes no lock.
ASIOSHIM_EXPORT long asioshim_messageTrampoline(
        long selector, long value, void* message, double* opt) {
    asio_message_fn cb = g_asioMessageCallback.load(std::memory_order_acquire);
    if (cb == nullptr) {
        return 0;
    }
    return cb(selector, value, message, opt);
}

// ─── Real-time streaming (story 311) ────────────────────────────────
//
// asioshim_createBuffers is where the process-global ASIOCallbacks table is
// finally populated. That makes the story-218 asioshim_messageTrampoline
// reachable for the first time: before this story no ASIOCallbacks struct was
// ever constructed, so the driver had nowhere to deliver kAsioResetRequest /
// kAsioBufferSizeChange / kAsioResyncRequest.
//
// Lifecycle contract:
//   asioshim_createBuffers → asioshim_getBufferInfos → asioshim_start
//     … streaming … → asioshim_stop → asioshim_disposeBuffers
// asioshim_stop and asioshim_disposeBuffers are idempotent so the Java
// close() path can call them unconditionally: "nothing to do" is SHIM_OK.
// "The driver refused" is not — a non-ASE_OK ASIOStop / ASIODisposeBuffers
// returns SHIM_FAIL. That status is a *diagnostic*, not a stop signal:
// AsioBackend#tearDownStreaming logs it at WARNING and deliberately carries
// on through the rest of the teardown. What protects the JVM upcall stub
// from a driver that ignored ASIOStop is the in-flight barrier plus the null
// callback pointer published by uninstallAsioBufferSwitchCallback (see the
// REAL-TIME DISCIPLINE banner below) — not ASIOStop having succeeded. Bailing
// out on a refusal would skip the very uninstall that makes the arena free
// safe. unloadDriverLocked() runs the same teardown before ASIOExit.
//
// ── REAL-TIME DISCIPLINE — read before touching shimBufferSwitchBody ──
//
// The bufferSwitch callbacks run on the *vendor driver's* real-time audio
// thread, not on `asio-control`. They MUST NEVER take g_driverMutex. Every
// other export in this file locks it, so a lock here would be both:
//
//   (a) an RT violation — an unbounded, priority-inversion-prone blocking
//       operation inside the audio callback; and
//   (b) a deadlock hazard — a control thread inside asioshim_stop() holds
//       g_driverMutex while ASIOStop() waits for the driver's callback
//       thread to quiesce, and that callback would be parked on the very
//       mutex ASIOStop is waiting behind.
//
// The only synchronisation permitted here is std::atomic, and it is two
// mechanisms working together:
//
//   1. State gates. A null g_bufferSwitchCallback (after uninstall) makes
//      the callback a cheap no-op, and g_buffersCreated is published false
//      *before* the SDK teardown so a callback that has not started yet
//      returns without touching disposed state.
//   2. An in-flight barrier. Both gates are read once, at entry — they say
//      nothing about a callback that is already past them. Every callback
//      therefore counts itself into g_callbacksInFlight via
//      CallbackInFlightGuard, and every teardown step calls
//      drainInFlightCallbacks() before it lets the SDK (or the JVM) free
//      anything the body touches. Because the callback never takes
//      g_driverMutex, a control thread may hold that mutex while draining
//      without any risk of deadlock.

namespace {
    std::atomic<asio_buffer_switch_fn> g_bufferSwitchCallback{nullptr};

    // Shared body of both Steinberg bufferSwitch entry points.
    // Lock-free and allocation-free by contract — see the banner above.
    void shimBufferSwitchBody(long bufferIndex, ASIOBool directProcess) {
        // Count in first, then re-read the gate: that ordering is what lets
        // drainInFlightCallbacks() conclude that a zero counter means no
        // callback is inside this body. The guard decrements on every exit
        // path, including the two early returns below.
        CallbackInFlightGuard inFlight;
        if (!g_buffersCreated.load(std::memory_order_seq_cst)) {
            return;
        }
        // seq_cst, not merely acquire: this load has to join the total order
        // described on drainInFlightCallbacks() so that a callback which
        // still sees the pre-uninstall pointer is guaranteed to be counted.
        asio_buffer_switch_fn cb =
                g_bufferSwitchCallback.load(std::memory_order_seq_cst);
        if (cb == nullptr) {
            // No upcall installed — between asioshim_start and
            // installAsioBufferSwitchCallback, or after an uninstall that
            // ASIOStop has not yet caught up with. Nothing filled the output
            // half this cycle, so signalling ASIOOutputReady() here would
            // tell the driver to play back whatever the previous cycle left
            // behind: audible looped garbage instead of silence. Stay quiet.
            return;
        }
        // directProcess is forwarded verbatim; the shim always processes
        // inline, so it is informational for the JVM upcall.
        cb(bufferIndex, static_cast<long>(directProcess));
        // Called natively rather than from Java so the JVM upcall stays a
        // pure memory copy. Only meaningful when the driver advertised
        // support at ASIOCreateBuffers time (probed once, cached), and only
        // reached once the upcall above has actually filled the output half.
        if (g_outputReadySupported.load(std::memory_order_acquire)) {
            ASIOOutputReady();
        }
    }

    // ASIOCallbacks::bufferSwitchTimeInfo slot (ASIO 2.0 entry point).
    // `params` is unused: the shim does not consume the driver's time
    // stamps (round-trip latency reporting is story 217). The driver
    // ignores the return value; Steinberg's own hostsample returns 0L.
    ASIOTime* shimBufferSwitchTimeInfo(ASIOTime* params,
                                       long doubleBufferIndex,
                                       ASIOBool directProcess) {
        (void) params;
        shimBufferSwitchBody(doubleBufferIndex, directProcess);
        return nullptr;
    }

    // ASIOCallbacks::sampleRateDidChange slot. The hardware clock changed
    // rate underneath us, which invalidates the negotiated format. Report it
    // as a driver reset through the story-218 message trampoline rather than
    // inventing a second upcall — AsioFormatChangeShim's Javadoc already
    // anticipates exactly this: "Sample-rate-driven resets … are reported as
    // DriverReset and the controller re-queries device capabilities on
    // reopen." Also lock-free: this too arrives on the driver's thread.
    void shimSampleRateDidChange(ASIOSampleRate sampleRate) {
        (void) sampleRate;
        asioshim_messageTrampoline(static_cast<long>(kAsioResetRequest),
                                   0, nullptr, nullptr);
    }
}

// Creates the driver's double-buffered I/O buffers for the requested active
// channels and installs the host callback table.
//
// inputChannels[0..numInputs) and outputChannels[0..numOutputs) are driver
// channel indices. The resulting ASIOBufferInfo records — and therefore the
// records asioshim_getBufferInfos reports — are ordered inputs first (in
// inputChannels order) then outputs (in outputChannels order).
//
// numInputs + numOutputs is capped at MAX_STREAM_CHANNELS (64) *combined*,
// so a symmetric configuration tops out at 32 in + 32 out.
//
// Requires a loaded driver and no already-created buffers. Returns SHIM_OK
// (1) on ASE_OK, SHIM_FAIL (0) otherwise; a failure leaves no partial state.
//
// There is no try/catch here (nor in the other story-311 exports): nothing
// in the body can throw — std::array::fill over PODs, plain assignment, and
// C calls into the SDK — and MSVC's default /EHsc does not turn an access
// violation inside a vendor driver into a C++ exception, so a handler would
// be unreachable. The genuinely reachable handlers are the ones in
// asioshim_listDrivers / asioshim_loadDriver, where std::make_unique can
// throw std::bad_alloc.
ASIOSHIM_EXPORT int asioshim_createBuffers(const int* inputChannels,
                                           int numInputs,
                                           const int* outputChannels,
                                           int numOutputs,
                                           int bufferFrames) {
    if (numInputs < 0 || numOutputs < 0 || bufferFrames <= 0) {
        return SHIM_FAIL;
    }
    if ((numInputs > 0 && !inputChannels)
            || (numOutputs > 0 && !outputChannels)) {
        return SHIM_FAIL;
    }
    int total = numInputs + numOutputs;
    if (total <= 0 || total > MAX_STREAM_CHANNELS) {
        return SHIM_FAIL;
    }
    // Validate every channel index before entering the SDK — a negative
    // index would be forwarded straight into the vendor driver.
    for (int index = 0; index < numInputs; ++index) {
        if (inputChannels[index] < 0) {
            return SHIM_FAIL;
        }
    }
    for (int index = 0; index < numOutputs; ++index) {
        if (outputChannels[index] < 0) {
            return SHIM_FAIL;
        }
    }
    std::lock_guard<std::mutex> lock(g_driverMutex);
    if (!g_driverLoaded || g_buffersCreated.load(std::memory_order_acquire)) {
        return SHIM_FAIL;
    }
    clearBufferStateLocked();
    for (int index = 0; index < numInputs; ++index) {
        ASIOBufferInfo& info = g_bufferInfos[index];
        info.isInput = ASIOTrue;
        info.channelNum = static_cast<long>(inputChannels[index]);
        info.buffers[0] = nullptr;
        info.buffers[1] = nullptr;
    }
    for (int index = 0; index < numOutputs; ++index) {
        ASIOBufferInfo& info = g_bufferInfos[numInputs + index];
        info.isInput = ASIOFalse;
        info.channelNum = static_cast<long>(outputChannels[index]);
        info.buffers[0] = nullptr;
        info.buffers[1] = nullptr;
    }

    // Both trampolines the driver calls are the exported symbols themselves,
    // so the DLL's public surface is exactly what the driver invokes.
    g_callbacks.bufferSwitch = asioshim_bufferSwitchTrampoline;
    g_callbacks.sampleRateDidChange = &shimSampleRateDidChange;
    // story 218: the JVM-installed asioMessage upcall finally gets a
    // slot in a real ASIOCallbacks table.
    g_callbacks.asioMessage = asioshim_messageTrampoline;
    // bufferSwitchTimeInfo keeps the file-local function: its signature
    // (ASIOTime*) has no useful FFM mapping, so exporting it would only add
    // a symbol nothing calls.
    g_callbacks.bufferSwitchTimeInfo = &shimBufferSwitchTimeInfo;

    ASIOError err = ASIOCreateBuffers(g_bufferInfos.data(),
                                      static_cast<long>(total),
                                      static_cast<long>(bufferFrames),
                                      &g_callbacks);
    if (err != ASE_OK) {
        // ASIOCreateBuffers can fail part-way through — e.g. 32 in + 32 out
        // requested on a 32-in/16-out device allocates the inputs and then
        // refuses the outputs — leaving the driver holding buffers and a
        // live pointer to &g_callbacks. Dispose *before* clearing our state:
        // clearBufferStateLocked() publishes g_buffersCreated = false, after
        // which disposeBuffersLocked() would skip ASIODisposeBuffers()
        // forever and the device would stay unusable (every retry answered
        // with "buffers already created") until the process restarts.
        //
        // Harmless when the driver allocated nothing: g_driverLoaded is
        // checked above so the SDK always has a driver to forward to, and a
        // driver with no buffers simply answers with an error we ignore.
        ASIODisposeBuffers();
        clearBufferStateLocked();
        return SHIM_FAIL;
    }
    // Cache each channel's ASIOSampleType now so the real-time path
    // never has to re-enter the SDK to learn the driver's format.
    for (int index = 0; index < total; ++index) {
        ASIOChannelInfo channelInfo{};
        channelInfo.channel = g_bufferInfos[index].channelNum;
        channelInfo.isInput = g_bufferInfos[index].isInput;
        g_sampleTypes[index] =
                (ASIOGetChannelInfo(&channelInfo) == ASE_OK)
                        ? static_cast<int32_t>(channelInfo.type)
                        : SAMPLE_TYPE_UNKNOWN;
    }
    g_bufferInfoCount = total;
    g_outputReadySupported.store(ASIOOutputReady() == ASE_OK,
                                 std::memory_order_release);
    g_buffersCreated.store(true, std::memory_order_release);
    return SHIM_OK;
}

// Reports the buffer addresses ASIOCreateBuffers handed back, so the JVM
// can read and write the driver's buffers directly (the sample-format
// conversion boundary lives in Java — story 312 generalises it).
//
// Writes a fixed 32-byte record per active channel, in exactly the order
// passed to asioshim_createBuffers:
//   [0..4)   int32 channel      driver channel index
//   [4..8)   int32 isInput      1 = input, 0 = output
//   [8..12)  int32 sampleType   driver ASIOSampleType (-1 = unknown)
//   [12..16) int32 reserved     always 0 (keeps the pointers 8-byte aligned)
//   [16..24) int64 buffer0      address of ASIOBufferInfo.buffers[0]
//   [24..32) int64 buffer1      address of ASIOBufferInfo.buffers[1]
//
// *outCount is capacity-in / actual-written-out. A zero-capacity call is a
// successful no-op. Fails with *outCount = 0 when no driver is loaded or
// buffers have not been created.
ASIOSHIM_EXPORT int asioshim_getBufferInfos(void* outArray, int* outCount) {
    if (!outCount) {
        return SHIM_FAIL;
    }
    int capacity = *outCount;
    *outCount = 0;
    if (capacity < 0) {
        return SHIM_FAIL;
    }
    if (capacity > 0 && !outArray) {
        return SHIM_FAIL;
    }
    std::lock_guard<std::mutex> lock(g_driverMutex);
    if (!g_driverLoaded || !g_buffersCreated.load(std::memory_order_acquire)) {
        return SHIM_FAIL;
    }
    if (capacity == 0) {
        return SHIM_OK;
    }
    int written = std::min(capacity, g_bufferInfoCount);
    auto* bytes = static_cast<unsigned char*>(outArray);
    for (int index = 0; index < written; ++index) {
        const ASIOBufferInfo& info = g_bufferInfos[index];
        unsigned char* row = bytes + (index * BUFFER_INFO_STRIDE);
        // Pack little-endian via memcpy (ASIO is Windows-only — x64 /
        // x86 / ARM64, all little-endian — so this matches the Java
        // ValueLayout.JAVA_INT / JAVA_LONG host-endian contract).
        int32_t channel    = static_cast<int32_t>(info.channelNum);
        int32_t isInput    = (info.isInput != ASIOFalse) ? 1 : 0;
        int32_t sampleType = g_sampleTypes[index];
        int32_t reserved   = 0;
        int64_t buffer0    = static_cast<int64_t>(
                reinterpret_cast<std::uintptr_t>(info.buffers[0]));
        int64_t buffer1    = static_cast<int64_t>(
                reinterpret_cast<std::uintptr_t>(info.buffers[1]));
        std::memcpy(row + 0,  &channel,    4);
        std::memcpy(row + 4,  &isInput,    4);
        std::memcpy(row + 8,  &sampleType, 4);
        std::memcpy(row + 12, &reserved,   4);
        std::memcpy(row + 16, &buffer0,    8);
        std::memcpy(row + 24, &buffer1,    8);
    }
    *outCount = written;
    return SHIM_OK;
}

// Wraps ASIOStart(). Requires created buffers. Returns SHIM_OK (1) when the
// driver is already streaming, so the call is safe to repeat.
ASIOSHIM_EXPORT int asioshim_start(void) {
    std::lock_guard<std::mutex> lock(g_driverMutex);
    if (!g_driverLoaded || !g_buffersCreated.load(std::memory_order_acquire)) {
        return SHIM_FAIL;
    }
    if (g_started) {
        return SHIM_OK;
    }
    if (ASIOStart() != ASE_OK) {
        return SHIM_FAIL;
    }
    g_started = true;
    return SHIM_OK;
}

// Wraps ASIOStop(). Idempotent in the "nothing to do" sense: returns
// SHIM_OK (1) when nothing is streaming — including when no driver is
// loaded at all — so the Java close() path can call it unconditionally
// without branching on state.
//
// It does NOT paper over a refusal: when ASIOStop() itself returns anything
// other than ASE_OK the driver may still be firing bufferSwitch, so this
// returns SHIM_FAIL. The Java side consumes that as a diagnostic rather than
// as a stop signal — AsioBackend#tearDownStreaming logs it at WARNING, naming
// the refused call and the driver, and then continues the teardown.
//
// Continuing is the safe response. asioshim_disposeBuffers publishes
// g_buffersCreated = false before it re-enters the SDK and then drains the
// in-flight barrier, so a later callback short-circuits at the top of
// shimBufferSwitchBody without ever loading the callback pointer; then
// uninstallAsioBufferSwitchCallback stores a null pointer and drains again,
// after which the trampoline loads null and returns without touching the
// upcall stub. Only then does Java free the arena, so the free is guarded by
// those two steps — not by ASIOStop. Aborting here would skip the uninstall
// that is the actual protection and would leak the upcall arena and the
// asio-input-drain thread for the life of the process.
ASIOSHIM_EXPORT int asioshim_stop(void) {
    std::lock_guard<std::mutex> lock(g_driverMutex);
    return stopStreamingLocked() ? SHIM_OK : SHIM_FAIL;
}

// Wraps ASIODisposeBuffers(), stopping the driver first when it is still
// streaming. Clears the cached buffer records and the output-ready probe.
//
// Same distinction as asioshim_stop: SHIM_OK (1) when there was nothing to
// dispose, SHIM_FAIL (0) when the driver refused ASIOStop() or
// ASIODisposeBuffers().
ASIOSHIM_EXPORT int asioshim_disposeBuffers(void) {
    std::lock_guard<std::mutex> lock(g_driverMutex);
    return disposeBuffersLocked() ? SHIM_OK : SHIM_FAIL;
}

// Registers the JVM FFM upcall invoked from every bufferSwitch. Mirrors
// installAsioMessageCallback (a single atomic store) so the real-time
// trampoline never needs a lock to observe the swap.
ASIOSHIM_EXPORT void installAsioBufferSwitchCallback(void* callback) {
    g_bufferSwitchCallback.store(
            reinterpret_cast<asio_buffer_switch_fn>(callback),
            std::memory_order_release);
}

// Nulling the pointer only stops callbacks that have not read it yet, so
// wait for the ones that already have. The Java side frees the upcall
// stub's arena right after this returns; a callback still inside
// shimBufferSwitchBody would otherwise jump into released memory.
//
// The store is seq_cst so that it joins the total order described on
// drainInFlightCallbacks() together with the callback's seq_cst increment
// and matching seq_cst load of the same pointer: a callback that still
// observes the old, non-null pointer is therefore guaranteed to be visible
// in the counter this drain reads.
ASIOSHIM_EXPORT void uninstallAsioBufferSwitchCallback() {
    g_bufferSwitchCallback.store(nullptr, std::memory_order_seq_cst);
    drainInFlightCallbacks();
}

// The ASIOCallbacks::bufferSwitch slot itself (the ASIO 1.0 entry point),
// wired in asioshim_createBuffers exactly the way asioshim_messageTrampoline
// is wired into the asioMessage slot. It is exported rather than file-local
// so both driver-invoked trampolines are visible in the DLL's symbol table
// for diagnostics.
//
// Runs on the vendor driver's real-time thread and takes no lock — see the
// REAL-TIME DISCIPLINE banner above.
ASIOSHIM_EXPORT void asioshim_bufferSwitchTrampoline(long bufferIndex,
                                                     long directProcess) {
    shimBufferSwitchBody(bufferIndex, static_cast<ASIOBool>(directProcess));
}

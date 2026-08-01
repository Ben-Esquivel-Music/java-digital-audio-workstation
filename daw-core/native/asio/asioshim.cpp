// asioshim.cpp — thin native bridge to the Steinberg ASIO SDK exposed
// to Java via FFM (JEP 454). See README.md and the Java counterpart
// daw-sdk/.../AsioCapabilityShim.java for the contract.
//
// Story 130 / 213 — Driver-Reported Buffer Size and Sample-Rate
// Enumeration. Story 218 — Format-change host-callback bridge.
//
// Threading: all host downcalls are serialized by the JVM's dedicated
// `asio-control` platform thread. They must never be called from the audio
// render thread. The only cross-thread entrypoint is the driver-owned
// format-change callback trampoline at the bottom of this file.

#define ASIOSHIM_EXPORTS
#include "asioshim.h"
#include "asiosys.h"
#include "asio.h"
#include "asiodrivers.h"

#include <windows.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>

#define ASIOSHIM_EXPORT ASIOSHIM_API

// Defined by the SDK's common/asio.cpp; the indexed host-glue open path
// assigns the single process-wide IASIO instance before ASIOInit.
extern IASIO* theAsioDriver;

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

    void unloadDriverLocked() {
        if (g_driverLoaded) {
            ASIOExit();
        }
        if (g_drivers) {
            g_drivers->removeCurrentDriver();
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
        long matchingIndex = -1;
        long count = std::min(driverList().count(),
                              static_cast<long>(DRIVER_CAPACITY));
        for (long index = 0; index < count; ++index) {
            std::array<char, DRIVER_NAME_BYTES> installedName{};
            if (driverList().nameAt(index, installedName.data(), DRIVER_NAME_BYTES)
                    && std::strcmp(installedName.data(), mutableName.data()) == 0) {
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

// The SDK's ASIOCallbacks struct contains a slot called asioMessage
// that the host-side glue (asiodrivers.cpp) routes through this
// trampoline. The trampoline is referenced from the SDK glue when
// asioshim is built with -DASIOSHIM_TRAMPOLINE.
ASIOSHIM_EXPORT long asioshim_messageTrampoline(
        long selector, long value, void* message, double* opt) {
    asio_message_fn cb = g_asioMessageCallback.load(std::memory_order_acquire);
    if (cb == nullptr) {
        return 0;
    }
    return cb(selector, value, message, opt);
}

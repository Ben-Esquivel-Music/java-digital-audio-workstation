package com.benesquivelmusic.daw.sdk.audio;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * FFM (JEP 454, final in Java 22) binding for ASIO driver discovery and
 * init/exit lifecycle operations exported by {@code asioshim.dll}.
 *
 * <p>This class deliberately uses {@link SymbolLookup#libraryLookup(String, Arena)}
 * directly. The repository's shared native loader lives in {@code daw-core}, while
 * {@code daw-core} depends on this SDK module. Moving this binding through that
 * loader would therefore introduce a module cycle. Keeping all ASIO bindings in
 * the SDK's existing module-safe lookup family is the only legal dependency
 * direction until native bindings move to a lower-level module.</p>
 *
 * <p>The shim is optional. Missing libraries and missing symbols are represented
 * by unavailable operations and empty results; construction never fails. The
 * process-global ownership guard mirrors the Steinberg host glue's single-driver
 * model and prevents an obsolete wrapper from unloading a newer driver's COM
 * object.</p>
 */
class AsioDriverShim implements AutoCloseable {

    static final int DRIVER_NAME_BYTES = 128;
    static final int DRIVER_CLSID_BYTES = 40;
    static final int DRIVER_RECORD_STRIDE = DRIVER_NAME_BYTES + DRIVER_CLSID_BYTES;
    static final int DRIVER_CAPACITY = 64;

    static final int DRIVER_INFO_NAME_OFFSET = 8;
    static final int DRIVER_INFO_ERROR_OFFSET = DRIVER_INFO_NAME_OFFSET + DRIVER_NAME_BYTES;
    static final int DRIVER_INFO_STRIDE = DRIVER_INFO_ERROR_OFFSET + DRIVER_NAME_BYTES;

    private static final int SHIM_OK = 1;
    private static final Object LIFECYCLE_LOCK = new Object();
    private static AsioDriverShim activeOwner;

    private final Arena arena;
    private final MethodHandle listDrivers;
    private final MethodHandle loadDriver;
    private final MethodHandle getDriverName;
    private final MethodHandle getDriverInfo;
    private final MethodHandle unloadDriver;
    private boolean ownsActiveDriver;
    private boolean closed;

    AsioDriverShim() {
        arena = Arena.ofShared();
        MethodHandle list = null;
        MethodHandle load = null;
        MethodHandle name = null;
        MethodHandle info = null;
        MethodHandle unload = null;
        try {
            SymbolLookup lookup = SymbolLookup.libraryLookup("asioshim", arena);
            Linker linker = Linker.nativeLinker();
            list = resolve(linker, lookup, "asioshim_listDrivers",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            load = resolve(linker, lookup, "asioshim_loadDriver",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            name = resolve(linker, lookup, "asioshim_getDriverName",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            info = resolve(linker, lookup, "asioshim_getDriverInfo",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            unload = resolve(linker, lookup, "asioshim_unloadDriver",
                    FunctionDescriptor.ofVoid());
        } catch (IllegalArgumentException | UnsatisfiedLinkError ignored) {
            // Optional DLL absent: every operation degrades gracefully.
        } catch (Throwable ignored) {
            // Native access disabled or ABI mismatch: keep all handles unavailable.
        }
        listDrivers = list;
        loadDriver = load;
        getDriverName = name;
        getDriverInfo = info;
        unloadDriver = unload;
    }

    AsioDriverShim(MethodHandle loadDriver, MethodHandle unloadDriver) {
        arena = Arena.ofShared();
        listDrivers = null;
        this.loadDriver = Objects.requireNonNull(loadDriver,
                "loadDriver must not be null");
        getDriverName = null;
        getDriverInfo = null;
        this.unloadDriver = Objects.requireNonNull(unloadDriver,
                "unloadDriver must not be null");
    }

    private static MethodHandle resolve(Linker linker, SymbolLookup lookup,
                                        String symbol, FunctionDescriptor descriptor) {
        return lookup.find(symbol)
                .map(address -> linker.downcallHandle(address, descriptor))
                .orElse(null);
    }

    boolean isEnumerationAvailable() {
        return !closed && listDrivers != null;
    }

    boolean isLifecycleAvailable() {
        return !closed && loadDriver != null && unloadDriver != null;
    }

    /** Returns a snapshot of installed x64 ASIO driver registry entries. */
    List<DriverDescriptor> listDrivers() {
        if (!isEnumerationAvailable()) {
            return List.of();
        }
        try {
            return AsioControlThread.call(() -> {
                try (Arena call = Arena.ofConfined()) {
                    MemorySegment records = call.allocate(
                            (long) DRIVER_RECORD_STRIDE * DRIVER_CAPACITY);
                    MemorySegment count = call.allocate(ValueLayout.JAVA_INT);
                    count.set(ValueLayout.JAVA_INT, 0, DRIVER_CAPACITY);
                    int status = (int) listDrivers.invokeExact(records, count);
                    if (status != SHIM_OK) {
                        return List.of();
                    }
                    int actual = Math.clamp(
                            count.get(ValueLayout.JAVA_INT, 0), 0, DRIVER_CAPACITY);
                    return decodeDrivers(records, actual);
                }
            });
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    /**
     * Loads and initializes the named driver. A successful call transfers the
     * process-global driver ownership to this wrapper.
     */
    boolean loadDriver(String driverName) {
        Objects.requireNonNull(driverName, "driverName must not be null");
        if (driverName.isBlank() || !isLifecycleAvailable()) {
            return false;
        }
        synchronized (LIFECYCLE_LOCK) {
            if (activeOwner != null && activeOwner != this) {
                return false;
            }
            try {
                int status = AsioControlThread.call(() -> {
                    try (Arena call = Arena.ofConfined()) {
                        MemorySegment nativeName =
                                call.allocateFrom(driverName, StandardCharsets.UTF_8);
                        return (int) loadDriver.invokeExact(nativeName);
                    }
                });
                if (status != SHIM_OK) {
                    clearOwnershipAfterFailedLoad();
                    return false;
                }
                activeOwner = this;
                ownsActiveDriver = true;
                return true;
            } catch (Throwable ignored) {
                clearOwnershipAfterFailedLoad();
                return false;
            }
        }
    }

    private void clearOwnershipAfterFailedLoad() {
        ownsActiveDriver = false;
        if (activeOwner == this) {
            activeOwner = null;
        }
    }

    Optional<String> getDriverName() {
        if (!mayQueryActiveDriver() || getDriverName == null) {
            return Optional.empty();
        }
        try {
            return AsioControlThread.call(() -> {
                try (Arena call = Arena.ofConfined()) {
                    MemorySegment name = call.allocate(DRIVER_NAME_BYTES);
                    int status = (int) getDriverName.invokeExact(name, DRIVER_NAME_BYTES);
                    if (status != SHIM_OK) {
                        return Optional.empty();
                    }
                    return nonBlank(decodeAscii(name, 0, DRIVER_NAME_BYTES));
                }
            });
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    Optional<DriverInfo> getDriverInfo() {
        if (!mayQueryActiveDriver() || getDriverInfo == null) {
            return Optional.empty();
        }
        try {
            return AsioControlThread.call(() -> {
                try (Arena call = Arena.ofConfined()) {
                    MemorySegment info = call.allocate(DRIVER_INFO_STRIDE);
                    int status = (int) getDriverInfo.invokeExact(info);
                    if (status != SHIM_OK) {
                        return Optional.empty();
                    }
                    return Optional.of(decodeDriverInfo(info));
                }
            });
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    /** Calls {@code ASIOExit} and releases the current driver's COM object. */
    void unloadDriver() {
        synchronized (LIFECYCLE_LOCK) {
            if (closed || !ownsActiveDriver || activeOwner != this) {
                ownsActiveDriver = false;
                return;
            }
            try {
                if (unloadDriver != null) {
                    AsioControlThread.call(() -> {
                        unloadDriver.invokeExact();
                        return null;
                    });
                }
            } catch (Throwable ignored) {
                // Native unload is best-effort and the ABI itself is idempotent.
            } finally {
                ownsActiveDriver = false;
                activeOwner = null;
            }
        }
    }

    static boolean isDriverLoaded() {
        synchronized (LIFECYCLE_LOCK) {
            return activeOwner != null && activeOwner.ownsActiveDriver && !activeOwner.closed;
        }
    }

    private boolean mayQueryActiveDriver() {
        synchronized (LIFECYCLE_LOCK) {
            return !closed && ownsActiveDriver && activeOwner == this;
        }
    }

    @Override
    public void close() {
        synchronized (LIFECYCLE_LOCK) {
            if (closed) {
                return;
            }
        }
        unloadDriver();
        synchronized (LIFECYCLE_LOCK) {
            if (!closed) {
                closed = true;
                try {
                    AsioControlThread.call(() -> {
                        arena.close();
                        return null;
                    });
                } catch (Throwable ignored) {
                    // Best-effort cleanup; close remains idempotent.
                }
            }
        }
    }

    static List<DriverDescriptor> decodeDrivers(MemorySegment records, int count) {
        Objects.requireNonNull(records, "records must not be null");
        int safeCount = Math.max(0, count);
        List<DriverDescriptor> decoded = new ArrayList<>(safeCount);
        for (int index = 0; index < safeCount; index++) {
            long base = (long) index * DRIVER_RECORD_STRIDE;
            String name = decodeAscii(records, base, DRIVER_NAME_BYTES);
            if (name.isBlank()) {
                continue;
            }
            String clsid = decodeAscii(records, base + DRIVER_NAME_BYTES, DRIVER_CLSID_BYTES);
            decoded.add(new DriverDescriptor(name, clsid));
        }
        return List.copyOf(decoded);
    }

    static DriverInfo decodeDriverInfo(MemorySegment info) {
        Objects.requireNonNull(info, "info must not be null");
        return new DriverInfo(
                info.get(ValueLayout.JAVA_INT, 0),
                info.get(ValueLayout.JAVA_INT, 4),
                decodeAscii(info, DRIVER_INFO_NAME_OFFSET, DRIVER_NAME_BYTES),
                decodeAscii(info, DRIVER_INFO_ERROR_OFFSET, DRIVER_NAME_BYTES));
    }

    private static String decodeAscii(MemorySegment segment, long offset, int length) {
        byte[] bytes = new byte[length];
        int used = 0;
        for (; used < length; used++) {
            byte value = segment.get(ValueLayout.JAVA_BYTE, offset + used);
            if (value == 0) {
                break;
            }
            bytes[used] = (value >= 0x20 && value <= 0x7e) ? value : (byte) '?';
        }
        return new String(bytes, 0, used, StandardCharsets.US_ASCII);
    }

    private static Optional<String> nonBlank(String value) {
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    record DriverDescriptor(String name, String clsid) {
        DriverDescriptor {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(clsid, "clsid must not be null");
            if (name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
        }
    }

    record DriverInfo(int asioVersion, int driverVersion, String name, String errorMessage) {
        DriverInfo {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(errorMessage, "errorMessage must not be null");
        }
    }
}

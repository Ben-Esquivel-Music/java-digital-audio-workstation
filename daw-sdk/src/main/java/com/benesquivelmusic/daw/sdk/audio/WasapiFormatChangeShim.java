package com.benesquivelmusic.daw.sdk.audio;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;
import java.util.Optional;

/**
 * FFM (JEP 454) shim that builds a Java-implemented
 * {@code IMMNotificationClient} COM object — an in-memory pointer-to-
 * vtable structure whose slots are FFM upcall stubs — and registers it
 * with the Windows {@code IMMDeviceEnumerator} so that
 * {@code OnPropertyValueChanged(PKEY_AudioEngine_DeviceFormat)}
 * publishes a {@link AudioDeviceEvent.FormatChangeRequested} on
 * {@link WasapiBackend#deviceEvents()} (story 218).
 *
 * <p>On non-Windows hosts the {@code Ole32.dll} lookup fails and the
 * shim degrades to a no-op. Tests can still invoke the package-private
 * {@link #dispatchPropertyChanged(MemorySegment)} helper directly to
 * exercise the {@code PROPERTYKEY} comparison logic.</p>
 *
 * <p>The vtable order is fixed by the {@code IMMNotificationClient}
 * COM contract:</p>
 * <ol>
 *   <li>{@code QueryInterface}</li>
 *   <li>{@code AddRef}</li>
 *   <li>{@code Release}</li>
 *   <li>{@code OnDeviceStateChanged}</li>
 *   <li>{@code OnDeviceAdded}</li>
 *   <li>{@code OnDeviceRemoved}</li>
 *   <li>{@code OnDefaultDeviceChanged}</li>
 *   <li>{@code OnPropertyValueChanged}</li>
 * </ol>
 *
 * <p>The {@code OnPropertyValueChanged} parameter signature uses
 * {@link ValueLayout#ADDRESS} for the {@code PROPERTYKEY} struct rather
 * than passing it by value — Windows x64 ABI passes structs &gt; 8
 * bytes through a hidden pointer, which matches this descriptor in
 * practice. The actual native registration is best-effort; the
 * canonical exercise of the dispatch logic is via
 * {@link #dispatchPropertyChanged(MemorySegment)}.</p>
 */
final class WasapiFormatChangeShim implements AutoCloseable {

    /** {@code S_OK} HRESULT. */
    static final int S_OK = 0;
    /** {@code E_NOINTERFACE} HRESULT. */
    static final int E_NOINTERFACE = 0x80004002;

    /**
     * {@code PKEY_AudioEngine_DeviceFormat} GUID in little-endian byte
     * order: {@code {f19f064d-082c-4e27-bc73-6882a1bb8e4c}}, pid 0.
     */
    static final byte[] PKEY_AUDIO_ENGINE_DEVICE_FORMAT_GUID = new byte[]{
            (byte) 0x4d, (byte) 0x06, (byte) 0x9f, (byte) 0xf1,
            (byte) 0x2c, (byte) 0x08,
            (byte) 0x27, (byte) 0x4e,
            (byte) 0xbc, (byte) 0x73,
            (byte) 0x68, (byte) 0x82, (byte) 0xa1, (byte) 0xbb,
            (byte) 0x8e, (byte) 0x4c
    };

    /** Expected pid component of the matching {@code PROPERTYKEY}. */
    static final int PKEY_AUDIO_ENGINE_DEVICE_FORMAT_PID = 0;

    /** {@code IID_IUnknown} = {@code {00000000-0000-0000-C000-000000000046}}. */
    private static final byte[] IID_IUNKNOWN = new byte[]{
            0, 0, 0, 0,
            0, 0,
            0, 0,
            (byte) 0xC0, 0,
            0, 0, 0, 0, 0, (byte) 0x46
    };

    /** {@code IID_IMMNotificationClient} = {@code {7991EEC9-7E89-4D85-8390-6C703CEC60C0}}. */
    private static final byte[] IID_IMMNOTIFICATION_CLIENT = new byte[]{
            (byte) 0xC9, (byte) 0xEE, (byte) 0x91, (byte) 0x79,
            (byte) 0x89, (byte) 0x7E,
            (byte) 0x85, (byte) 0x4D,
            (byte) 0x83, (byte) 0x90,
            (byte) 0x6C, (byte) 0x70, (byte) 0x3C, (byte) 0xEC,
            (byte) 0x60, (byte) 0xC0
    };

    /** {@code S_FALSE} HRESULT. */
    private static final int S_FALSE = 1;
    /** {@code RPC_E_CHANGED_MODE} HRESULT — COM already initialized in a different threading model. */
    private static final int RPC_E_CHANGED_MODE = 0x80010106;
    /** {@code COINIT_APARTMENTTHREADED}. */
    private static final int COINIT_APARTMENTTHREADED = 0x2;
    /** {@code CLSCTX_ALL} = CLSCTX_INPROC_SERVER | CLSCTX_INPROC_HANDLER | CLSCTX_LOCAL_SERVER | CLSCTX_REMOTE_SERVER. */
    private static final int CLSCTX_ALL = 0x17;
    /** Vtable slot index for {@code IMMDeviceEnumerator::RegisterEndpointNotificationCallback}. */
    private static final int IMMDEVICE_ENUMERATOR_REGISTER_ENDPOINT_NOTIFICATION_CALLBACK_SLOT = 6;
    /** Vtable slot index for {@code IMMDeviceEnumerator::UnregisterEndpointNotificationCallback}. */
    private static final int IMMDEVICE_ENUMERATOR_UNREGISTER_ENDPOINT_NOTIFICATION_CALLBACK_SLOT = 7;
    /** Vtable slot index for {@code IUnknown::Release}. */
    private static final int IUNKNOWN_RELEASE_SLOT = 2;

    /** {@code CLSID_MMDeviceEnumerator} = {@code {BCDE0395-E52F-467C-8E3D-C4579291692E}}. */
    private static final byte[] CLSID_MMDEVICE_ENUMERATOR = new byte[] {
            (byte) 0x95, (byte) 0x03, (byte) 0xDE, (byte) 0xBC,
            (byte) 0x2F, (byte) 0xE5,
            (byte) 0x7C, (byte) 0x46,
            (byte) 0x8E, (byte) 0x3D,
            (byte) 0xC4, (byte) 0x57, (byte) 0x92, (byte) 0x91,
            (byte) 0x69, (byte) 0x2E
    };

    /** {@code IID_IMMDeviceEnumerator} = {@code {A95664D2-9614-4F35-A746-DE8DB63617E6}}. */
    private static final byte[] IID_IMMDEVICE_ENUMERATOR = new byte[] {
            (byte) 0xD2, (byte) 0x64, (byte) 0x56, (byte) 0xA9,
            (byte) 0x14, (byte) 0x96,
            (byte) 0x35, (byte) 0x4F,
            (byte) 0xA7, (byte) 0x46,
            (byte) 0xDE, (byte) 0x8D, (byte) 0xB6, (byte) 0x36,
            (byte) 0x17, (byte) 0xE6
    };
    static final long PROPERTYKEY_SIZE = 20L;
    private static final long GUID_SIZE = 16L;

    private final WasapiBackend backend;
    private final DeviceId device;
    private final Arena arena;
    private final MemorySegment vtable;
    private final MemorySegment instance;
    /** The {@code IMMDeviceEnumerator*} obtained via {@code CoCreateInstance}, or {@code NULL}. */
    private MemorySegment enumerator = MemorySegment.NULL;
    private final boolean registered;
    private boolean closed;

    /**
     * Builds the eight upcall stubs, lays out the COM "fat pointer"
     * (16-byte instance whose first 8 bytes point to the vtable), and
     * (on Windows) calls {@code RegisterEndpointNotificationCallback}
     * on the {@code IMMDeviceEnumerator} created via
     * {@code CoCreateInstance}.
     *
     * @param backend owning backend; never null
     * @param device  device id used when publishing; never null
     */
    WasapiFormatChangeShim(WasapiBackend backend, DeviceId device) {
        this.backend = Objects.requireNonNull(backend, "backend must not be null");
        this.device = Objects.requireNonNull(device, "device must not be null");
        this.arena = Arena.ofConfined();
        MemorySegment vt;
        MemorySegment inst;
        try {
            vt = buildVtable();
            // 16-byte instance: first 8 bytes are the vtable pointer.
            inst = arena.allocate(16);
            inst.set(ValueLayout.ADDRESS, 0L, vt);
        } catch (Throwable t) {
            vt = MemorySegment.NULL;
            inst = MemorySegment.NULL;
        }
        this.vtable = vt;
        this.instance = inst;
        this.registered = tryRegister();
    }

    private MemorySegment buildVtable() throws Throwable {
        Linker linker = Linker.nativeLinker();
        MethodHandles.Lookup lookup = MethodHandles.lookup();

        // 1. QueryInterface(this, REFIID, void**) -> HRESULT
        MethodHandle qiHandle = lookup.findVirtual(WasapiFormatChangeShim.class,
                        "queryInterfaceUpcall",
                        MethodType.methodType(int.class,
                                MemorySegment.class, MemorySegment.class, MemorySegment.class))
                .bindTo(this);
        MemorySegment qi = linker.upcallStub(qiHandle,
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                arena);

        // 2. AddRef(this) -> ULONG, 3. Release(this) -> ULONG
        MethodHandle refcountHandle = lookup.findVirtual(WasapiFormatChangeShim.class,
                        "refcountUpcall", MethodType.methodType(int.class, MemorySegment.class))
                .bindTo(this);
        FunctionDescriptor refcountDesc = FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
        MemorySegment addRef = linker.upcallStub(refcountHandle, refcountDesc, arena);
        MemorySegment release = linker.upcallStub(refcountHandle, refcountDesc, arena);

        // 4-6. OnDeviceStateChanged / OnDeviceAdded / OnDeviceRemoved
        MethodHandle stateChangedHandle = lookup.findVirtual(WasapiFormatChangeShim.class,
                        "onDeviceStateChangedUpcall",
                        MethodType.methodType(int.class,
                                MemorySegment.class, MemorySegment.class, int.class))
                .bindTo(this);
        MemorySegment onStateChanged = linker.upcallStub(stateChangedHandle,
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                arena);

        MethodHandle addRemoveHandle = lookup.findVirtual(WasapiFormatChangeShim.class,
                        "onDeviceAddedOrRemovedUpcall",
                        MethodType.methodType(int.class,
                                MemorySegment.class, MemorySegment.class))
                .bindTo(this);
        FunctionDescriptor addRemoveDesc = FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
        MemorySegment onAdded = linker.upcallStub(addRemoveHandle, addRemoveDesc, arena);
        MemorySegment onRemoved = linker.upcallStub(addRemoveHandle, addRemoveDesc, arena);

        // 7. OnDefaultDeviceChanged(this, EDataFlow, ERole, LPCWSTR) -> HRESULT
        MethodHandle defaultChangedHandle = lookup.findVirtual(WasapiFormatChangeShim.class,
                        "onDefaultDeviceChangedUpcall",
                        MethodType.methodType(int.class,
                                MemorySegment.class, int.class, int.class, MemorySegment.class))
                .bindTo(this);
        MemorySegment onDefault = linker.upcallStub(defaultChangedHandle,
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                arena);

        // 8. OnPropertyValueChanged(this, LPCWSTR, PROPERTYKEY) -> HRESULT
        MethodHandle propValueHandle = lookup.findVirtual(WasapiFormatChangeShim.class,
                        "onPropertyValueChangedUpcall",
                        MethodType.methodType(int.class,
                                MemorySegment.class, MemorySegment.class, MemorySegment.class))
                .bindTo(this);
        MemorySegment onProperty = linker.upcallStub(propValueHandle,
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                arena);

        // Layout the vtable: 8 contiguous 8-byte function pointers.
        MemorySegment vt = arena.allocate(8L * 8L);
        vt.set(ValueLayout.ADDRESS, 0L * 8L, qi);
        vt.set(ValueLayout.ADDRESS, 1L * 8L, addRef);
        vt.set(ValueLayout.ADDRESS, 2L * 8L, release);
        vt.set(ValueLayout.ADDRESS, 3L * 8L, onStateChanged);
        vt.set(ValueLayout.ADDRESS, 4L * 8L, onAdded);
        vt.set(ValueLayout.ADDRESS, 5L * 8L, onRemoved);
        vt.set(ValueLayout.ADDRESS, 6L * 8L, onDefault);
        vt.set(ValueLayout.ADDRESS, 7L * 8L, onProperty);
        return vt;
    }

    private static boolean hresultSucceeded(int hr) {
        return hr >= 0;
    }

    private static boolean coInitializeSucceeded(int hr) {
        return hr == S_OK || hr == S_FALSE || hr == RPC_E_CHANGED_MODE;
    }

    private MemorySegment allocateGuid(byte[] guidBytes) {
        MemorySegment guid = arena.allocate(guidBytes.length, 1);
        MemorySegment.copy(MemorySegment.ofArray(guidBytes), 0, guid, 0, guidBytes.length);
        return guid;
    }

    private static MemorySegment vtableSlot(MemorySegment comInterface, int slotIndex) {
        MemorySegment vtablePtr = comInterface.reinterpret(ValueLayout.ADDRESS.byteSize())
                .get(ValueLayout.ADDRESS, 0);
        return vtablePtr.reinterpret((long) (slotIndex + 1) * ValueLayout.ADDRESS.byteSize())
                .get(ValueLayout.ADDRESS, (long) slotIndex * ValueLayout.ADDRESS.byteSize());
    }

    private boolean tryRegister() {
        if (instance.equals(MemorySegment.NULL)) {
            return false;
        }
        try {
            SymbolLookup ole32 = SymbolLookup.libraryLookup("Ole32", arena);
            Linker nativeLinker = Linker.nativeLinker();

            MethodHandle coInitializeEx = nativeLinker.downcallHandle(
                    ole32.find("CoInitializeEx").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            MethodHandle coCreateInstance = nativeLinker.downcallHandle(
                    ole32.find("CoCreateInstance").orElseThrow(),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));

            int initHr = (int) coInitializeEx.invokeExact(
                    MemorySegment.NULL, COINIT_APARTMENTTHREADED);
            if (!coInitializeSucceeded(initHr)) {
                return false;
            }

            MemorySegment clsid = allocateGuid(CLSID_MMDEVICE_ENUMERATOR);
            MemorySegment iid = allocateGuid(IID_IMMDEVICE_ENUMERATOR);
            MemorySegment enumeratorOut = arena.allocate(ValueLayout.ADDRESS);
            enumeratorOut.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);

            int createHr = (int) coCreateInstance.invokeExact(
                    clsid,
                    MemorySegment.NULL,
                    CLSCTX_ALL,
                    iid,
                    enumeratorOut);
            if (!hresultSucceeded(createHr)) {
                return false;
            }

            MemorySegment en = enumeratorOut.get(ValueLayout.ADDRESS, 0);
            if (en.equals(MemorySegment.NULL)) {
                return false;
            }

            MethodHandle registerEndpointNotificationCallback =
                    nativeLinker.downcallHandle(
                            vtableSlot(en,
                                    IMMDEVICE_ENUMERATOR_REGISTER_ENDPOINT_NOTIFICATION_CALLBACK_SLOT),
                            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            int registerHr = (int) registerEndpointNotificationCallback.invokeExact(
                    en, instance);
            if (!hresultSucceeded(registerHr)) {
                return false;
            }

            this.enumerator = en;
            return true;
        } catch (IllegalArgumentException | UnsatisfiedLinkError ignored) {
            // Library not present on this host (non-Windows) — no-op.
            return false;
        } catch (Throwable ignored) {
            // Any other failure — no-op.
            return false;
        }
    }

    // -- Upcall targets ------------------------------------------------------

    @SuppressWarnings("unused")
    private int queryInterfaceUpcall(MemorySegment self, MemorySegment riid, MemorySegment ppv) {
        try {
            if (riid == null || riid.equals(MemorySegment.NULL)) {
                return E_NOINTERFACE;
            }
            MemorySegment iid = riid.reinterpret(GUID_SIZE);
            if (guidMatches(iid, IID_IUNKNOWN) || guidMatches(iid, IID_IMMNOTIFICATION_CLIENT)) {
                if (ppv != null && !ppv.equals(MemorySegment.NULL)) {
                    ppv.reinterpret(8).set(ValueLayout.ADDRESS, 0L, self);
                }
                return S_OK;
            }
            return E_NOINTERFACE;
        } catch (Throwable t) {
            return E_NOINTERFACE;
        }
    }

    @SuppressWarnings("unused")
    private int refcountUpcall(MemorySegment self) {
        // Fixed reference count — the shim's lifetime is owned by close().
        return 2;
    }

    @SuppressWarnings("unused")
    private int onDeviceStateChangedUpcall(MemorySegment self,
                                           MemorySegment pwstrDeviceId, int dwNewState) {
        // Story 214 (device-loss) handles state changes; 218 is format-only.
        return S_OK;
    }

    @SuppressWarnings("unused")
    private int onDeviceAddedOrRemovedUpcall(MemorySegment self, MemorySegment pwstrDeviceId) {
        return S_OK;
    }

    @SuppressWarnings("unused")
    private int onDefaultDeviceChangedUpcall(MemorySegment self,
                                             int flow, int role, MemorySegment pwstrDeviceId) {
        return S_OK;
    }

    @SuppressWarnings("unused")
    private int onPropertyValueChangedUpcall(MemorySegment self,
                                             MemorySegment pwstrDeviceId,
                                             MemorySegment key) {
        try {
            return dispatchPropertyChanged(key);
        } catch (Throwable t) {
            return S_OK;
        }
    }

    // -- Dispatch ------------------------------------------------------------

    /**
     * Compares {@code key} (a 20-byte {@code PROPERTYKEY} = 16-byte GUID
     * + 4-byte DWORD pid) against
     * {@link #PKEY_AUDIO_ENGINE_DEVICE_FORMAT_GUID} / pid 0 and, on a
     * match, publishes a
     * {@link AudioDeviceEvent.FormatChangeRequested} with reason
     * {@link FormatChangeReason.SampleRateChange} and an empty proposed
     * format (story 218).
     *
     * <p>Package-private so that unit tests can invoke the comparison
     * logic with a hand-built {@code MemorySegment} containing either
     * a matching or a non-matching key.</p>
     *
     * @param key 20-byte {@code PROPERTYKEY} pointer (may be
     *            {@link MemorySegment#NULL} — treated as no-op)
     * @return {@link #S_OK}
     */
    int dispatchPropertyChanged(MemorySegment key) {
        if (key == null || key.equals(MemorySegment.NULL)) {
            return S_OK;
        }
        MemorySegment k = key.reinterpret(PROPERTYKEY_SIZE);
        if (!guidMatches(k, PKEY_AUDIO_ENGINE_DEVICE_FORMAT_GUID)) {
            return S_OK;
        }
        int pid = k.get(ValueLayout.JAVA_INT, GUID_SIZE);
        if (pid != PKEY_AUDIO_ENGINE_DEVICE_FORMAT_PID) {
            return S_OK;
        }
        backend.publishFormatChangeRequested(
                device, Optional.empty(), new FormatChangeReason.SampleRateChange());
        return S_OK;
    }

    private static boolean guidMatches(MemorySegment segment, byte[] expected) {
        if (segment == null || segment.equals(MemorySegment.NULL)) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (segment.get(ValueLayout.JAVA_BYTE, i) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return {@code true} if {@code RegisterEndpointNotificationCallback}
     *         was successfully invoked
     */
    boolean isRegistered() {
        return registered;
    }

    /**
     * Returns the COM "fat pointer" passed to
     * {@code RegisterEndpointNotificationCallback}. Exposed for testing.
     *
     * @return the instance pointer
     */
    MemorySegment instance() {
        return instance;
    }

    /**
     * Returns the vtable pointer (eight 8-byte function pointer slots).
     * Exposed for testing.
     *
     * @return the vtable pointer
     */
    MemorySegment vtable() {
        return vtable;
    }

    /**
     * Unregisters the notification callback (when registration
     * succeeded), releases the {@code IMMDeviceEnumerator}, and frees
     * the vtable / instance / upcall stubs. Idempotent.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // Unregister and release the enumerator when registration succeeded.
        if (registered && !enumerator.equals(MemorySegment.NULL)) {
            Linker nativeLinker = Linker.nativeLinker();
            try {
                // UnregisterEndpointNotificationCallback(enumerator, callback)
                MethodHandle unregister = nativeLinker.downcallHandle(
                        vtableSlot(enumerator,
                                IMMDEVICE_ENUMERATOR_UNREGISTER_ENDPOINT_NOTIFICATION_CALLBACK_SLOT),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                unregister.invokeExact(enumerator, instance);
            } catch (Throwable ignored) {
                // Best-effort: never throw from close().
            }
            try {
                // enumerator->Release()
                MethodHandle release = nativeLinker.downcallHandle(
                        vtableSlot(enumerator, IUNKNOWN_RELEASE_SLOT),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS));
                release.invokeExact(enumerator);
            } catch (Throwable ignored) {
                // Best-effort.
            }
        }
        try {
            arena.close();
        } catch (Throwable ignored) {
            // Already closed — idempotent.
        }
    }
}

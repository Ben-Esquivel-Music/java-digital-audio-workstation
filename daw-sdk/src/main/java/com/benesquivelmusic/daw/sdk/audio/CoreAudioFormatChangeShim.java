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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * FFM (JEP 454) shim that bridges CoreAudio's
 * {@code AudioObjectPropertyListenerProc} callbacks into
 * {@link CoreAudioBackend#publishFormatChangeRequested(DeviceId,
 * Optional, FormatChangeReason)} (story 218).
 *
 * <p>On macOS the shim resolves
 * {@code AudioObjectAddPropertyListener} from {@code CoreAudio.framework}
 * and registers a single shared listener for three property selectors:
 * {@code kAudioDevicePropertyNominalSampleRate},
 * {@code kAudioDevicePropertyBufferFrameSize}, and
 * {@code kAudioDevicePropertyClockSource}. On non-macOS hosts (or when
 * the framework cannot be resolved) registration is skipped and the
 * shim degrades to a no-op.</p>
 *
 * <p>The {@code AudioObjectID} target is currently a placeholder
 * ({@code kAudioObjectSystemObject == 1}); the implementation layer
 * that performs real device opening (story 130) must replace this with
 * the device's actual {@code AudioDeviceID} once it is known. Until
 * then the system-object listener still fires for system-wide property
 * changes, which is sufficient to demonstrate the round-trip.</p>
 */
final class CoreAudioFormatChangeShim implements AutoCloseable {

    /** {@code kAudioObjectSystemObject} — placeholder listener target. */
    static final int kAudioObjectSystemObject = 1;

    /** {@code kAudioDevicePropertyNominalSampleRate} = {@code 'nsrt'}. */
    static final int kSelNominalSampleRate = fourCC('n', 's', 'r', 't');
    /** {@code kAudioDevicePropertyBufferFrameSize} = {@code 'fsiz'}. */
    static final int kSelBufferFrameSize = fourCC('f', 's', 'i', 'z');
    /** {@code kAudioDevicePropertyClockSource} = {@code 'csrc'}. */
    static final int kSelClockSource = fourCC('c', 's', 'r', 'c');

    /** {@code kAudioObjectPropertyScopeGlobal} = {@code 'glob'}. */
    private static final int kScopeGlobal = fourCC('g', 'l', 'o', 'b');
    /** {@code kAudioObjectPropertyElementMain} = 0. */
    private static final int kElementMain = 0;

    /**
     * AudioObjectPropertyAddress is {@code { UInt32 mSelector; UInt32 mScope;
     * UInt32 mElement; }} = 12 bytes.
     */
    private static final long PROPERTY_ADDRESS_SIZE = 12L;

    /**
     * AudioObjectPropertyListenerProc descriptor:
     * {@code (UInt32 inObjectID, UInt32 inNumberAddresses,
     *         const AudioObjectPropertyAddress* inAddresses,
     *         void* inClientData) -> OSStatus}.
     */
    private static final FunctionDescriptor LISTENER_PROC =
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS);

    /**
     * AudioObjectAddPropertyListener / AudioObjectRemovePropertyListener
     * descriptor: {@code (UInt32 inObjectID,
     *                     const AudioObjectPropertyAddress* inAddress,
     *                     AudioObjectPropertyListenerProc inListener,
     *                     void* inClientData) -> OSStatus}.
     */
    private static final FunctionDescriptor ADD_REMOVE_LISTENER =
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS);

    private final CoreAudioBackend backend;
    private final DeviceId device;
    private final Arena arena;
    private final MemorySegment upcallStub;
    /** The {@code AudioObjectID} the listeners were registered on. */
    private final int registeredObjectID;
    /** Holds (selectorAddress, addPropertyListenerHandle, removeHandle) per registration. */
    private final List<MemorySegment> registeredAddresses = new ArrayList<>();
    private final MethodHandle removeListener;
    private boolean closed;

    /**
     * Builds the shared listener upcall stub and (on macOS) registers it
     * for the three property selectors.
     *
     * @param backend  owning backend; never null
     * @param device   device id to use when publishing; never null
     * @param objectID the {@code AudioObjectID} to register listeners on;
     *                 use {@link #kAudioObjectSystemObject} as a
     *                 placeholder until the real {@code AudioDeviceID}
     *                 is known (story 130)
     */
    CoreAudioFormatChangeShim(CoreAudioBackend backend, DeviceId device, int objectID) {
        this.backend = Objects.requireNonNull(backend, "backend must not be null");
        this.device = Objects.requireNonNull(device, "device must not be null");
        this.registeredObjectID = objectID;
        this.arena = Arena.ofConfined();
        this.upcallStub = buildUpcallStub();
        this.removeListener = tryRegister();
    }

    /**
     * Convenience constructor that registers on
     * {@link #kAudioObjectSystemObject} — used until story 130 surfaces
     * the real {@code AudioDeviceID}.
     */
    CoreAudioFormatChangeShim(CoreAudioBackend backend, DeviceId device) {
        this(backend, device, kAudioObjectSystemObject);
    }

    private MemorySegment buildUpcallStub() {
        try {
            MethodHandle handle = MethodHandles.lookup().findVirtual(
                    CoreAudioFormatChangeShim.class,
                    "listenerUpcall",
                    MethodType.methodType(int.class,
                            int.class, int.class,
                            MemorySegment.class, MemorySegment.class))
                    .bindTo(this);
            return Linker.nativeLinker().upcallStub(handle, LISTENER_PROC, arena);
        } catch (Throwable t) {
            return MemorySegment.NULL;
        }
    }

    private MethodHandle tryRegister() {
        if (upcallStub.equals(MemorySegment.NULL)) {
            return null;
        }
        try {
            SymbolLookup lookup;
            try {
                lookup = SymbolLookup.libraryLookup("CoreAudio", arena);
            } catch (IllegalArgumentException firstAttempt) {
                // Fall back to the absolute framework path the dynamic
                // linker uses on macOS.
                lookup = SymbolLookup.libraryLookup(
                        "/System/Library/Frameworks/CoreAudio.framework/CoreAudio",
                        arena);
            }
            MemorySegment addSym = lookup.find("AudioObjectAddPropertyListener")
                    .orElseThrow(() -> new UnsatisfiedLinkError(
                            "AudioObjectAddPropertyListener not found"));
            MemorySegment removeSym = lookup.find("AudioObjectRemovePropertyListener")
                    .orElseThrow(() -> new UnsatisfiedLinkError(
                            "AudioObjectRemovePropertyListener not found"));
            MethodHandle add = Linker.nativeLinker().downcallHandle(
                    addSym, ADD_REMOVE_LISTENER);
            MethodHandle remove = Linker.nativeLinker().downcallHandle(
                    removeSym, ADD_REMOVE_LISTENER);
            for (int sel : new int[]{
                    kSelNominalSampleRate, kSelBufferFrameSize, kSelClockSource}) {
                MemorySegment addr = arena.allocate(PROPERTY_ADDRESS_SIZE);
                addr.set(ValueLayout.JAVA_INT, 0L, sel);
                addr.set(ValueLayout.JAVA_INT, 4L, kScopeGlobal);
                addr.set(ValueLayout.JAVA_INT, 8L, kElementMain);
                int status = (int) add.invoke(
                        registeredObjectID, addr, upcallStub, MemorySegment.NULL);
                if (status == 0) {
                    registeredAddresses.add(addr);
                }
            }
            return remove;
        } catch (IllegalArgumentException | UnsatisfiedLinkError ignored) {
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Method handle target invoked by the FFM upcall stub when the
     * CoreAudio dispatch queue delivers a property change.
     */
    @SuppressWarnings("unused") // invoked via upcall stub
    private int listenerUpcall(int inObjectID, int inNumberAddresses,
                               MemorySegment inAddresses,
                               MemorySegment inClientData) {
        try {
            return dispatch(inObjectID, inNumberAddresses, inAddresses);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Iterates {@code inNumberAddresses} {@code AudioObjectPropertyAddress}
     * structs starting at {@code inAddresses} and publishes a
     * {@link AudioDeviceEvent.FormatChangeRequested} for each recognised
     * selector (story 218).
     *
     * <p>Selector mapping:</p>
     * <ul>
     *   <li>{@link #kSelNominalSampleRate} &rarr;
     *       {@link FormatChangeReason.SampleRateChange}</li>
     *   <li>{@link #kSelBufferFrameSize} &rarr;
     *       {@link FormatChangeReason.BufferSizeChange}</li>
     *   <li>{@link #kSelClockSource} &rarr;
     *       {@link FormatChangeReason.ClockSourceChange}</li>
     * </ul>
     *
     * <p>When {@code inObjectID} does not match the
     * {@link #registeredObjectID} the shim was registered on, the
     * callback is silently ignored — this prevents unrelated system-wide
     * property changes (for other devices) from being attributed to the
     * active device and triggering unnecessary reconfiguration.</p>
     *
     * <p>{@code proposedFormat} is always {@link Optional#empty()}: the
     * listener fires before the new property value is fully readable on
     * the dispatch queue, so the controller re-queries it on reopen.</p>
     *
     * <p>Package-private for unit-test access.</p>
     *
     * @param inObjectID         the {@code AudioObjectID} that fired
     * @param inNumberAddresses  number of property addresses in the array
     * @param inAddresses        pointer to the address array; may be
     *                           {@link MemorySegment#NULL} (no-op)
     * @return {@code 0} (noErr)
     */
    int dispatch(int inObjectID, int inNumberAddresses, MemorySegment inAddresses) {
        if (inAddresses == null
                || inAddresses.equals(MemorySegment.NULL)
                || inNumberAddresses <= 0) {
            return 0;
        }
        // Ignore callbacks from objects that don't match the registered
        // target — prevents unrelated devices' property changes from
        // triggering reconfiguration when registered on
        // kAudioObjectSystemObject (story 218 review feedback).
        if (inObjectID != registeredObjectID) {
            return 0;
        }
        MemorySegment addrs = inAddresses.reinterpret(
                PROPERTY_ADDRESS_SIZE * inNumberAddresses);
        for (int i = 0; i < inNumberAddresses; i++) {
            int selector = addrs.get(ValueLayout.JAVA_INT, i * PROPERTY_ADDRESS_SIZE);
            FormatChangeReason reason = mapSelector(selector);
            if (reason != null) {
                backend.publishFormatChangeRequested(
                        device, Optional.empty(), reason);
            }
        }
        return 0;
    }

    private static FormatChangeReason mapSelector(int selector) {
        if (selector == kSelNominalSampleRate) {
            return new FormatChangeReason.SampleRateChange();
        }
        if (selector == kSelBufferFrameSize) {
            return new FormatChangeReason.BufferSizeChange();
        }
        if (selector == kSelClockSource) {
            return new FormatChangeReason.ClockSourceChange();
        }
        return null;
    }

    /**
     * Returns {@code true} if at least one property listener was
     * successfully registered with CoreAudio.
     *
     * @return true if any registration succeeded
     */
    boolean isRegistered() {
        return !registeredAddresses.isEmpty();
    }

    /**
     * Returns the upcall stub address used as the
     * {@code AudioObjectPropertyListenerProc} pointer. Exposed for testing.
     *
     * @return the upcall stub address
     */
    MemorySegment upcallStub() {
        return upcallStub;
    }

    /**
     * Encodes a four-character constant the same way CoreAudio's headers
     * do: {@code (a&lt;&lt;24)|(b&lt;&lt;16)|(c&lt;&lt;8)|d}.
     *
     * @param a first character
     * @param b second character
     * @param c third character
     * @param d fourth character
     * @return the 4-CC integer
     */
    static int fourCC(char a, char b, char c, char d) {
        return (a << 24) | (b << 16) | (c << 8) | d;
    }

    /**
     * Removes every registered property listener and frees the upcall
     * stub. Idempotent.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (removeListener != null) {
            for (MemorySegment addr : registeredAddresses) {
                try {
                    removeListener.invoke(
                            registeredObjectID, addr, upcallStub, MemorySegment.NULL);
                } catch (Throwable ignored) {
                    // Best-effort: never throw from close().
                }
            }
        }
        registeredAddresses.clear();
        try {
            arena.close();
        } catch (Throwable ignored) {
            // Already closed — idempotent.
        }
    }
}

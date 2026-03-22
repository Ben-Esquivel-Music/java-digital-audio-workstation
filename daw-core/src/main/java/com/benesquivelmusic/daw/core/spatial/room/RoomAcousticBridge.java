package com.benesquivelmusic.daw.core.spatial.room;

import com.benesquivelmusic.daw.sdk.spatial.RoomSimulationConfig;
import com.benesquivelmusic.daw.sdk.telemetry.ListenerOrientation;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;
import com.benesquivelmusic.daw.sdk.telemetry.WallMaterial;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * FFM bridge (JEP 454) to the RoomAcoustiC++ native library.
 *
 * <p>This bridge uses the Foreign Function &amp; Memory API to invoke
 * RoomAcoustiC++ functions for room acoustic simulation. The native
 * library must be present on the system's library path or specified
 * explicitly via {@link #load(Path)}.</p>
 *
 * <p>If the native library is not available, the bridge reports
 * unavailability and callers should fall back to the pure-Java
 * {@link FdnRoomSimulator}.</p>
 *
 * <p>Expected native API surface (C linkage):</p>
 * <pre>{@code
 * void* rac_create_room(double width, double length, double height, int sampleRate);
 * void  rac_destroy_room(void* room);
 * void  rac_set_wall_absorption(void* room, int surfaceIndex, double absorption);
 * void  rac_set_source_position(void* room, double x, double y, double z);
 * void  rac_set_listener_position(void* room, double x, double y, double z,
 *                                  double yaw, double pitch);
 * int   rac_generate_impulse_response(void* room, float* outBuffer, int maxSamples);
 * }</pre>
 */
public final class RoomAcousticBridge implements AutoCloseable {

    private static final String LIBRARY_NAME = "roomacousticpp";

    private final Arena arena;
    private final MethodHandle createRoom;
    private final MethodHandle destroyRoom;
    private final MethodHandle setWallAbsorption;
    private final MethodHandle setSourcePosition;
    private final MethodHandle setListenerPosition;
    private final MethodHandle generateImpulseResponse;

    private MemorySegment roomHandle;
    private boolean closed;

    /**
     * Attempts to load the RoomAcoustiC++ native library from the
     * system library path.
     *
     * @return an optional containing the bridge if loading succeeds,
     *         or empty if the native library is unavailable
     */
    public static Optional<RoomAcousticBridge> tryLoad() {
        try {
            return Optional.of(new RoomAcousticBridge(
                    SymbolLookup.libraryLookup(System.mapLibraryName(LIBRARY_NAME), Arena.global())));
        } catch (IllegalArgumentException | UnsatisfiedLinkError e) {
            return Optional.empty();
        }
    }

    /**
     * Loads the RoomAcoustiC++ native library from a specific path.
     *
     * @param libraryPath the path to the shared library file
     * @return an optional containing the bridge if loading succeeds,
     *         or empty if the library file is not found or invalid
     */
    public static Optional<RoomAcousticBridge> load(Path libraryPath) {
        Objects.requireNonNull(libraryPath, "libraryPath must not be null");
        try {
            return Optional.of(new RoomAcousticBridge(
                    SymbolLookup.libraryLookup(libraryPath, Arena.global())));
        } catch (IllegalArgumentException | UnsatisfiedLinkError e) {
            return Optional.empty();
        }
    }

    private RoomAcousticBridge(SymbolLookup lookup) {
        this.arena = Arena.ofConfined();
        Linker linker = Linker.nativeLinker();

        // rac_create_room(double, double, double, int) -> void*
        this.createRoom = linker.downcallHandle(
                lookup.find("rac_create_room").orElseThrow(),
                FunctionDescriptor.of(
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE,
                        ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_INT));

        // rac_destroy_room(void*)
        this.destroyRoom = linker.downcallHandle(
                lookup.find("rac_destroy_room").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

        // rac_set_wall_absorption(void*, int, double)
        this.setWallAbsorption = linker.downcallHandle(
                lookup.find("rac_set_wall_absorption").orElseThrow(),
                FunctionDescriptor.ofVoid(
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_DOUBLE));

        // rac_set_source_position(void*, double, double, double)
        this.setSourcePosition = linker.downcallHandle(
                lookup.find("rac_set_source_position").orElseThrow(),
                FunctionDescriptor.ofVoid(
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE));

        // rac_set_listener_position(void*, double, double, double, double, double)
        this.setListenerPosition = linker.downcallHandle(
                lookup.find("rac_set_listener_position").orElseThrow(),
                FunctionDescriptor.ofVoid(
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE,
                        ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE));

        // rac_generate_impulse_response(void*, float*, int) -> int
        this.generateImpulseResponse = linker.downcallHandle(
                lookup.find("rac_generate_impulse_response").orElseThrow(),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    }

    /**
     * Creates a native room with the given dimensions and sample rate.
     *
     * @param dimensions the room dimensions
     * @param sampleRate the audio sample rate in Hz
     */
    public void createRoom(RoomDimensions dimensions, int sampleRate) {
        ensureOpen();
        try {
            if (roomHandle != null) {
                destroyRoom.invokeExact(roomHandle);
            }
            roomHandle = (MemorySegment) createRoom.invokeExact(
                    dimensions.width(), dimensions.length(), dimensions.height(), sampleRate);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to create native room", t);
        }
    }

    /**
     * Sets the absorption coefficient for a room surface in the native model.
     *
     * @param surfaceIndex the surface index (0–5: floor, ceiling, left, right, front, back)
     * @param absorption   the absorption coefficient in [0.0, 1.0]
     */
    public void setWallAbsorption(int surfaceIndex, double absorption) {
        ensureOpen();
        ensureRoom();
        try {
            setWallAbsorption.invokeExact(roomHandle, surfaceIndex, absorption);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to set wall absorption", t);
        }
    }

    /**
     * Sets the source position in the native model.
     *
     * @param position the 3D source position
     */
    public void setSourcePosition(Position3D position) {
        ensureOpen();
        ensureRoom();
        try {
            setSourcePosition.invokeExact(roomHandle, position.x(), position.y(), position.z());
        } catch (Throwable t) {
            throw new RuntimeException("Failed to set source position", t);
        }
    }

    /**
     * Sets the listener position and orientation in the native model.
     *
     * @param orientation the listener orientation
     */
    public void setListenerPosition(ListenerOrientation orientation) {
        ensureOpen();
        ensureRoom();
        Position3D pos = orientation.position();
        try {
            setListenerPosition.invokeExact(roomHandle,
                    pos.x(), pos.y(), pos.z(),
                    orientation.yawDegrees(), orientation.pitchDegrees());
        } catch (Throwable t) {
            throw new RuntimeException("Failed to set listener position", t);
        }
    }

    /**
     * Generates an impulse response from the current native room state.
     *
     * @param maxSamples the maximum number of samples to generate
     * @return the generated impulse response samples
     */
    public float[] generateImpulseResponse(int maxSamples) {
        ensureOpen();
        ensureRoom();
        try (Arena local = Arena.ofConfined()) {
            MemorySegment buffer = local.allocate(
                    MemoryLayout.sequenceLayout(maxSamples, ValueLayout.JAVA_FLOAT));
            int actualSamples = (int) generateImpulseResponse.invokeExact(
                    roomHandle, buffer, maxSamples);
            float[] result = new float[actualSamples];
            MemorySegment.copy(buffer, ValueLayout.JAVA_FLOAT, 0, result, 0, actualSamples);
            return result;
        } catch (Throwable t) {
            throw new RuntimeException("Failed to generate impulse response", t);
        }
    }

    /**
     * Configures the native room from a {@link RoomSimulationConfig}.
     *
     * @param config the simulation configuration
     */
    public void configureFromSimulation(RoomSimulationConfig config) {
        createRoom(config.dimensions(), config.sampleRate());

        // Set per-surface absorption
        for (int i = 0; i < RoomSimulationConfig.SURFACE_NAMES.size(); i++) {
            String surface = RoomSimulationConfig.SURFACE_NAMES.get(i);
            double absorption = config.materialForSurface(surface).absorptionCoefficient();
            setWallAbsorption(i, absorption);
        }

        // Set first source position (native API supports one source at a time)
        if (!config.sources().isEmpty()) {
            setSourcePosition(config.sources().getFirst().position());
        }

        // Set listener
        setListenerPosition(config.listener());
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            if (roomHandle != null) {
                try {
                    destroyRoom.invokeExact(roomHandle);
                } catch (Throwable t) {
                    // Best-effort cleanup
                }
                roomHandle = null;
            }
            arena.close();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Bridge has been closed");
        }
    }

    private void ensureRoom() {
        if (roomHandle == null) {
            throw new IllegalStateException("No room has been created; call createRoom() first");
        }
    }
}

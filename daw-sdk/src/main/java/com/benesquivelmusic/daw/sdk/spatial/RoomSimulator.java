package com.benesquivelmusic.daw.sdk.spatial;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.telemetry.ListenerOrientation;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;

/**
 * Room acoustic simulation engine that models sound propagation in a
 * virtual room and produces auralized audio output.
 *
 * <p>Implementations combine early reflections (image-source method) with
 * late reverberation (Feedback Delay Network) to generate physically-based
 * impulse responses. The impulse response is then applied via convolution
 * to produce real-time auralization of dry audio signals.</p>
 *
 * <p>Key capabilities:</p>
 * <ul>
 *   <li>Configurable rectangular room geometry and per-surface materials</li>
 *   <li>Multiple sound source placement within the room</li>
 *   <li>Listener position and orientation tracking</li>
 *   <li>Real-time impulse response generation</li>
 *   <li>Convolution-based audio processing (via {@link AudioProcessor})</li>
 * </ul>
 *
 * <p>Implementations may use a pure-Java fallback (FDN) or a native
 * accelerated path via the FFM API (JEP 454) binding to the
 * RoomAcoustiC++ library.</p>
 */
public interface RoomSimulator extends AudioProcessor {

    /**
     * Configures the room simulation with the given parameters.
     *
     * <p>This replaces any previous configuration and triggers
     * regeneration of the impulse response.</p>
     *
     * @param config the room simulation configuration
     */
    void configure(RoomSimulationConfig config);

    /**
     * Returns the current room simulation configuration, or {@code null}
     * if the simulator has not been configured.
     *
     * @return the current configuration
     */
    RoomSimulationConfig getConfiguration();

    /**
     * Updates the listener position and orientation.
     *
     * <p>Changing the listener position triggers regeneration of the
     * impulse response on the next processing cycle.</p>
     *
     * @param orientation the new listener orientation
     */
    void setListenerOrientation(ListenerOrientation orientation);

    /**
     * Returns the current listener orientation.
     *
     * @return the listener orientation
     */
    ListenerOrientation getListenerOrientation();

    /**
     * Adds a sound source to the room simulation.
     *
     * @param source the sound source to add
     */
    void addSource(SoundSource source);

    /**
     * Removes a sound source by name.
     *
     * @param sourceName the name of the source to remove
     * @return {@code true} if the source was found and removed
     */
    boolean removeSource(String sourceName);

    /**
     * Updates the position of an existing sound source.
     *
     * @param sourceName the name of the source to update
     * @param position   the new 3D position
     * @return {@code true} if the source was found and updated
     */
    boolean updateSourcePosition(String sourceName, Position3D position);

    /**
     * Generates and returns the current impulse response based on the
     * room configuration, source positions, and listener orientation.
     *
     * <p>The IR may be mono or multi-channel depending on the
     * implementation.</p>
     *
     * @return the computed impulse response
     * @throws IllegalStateException if the simulator has not been configured
     */
    ImpulseResponse generateImpulseResponse();

    /**
     * Returns whether this simulator is using native acceleration via
     * the RoomAcoustiC++ FFM bridge.
     *
     * @return {@code true} if native acceleration is active
     */
    boolean isNativeAccelerated();
}

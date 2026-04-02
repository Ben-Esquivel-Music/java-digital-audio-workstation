package com.benesquivelmusic.daw.core.spatial.room;

import com.benesquivelmusic.daw.acoustics.simulator.AcousticsRoomSimulator;
import com.benesquivelmusic.daw.sdk.spatial.ImpulseResponse;
import com.benesquivelmusic.daw.sdk.spatial.RoomSimulationConfig;
import com.benesquivelmusic.daw.sdk.spatial.RoomSimulator;
import com.benesquivelmusic.daw.sdk.telemetry.ListenerOrientation;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;

/**
 * Room acoustic simulator that delegates to the full daw-acoustics engine
 * ({@link AcousticsRoomSimulator}).
 *
 * <p>This class was originally a simplified FDN-based room simulator. It now
 * delegates to the comprehensive pure-Java acoustics engine ported from
 * RoomAcoustiCpp, which provides:</p>
 * <ul>
 *   <li>Image-source method with first and second order reflections</li>
 *   <li>Feedback Delay Network (Householder) for late reverberation</li>
 *   <li>Per-surface frequency-dependent absorption</li>
 *   <li>Air absorption modeling</li>
 *   <li>Full room geometry with triangulated walls</li>
 * </ul>
 *
 * <p>This replaces the former native RoomAcoustiC++ FFM bridge — no native
 * library is required.</p>
 *
 * @see AcousticsRoomSimulator
 */
public final class FdnRoomSimulator implements RoomSimulator {

    private final AcousticsRoomSimulator delegate;

    /**
     * Creates an FDN room simulator. Call {@link #configure(RoomSimulationConfig)}
     * before processing audio.
     */
    public FdnRoomSimulator() {
        this.delegate = new AcousticsRoomSimulator();
    }

    @Override
    public void configure(RoomSimulationConfig config) {
        delegate.configure(config);
    }

    @Override
    public RoomSimulationConfig getConfiguration() {
        return delegate.getConfiguration();
    }

    @Override
    public void setListenerOrientation(ListenerOrientation orientation) {
        delegate.setListenerOrientation(orientation);
    }

    @Override
    public ListenerOrientation getListenerOrientation() {
        return delegate.getListenerOrientation();
    }

    @Override
    public void addSource(SoundSource source) {
        delegate.addSource(source);
    }

    @Override
    public boolean removeSource(String sourceName) {
        return delegate.removeSource(sourceName);
    }

    @Override
    public boolean updateSourcePosition(String sourceName, Position3D position) {
        return delegate.updateSourcePosition(sourceName, position);
    }

    @Override
    public ImpulseResponse generateImpulseResponse() {
        return delegate.generateImpulseResponse();
    }

    @Override
    public boolean isNativeAccelerated() {
        return false;
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        delegate.process(inputBuffer, outputBuffer, numFrames);
    }

    @Override
    public void reset() {
        delegate.reset();
    }

    @Override
    public int getInputChannelCount() {
        return delegate.getInputChannelCount();
    }

    @Override
    public int getOutputChannelCount() {
        return delegate.getOutputChannelCount();
    }

    /**
     * Estimates RT60 using the Sabine equation.
     *
     * @param dims          the room dimensions
     * @param avgAbsorption the average absorption coefficient
     * @return the estimated RT60 in seconds
     */
    static double estimateRt60(RoomDimensions dims, double avgAbsorption) {
        return AcousticsRoomSimulator.estimateRt60(dims, avgAbsorption);
    }
}

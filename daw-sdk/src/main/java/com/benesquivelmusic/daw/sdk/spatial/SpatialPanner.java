package com.benesquivelmusic.daw.sdk.spatial;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.List;

/**
 * 3D spatial panner for positioning audio sources in three-dimensional space.
 *
 * <p>Supports VBAP (Vector Base Amplitude Panning) for speaker-based rendering,
 * distance attenuation modeling, source size/spread controls, and full pan
 * automation. This is the foundational component for immersive audio mixing
 * (Dolby Atmos, Apple Spatial Audio, Ambisonics workflows).</p>
 *
 * <p>Key capabilities:</p>
 * <ul>
 *   <li>3D position (azimuth, elevation, distance) with Cartesian conversion</li>
 *   <li>Distance attenuation with configurable rolloff curve</li>
 *   <li>Distance-based spectral filtering (HF rolloff)</li>
 *   <li>Distance-based early reflection/reverb send</li>
 *   <li>Source size/spread for diffuse source rendering</li>
 *   <li>Pan automation with per-parameter curves</li>
 *   <li>Snap-to-speaker and free-form positioning modes</li>
 * </ul>
 */
public interface SpatialPanner extends AudioProcessor {

    // ---- Position ----

    /**
     * Sets the 3D source position.
     *
     * @param position the source position
     */
    void setPosition(SpatialPosition position);

    /**
     * Returns the current 3D source position.
     *
     * @return the source position
     */
    SpatialPosition getPosition();

    // ---- Size / Spread ----

    /**
     * Sets the source size (spread) factor.
     *
     * <p>A value of 0 represents a point source. A value of 1 represents
     * a fully diffuse source spread across all speakers. Intermediate
     * values blend between point and diffuse rendering.</p>
     *
     * @param spread the spread factor in [0, 1]
     */
    void setSpread(double spread);

    /**
     * Returns the current source spread factor.
     *
     * @return the spread factor in [0, 1]
     */
    double getSpread();

    // ---- Distance Attenuation ----

    /**
     * Sets the distance attenuation model.
     *
     * @param model the attenuation model to use
     */
    void setDistanceAttenuationModel(DistanceAttenuationModel model);

    /**
     * Returns the current distance attenuation model.
     *
     * @return the attenuation model
     */
    DistanceAttenuationModel getDistanceAttenuationModel();

    // ---- Positioning Mode ----

    /**
     * Sets the positioning mode.
     *
     * @param mode the positioning mode
     */
    void setPositioningMode(PositioningMode mode);

    /**
     * Returns the current positioning mode.
     *
     * @return the positioning mode
     */
    PositioningMode getPositioningMode();

    // ---- Automation ----

    /**
     * Sets the pan automation curve for this panner.
     *
     * @param curve the automation curve, or {@code null} to clear automation
     */
    void setAutomationCurve(PanAutomationCurve curve);

    /**
     * Returns the current pan automation curve, or {@code null} if none is set.
     *
     * @return the automation curve
     */
    PanAutomationCurve getAutomationCurve();

    // ---- Speaker Layout ----

    /**
     * Sets the speaker positions for VBAP rendering and snap-to-speaker mode.
     *
     * @param speakers the speaker positions
     */
    void setSpeakerPositions(List<SpatialPosition> speakers);

    /**
     * Returns the current speaker positions.
     *
     * @return the speaker positions
     */
    List<SpatialPosition> getSpeakerPositions();

    // ---- Per-Speaker Gains ----

    /**
     * Returns the computed per-speaker gain coefficients for the current
     * source position and spread settings.
     *
     * <p>The array length matches the number of configured speakers.
     * These gains represent the VBAP-computed amplitude panning
     * coefficients, with energy preservation applied.</p>
     *
     * @return the per-speaker gain array
     */
    double[] computeSpeakerGains();

    // ---- Visualization ----

    /**
     * Returns the current panner state as a visualization record.
     *
     * @return the panner visualization data
     */
    SpatialPannerData getPannerData();
}

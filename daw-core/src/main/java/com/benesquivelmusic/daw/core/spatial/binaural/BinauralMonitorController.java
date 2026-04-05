package com.benesquivelmusic.daw.core.spatial.binaural;

import com.benesquivelmusic.daw.core.spatial.objectbased.FoldDownRenderer;
import com.benesquivelmusic.daw.sdk.spatial.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Central controller for binaural monitoring with HRTF profile selection.
 *
 * <p>Integrates the binaural rendering pipeline — including A/B monitoring
 * mode switching, built-in and custom HRTF profile selection, the
 * {@link BinauralExternalizationProcessor} for improved headphone
 * spatialization, and fold-down monitoring preview via
 * {@link FoldDownRenderer}.</p>
 *
 * <h2>Typical usage</h2>
 * <ol>
 *   <li>Create a controller for the current sample rate and block size</li>
 *   <li>Select an HRTF profile ({@link #selectProfile}) or import a custom
 *       SOFA file ({@link #importCustomHrtf})</li>
 *   <li>Toggle between speaker and binaural monitoring
 *       ({@link #setMonitoringMode})</li>
 *   <li>Optionally enable the externalization processor
 *       ({@link #setExternalizationEnabled})</li>
 *   <li>Optionally set a fold-down target layout
 *       ({@link #setFoldDownTarget})</li>
 *   <li>Call {@link #process} on each audio block in the monitoring path</li>
 * </ol>
 *
 * @see DefaultBinauralRenderer
 * @see BinauralExternalizationProcessor
 * @see FoldDownRenderer
 * @see SofaFileParser
 */
public final class BinauralMonitorController {

    private final double sampleRate;
    private final int blockSize;

    private final DefaultBinauralRenderer binauralRenderer;
    private final BinauralExternalizationProcessor externalizationProcessor;

    private MonitoringMode monitoringMode;
    private HrtfProfile activeProfile;
    private HrtfData activeHrtfData;
    private String customHrtfName;
    private boolean externalizationEnabled;
    private SpeakerLayout foldDownTarget;

    /**
     * Creates a binaural monitor controller.
     *
     * @param sampleRate the audio sample rate in Hz (must be positive)
     * @param blockSize  the processing block size (must be a positive power of 2)
     */
    public BinauralMonitorController(double sampleRate, int blockSize) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (blockSize <= 0 || (blockSize & (blockSize - 1)) != 0) {
            throw new IllegalArgumentException("blockSize must be a positive power of 2: " + blockSize);
        }
        this.sampleRate = sampleRate;
        this.blockSize = blockSize;
        this.binauralRenderer = new DefaultBinauralRenderer(sampleRate, blockSize);
        this.externalizationProcessor = new BinauralExternalizationProcessor(sampleRate, blockSize);
        this.monitoringMode = MonitoringMode.SPEAKER;
        this.externalizationEnabled = false;
    }

    // ---- Monitoring mode (A/B switching) ------------------------------------

    /**
     * Sets the monitoring mode for A/B switching between speaker and binaural
     * rendering.
     *
     * <p>In {@link MonitoringMode#SPEAKER} mode, audio passes through
     * unchanged. In {@link MonitoringMode#BINAURAL} mode, HRTF-based
     * spatialization is applied.</p>
     *
     * @param mode the monitoring mode (must not be null)
     */
    public void setMonitoringMode(MonitoringMode mode) {
        Objects.requireNonNull(mode, "mode must not be null");
        this.monitoringMode = mode;
        binauralRenderer.setMonitoringMode(mode);
    }

    /**
     * Returns the current monitoring mode.
     *
     * @return the monitoring mode
     */
    public MonitoringMode getMonitoringMode() {
        return monitoringMode;
    }

    /**
     * Toggles between {@link MonitoringMode#SPEAKER} and
     * {@link MonitoringMode#BINAURAL} mode.
     */
    public void toggleMonitoringMode() {
        if (monitoringMode == MonitoringMode.SPEAKER) {
            setMonitoringMode(MonitoringMode.BINAURAL);
        } else {
            setMonitoringMode(MonitoringMode.SPEAKER);
        }
    }

    /**
     * Returns a human-readable label for the current monitoring mode,
     * suitable for display in the transport bar or monitoring section.
     *
     * @return {@code "Speakers"} or {@code "Binaural"}
     */
    public String getMonitoringModeDisplayName() {
        return monitoringMode == MonitoringMode.SPEAKER ? "Speakers" : "Binaural";
    }

    // ---- HRTF profile selection ---------------------------------------------

    /**
     * Selects a built-in HRTF profile for binaural rendering.
     *
     * <p>Generates a synthetic HRTF dataset appropriate for the profile's
     * head size and loads it into the binaural renderer. Clears any
     * previously imported custom HRTF.</p>
     *
     * @param profile the built-in profile to activate (must not be null)
     */
    public void selectProfile(HrtfProfile profile) {
        Objects.requireNonNull(profile, "profile must not be null");
        this.activeProfile = profile;
        this.customHrtfName = null;

        HrtfData data = generateHrtfDataForProfile(profile);
        this.activeHrtfData = data;
        binauralRenderer.loadHrtfData(data);
        externalizationProcessor.loadHrtfData(data);
    }

    /**
     * Returns the currently selected built-in HRTF profile, or {@code null}
     * if a custom HRTF is active.
     *
     * @return the active profile, or {@code null}
     */
    public HrtfProfile getActiveProfile() {
        return activeProfile;
    }

    /**
     * Returns the currently loaded HRTF dataset, or {@code null} if none
     * has been loaded.
     *
     * @return the active HRTF data
     */
    public HrtfData getActiveHrtfData() {
        return activeHrtfData;
    }

    /**
     * Returns all available built-in HRTF profiles.
     *
     * @return an unmodifiable list of profiles
     */
    public List<HrtfProfile> getAvailableProfiles() {
        return List.of(HrtfProfile.values());
    }

    // ---- Custom HRTF import -------------------------------------------------

    /**
     * Imports a custom HRTF dataset from a SOFA file.
     *
     * <p>The SOFA file is parsed using {@link SofaFileParser} and the
     * resulting {@link HrtfData} is loaded into the binaural renderer.
     * Clears any previously selected built-in profile.</p>
     *
     * @param sofaFile path to the SOFA file (must not be null)
     * @throws IOException if the file cannot be read or is not a valid SOFA file
     */
    public void importCustomHrtf(Path sofaFile) throws IOException {
        Objects.requireNonNull(sofaFile, "sofaFile must not be null");
        HrtfData data = SofaFileParser.parse(sofaFile);
        this.activeProfile = null;
        this.customHrtfName = data.profileName();
        this.activeHrtfData = data;
        binauralRenderer.loadHrtfData(data);
        externalizationProcessor.loadHrtfData(data);
    }

    /**
     * Loads a pre-constructed custom HRTF dataset.
     *
     * <p>Useful for loading HRTFs from non-SOFA sources or for testing.
     * Clears any previously selected built-in profile.</p>
     *
     * @param data the custom HRTF data (must not be null)
     */
    public void loadCustomHrtfData(HrtfData data) {
        Objects.requireNonNull(data, "data must not be null");
        this.activeProfile = null;
        this.customHrtfName = data.profileName();
        this.activeHrtfData = data;
        binauralRenderer.loadHrtfData(data);
        externalizationProcessor.loadHrtfData(data);
    }

    /**
     * Returns the name of the currently loaded custom HRTF, or {@code null}
     * if a built-in profile is active or no HRTF has been loaded.
     *
     * @return the custom HRTF name, or {@code null}
     */
    public String getCustomHrtfName() {
        return customHrtfName;
    }

    /**
     * Returns whether a custom (non-built-in) HRTF is currently active.
     *
     * @return {@code true} if a custom HRTF is loaded
     */
    public boolean isCustomHrtfActive() {
        return customHrtfName != null;
    }

    // ---- Externalization processor ------------------------------------------

    /**
     * Enables or disables the {@link BinauralExternalizationProcessor}.
     *
     * <p>When enabled, the externalization processor is applied as a
     * post-processing stage after binaural rendering to improve the
     * perceived spatial quality of headphone playback.</p>
     *
     * @param enabled {@code true} to enable externalization
     */
    public void setExternalizationEnabled(boolean enabled) {
        this.externalizationEnabled = enabled;
    }

    /**
     * Returns whether the externalization processor is enabled.
     *
     * @return {@code true} if externalization is enabled
     */
    public boolean isExternalizationEnabled() {
        return externalizationEnabled;
    }

    // ---- Fold-down monitoring -----------------------------------------------

    /**
     * Sets the target speaker layout for fold-down monitoring preview.
     *
     * <p>When a fold-down target is set, the full mix is folded down to the
     * target layout before any binaural rendering. Pass {@code null} to
     * disable fold-down.</p>
     *
     * <p>Supported targets: {@link SpeakerLayout#LAYOUT_5_1},
     * {@link SpeakerLayout#LAYOUT_STEREO}, or a mono layout.</p>
     *
     * @param target the target layout, or {@code null} to disable fold-down
     */
    public void setFoldDownTarget(SpeakerLayout target) {
        this.foldDownTarget = target;
    }

    /**
     * Returns the current fold-down target layout, or {@code null} if
     * fold-down is disabled.
     *
     * @return the fold-down target, or {@code null}
     */
    public SpeakerLayout getFoldDownTarget() {
        return foldDownTarget;
    }

    // ---- Audio processing ---------------------------------------------------

    /**
     * Processes audio through the monitoring chain.
     *
     * <p>Processing order:</p>
     * <ol>
     *   <li>Fold-down (if a target is set and input has more channels)</li>
     *   <li>Binaural rendering (if in {@link MonitoringMode#BINAURAL} mode)</li>
     *   <li>Externalization post-processing (if enabled and in binaural mode)</li>
     * </ol>
     *
     * <p>In {@link MonitoringMode#SPEAKER} mode, audio passes through unchanged
     * (with optional fold-down applied).</p>
     *
     * @param inputBuffer  the input audio buffer {@code [channel][frame]}
     * @param outputBuffer the output audio buffer {@code [channel][frame]}
     * @param numFrames    the number of sample frames to process
     */
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        float[][] currentInput = inputBuffer;

        // Step 1: Apply fold-down if configured and input has more channels
        if (foldDownTarget != null && currentInput.length > foldDownTarget.channelCount()) {
            currentInput = FoldDownRenderer.foldDown(currentInput, foldDownTarget, numFrames);
        }

        // Step 2: In speaker mode, pass through
        if (monitoringMode == MonitoringMode.SPEAKER) {
            int channels = Math.min(currentInput.length, outputBuffer.length);
            for (int ch = 0; ch < channels; ch++) {
                System.arraycopy(currentInput[ch], 0, outputBuffer[ch], 0, numFrames);
            }
            return;
        }

        // Step 3: Binaural rendering
        binauralRenderer.process(currentInput, outputBuffer, numFrames);

        // Step 4: Externalization post-processing
        if (externalizationEnabled) {
            float[][] extOutput = new float[2][numFrames];
            externalizationProcessor.process(outputBuffer, extOutput, numFrames);
            System.arraycopy(extOutput[0], 0, outputBuffer[0], 0, numFrames);
            if (outputBuffer.length > 1) {
                System.arraycopy(extOutput[1], 0, outputBuffer[1], 0, numFrames);
            }
        }
    }

    /**
     * Resets all internal processing state.
     */
    public void reset() {
        binauralRenderer.reset();
        externalizationProcessor.reset();
    }

    // ---- Internal helpers ---------------------------------------------------

    /**
     * Returns the underlying binaural renderer (for advanced configuration).
     *
     * @return the binaural renderer
     */
    DefaultBinauralRenderer getBinauralRenderer() {
        return binauralRenderer;
    }

    /**
     * Returns the underlying externalization processor (for advanced configuration).
     *
     * @return the externalization processor
     */
    BinauralExternalizationProcessor getExternalizationProcessor() {
        return externalizationProcessor;
    }

    /**
     * Generates a synthetic HRTF dataset for the given built-in profile.
     *
     * <p>The generated dataset provides a set of measured directions with
     * impulse responses scaled by a head-size factor derived from the
     * profile's circumference, modeling the effect of head size on
     * interaural time and level differences.</p>
     */
    private HrtfData generateHrtfDataForProfile(HrtfProfile profile) {
        double headFactor = profile.headCircumferenceCm() / HrtfProfile.MEDIUM.headCircumferenceCm();
        int irLength = blockSize;

        List<SphericalCoordinate> positions = new ArrayList<>();
        positions.add(new SphericalCoordinate(0, 0, 1.0));    // front
        positions.add(new SphericalCoordinate(90, 0, 1.0));   // left
        positions.add(new SphericalCoordinate(180, 0, 1.0));  // back
        positions.add(new SphericalCoordinate(270, 0, 1.0));  // right

        int m = positions.size();
        float[][][] ir = new float[m][2][irLength];
        float[][] delays = new float[m][2];

        // Front: nearly equal both ears
        ir[0][0][0] = 0.9f;
        ir[0][1][0] = 0.85f;
        delays[0][0] = 0;
        delays[0][1] = 0;

        // Left: louder left, quieter right with ITD
        ir[1][0][0] = 1.0f;
        ir[1][1][0] = 0.4f;
        delays[1][0] = 0;
        delays[1][1] = (float) (5.0 * headFactor);

        // Back: equal both ears
        ir[2][0][0] = 0.5f;
        ir[2][1][0] = 0.5f;
        delays[2][0] = 0;
        delays[2][1] = 0;

        // Right: quieter left, louder right with ITD
        ir[3][0][0] = 0.4f;
        ir[3][1][0] = 1.0f;
        delays[3][0] = (float) (5.0 * headFactor);
        delays[3][1] = 0;

        return new HrtfData(
                profile.displayName() + " (" + profile.headCircumferenceCm() + " cm)",
                sampleRate,
                positions,
                ir,
                delays);
    }
}

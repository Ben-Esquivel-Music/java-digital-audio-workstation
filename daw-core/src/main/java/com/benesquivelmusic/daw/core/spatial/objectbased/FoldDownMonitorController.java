package com.benesquivelmusic.daw.core.spatial.objectbased;

import com.benesquivelmusic.daw.sdk.spatial.FoldDownCoefficients;
import com.benesquivelmusic.daw.sdk.spatial.MonitoringFormat;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLayout;

import java.util.List;
import java.util.Objects;

/**
 * Controller for fold-down monitoring preview — switches between immersive,
 * surround, stereo, and mono monitoring formats in real time.
 *
 * <p>Engineers use this to verify that an immersive mix translates well when
 * played back on different systems. The controller applies real-time
 * fold-down rendering via {@link FoldDownRenderer}, using either the
 * standard ITU-R BS.775 coefficients or user-configured custom
 * coefficients.</p>
 *
 * <h2>Typical usage</h2>
 * <ol>
 *   <li>Create a controller for the source format
 *       (e.g. {@link MonitoringFormat#IMMERSIVE_7_1_4})</li>
 *   <li>Set the monitoring format via {@link #setMonitoringFormat}
 *       (e.g. {@link MonitoringFormat#STEREO})</li>
 *   <li>Optionally configure custom coefficients via
 *       {@link #setCoefficients}</li>
 *   <li>Call {@link #process} on each audio block in the monitoring path</li>
 * </ol>
 *
 * <p>Format switching is instant — no audible gap or additional latency is
 * introduced when changing the monitoring format.</p>
 *
 * @see FoldDownRenderer
 * @see MonitoringFormat
 * @see FoldDownCoefficients
 */
public final class FoldDownMonitorController {

    private final MonitoringFormat sourceFormat;

    private volatile MonitoringFormat monitoringFormat;
    private volatile FoldDownCoefficients coefficients;

    /**
     * Creates a fold-down monitor controller with the given source format.
     *
     * <p>The monitoring format defaults to the source format (pass-through,
     * no fold-down), and the coefficients default to ITU-R BS.775.</p>
     *
     * @param sourceFormat the native/source monitoring format (must not be null)
     */
    public FoldDownMonitorController(MonitoringFormat sourceFormat) {
        this.sourceFormat = Objects.requireNonNull(sourceFormat, "sourceFormat must not be null");
        this.monitoringFormat = sourceFormat;
        this.coefficients = FoldDownCoefficients.ITU_R_BS_775;
    }

    // ---- Monitoring format selection ----------------------------------------

    /**
     * Sets the monitoring format for fold-down preview.
     *
     * <p>Switching is instant — the next call to {@link #process} will
     * render in the new format with no audible gap.</p>
     *
     * @param format the target monitoring format (must not be null)
     * @throws IllegalArgumentException if the target format has more channels
     *                                  than the source format
     */
    public void setMonitoringFormat(MonitoringFormat format) {
        Objects.requireNonNull(format, "format must not be null");
        if (format.channelCount() > sourceFormat.channelCount()) {
            throw new IllegalArgumentException(
                    "Cannot monitor in " + format.displayName()
                            + " (" + format.channelCount() + " channels)"
                            + " — source format is " + sourceFormat.displayName()
                            + " (" + sourceFormat.channelCount() + " channels)");
        }
        this.monitoringFormat = format;
    }

    /**
     * Returns the current monitoring format.
     *
     * @return the monitoring format
     */
    public MonitoringFormat getMonitoringFormat() {
        return monitoringFormat;
    }

    /**
     * Returns the source (native) format that this controller was created for.
     *
     * @return the source format
     */
    public MonitoringFormat getSourceFormat() {
        return sourceFormat;
    }

    /**
     * Returns a human-readable display name for the current monitoring format,
     * suitable for the transport bar or monitoring section UI.
     *
     * @return the display name (e.g. "7.1.4", "Stereo", "Mono")
     */
    public String getMonitoringFormatDisplayName() {
        return monitoringFormat.displayName();
    }

    /**
     * Returns the available monitoring formats that can be selected, in order
     * from most channels to fewest. Only formats with equal or fewer channels
     * than the source format are included.
     *
     * @return an unmodifiable list of available formats
     */
    public List<MonitoringFormat> getAvailableFormats() {
        return List.of(MonitoringFormat.values()).stream()
                .filter(format -> format.channelCount() <= sourceFormat.channelCount())
                .toList();
    }

    // ---- Custom coefficients ------------------------------------------------

    /**
     * Sets custom fold-down coefficients for non-standard setups.
     *
     * <p>Pass {@link FoldDownCoefficients#ITU_R_BS_775} to restore the
     * standard coefficients.</p>
     *
     * @param coefficients the custom coefficients (must not be null)
     */
    public void setCoefficients(FoldDownCoefficients coefficients) {
        this.coefficients = Objects.requireNonNull(coefficients, "coefficients must not be null");
    }

    /**
     * Returns the current fold-down coefficients.
     *
     * @return the coefficients
     */
    public FoldDownCoefficients getCoefficients() {
        return coefficients;
    }

    /**
     * Returns whether custom (non-default) coefficients are active.
     *
     * @return {@code true} if coefficients differ from ITU-R BS.775
     */
    public boolean isCustomCoefficients() {
        return !coefficients.equals(FoldDownCoefficients.ITU_R_BS_775);
    }

    // ---- Audio processing ---------------------------------------------------

    /**
     * Processes audio through the fold-down monitoring chain.
     *
     * <p>If the monitoring format matches the source format, audio is
     * passed through unchanged. Otherwise, the input is folded down to
     * the target format using {@link FoldDownRenderer}.</p>
     *
     * @param input      the input audio buffer {@code [channel][frame]},
     *                   matching the source format's channel count
     * @param numFrames  the number of sample frames to process
     * @return the output buffer matching the monitoring format's channel count;
     *         may be the same reference as {@code input} if no fold-down is needed
     */
    public float[][] process(float[][] input, int numFrames) {
        MonitoringFormat currentFormat = monitoringFormat;

        if (currentFormat == sourceFormat) {
            return input;
        }

        SpeakerLayout targetLayout = currentFormat.toSpeakerLayout();
        FoldDownCoefficients currentCoefficients = coefficients;

        if (currentCoefficients.equals(FoldDownCoefficients.ITU_R_BS_775)) {
            return FoldDownRenderer.foldDown(input, targetLayout, numFrames);
        }

        return FoldDownRenderer.foldDown(input, targetLayout, numFrames, currentCoefficients);
    }
}

package com.benesquivelmusic.daw.app.ui.metering;

import com.benesquivelmusic.daw.app.ui.display.LevelMeterDisplay;
import com.benesquivelmusic.daw.core.metering.MeterFrame;

import java.util.Objects;

/**
 * Sink factories for the app's meter displays. The {@code .update(...)} of a
 * {@link LevelMeterDisplay} that carries a tap-bus subscription lives here —
 * inside {@code ui.metering} — so the no-fiction rule (a level display is
 * fed by the tap bus or not at all) is visible in one package and a
 * source-scan can enforce it.
 */
public final class MeterSinks {

    private MeterSinks() {
    }

    /**
     * A sink that pushes each frame's loudest-lane peak / RMS (in dB) and
     * clip flag into {@code display}. Runs on the FX thread (the pulse), once
     * per delivered block per <em>visible</em> meter, so it uses the
     * primitive {@code update(peakDb, rmsDb, clipping)} overload rather than
     * {@code frame.toLevelData()}: no {@code LevelData} record is allocated
     * per frame per strip ({@code javafx-application-design} §6, the same
     * rationale as {@code ChannelVM.peakDbFloored}).
     *
     * @param display the display to feed; must not be {@code null}
     * @return the sink
     */
    public static MeterSink levelMeterDisplay(LevelMeterDisplay display) {
        Objects.requireNonNull(display, "display must not be null");
        return frame -> display.update(MeterFrame.toDb(frame.maxPeak()),
                MeterFrame.toDb(frame.maxRms()), frame.clipped());
    }
}

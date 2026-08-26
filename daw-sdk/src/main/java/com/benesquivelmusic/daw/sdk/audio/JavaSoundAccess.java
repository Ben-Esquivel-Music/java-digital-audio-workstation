package com.benesquivelmusic.daw.sdk.audio;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

/**
 * Narrow, package-private boundary around Java Sound's static factories.
 *
 * <p>The production implementation delegates directly to {@link AudioSystem}.
 * Keeping that dependency behind this boundary lets the backend's format and
 * mixer-selection contract be proved on headless CI without manufacturing a
 * machine-specific {@link javax.sound.sampled.spi.MixerProvider}.</p>
 */
interface JavaSoundAccess {

    /** The production Java Sound access. */
    JavaSoundAccess SYSTEM = new JavaSoundAccess() {
        @Override
        public Mixer.Info[] mixerInfos() {
            return AudioSystem.getMixerInfo();
        }

        @Override
        public Line.Info[] sourceLineInfo(Mixer.Info mixerInfo) {
            return AudioSystem.getMixer(mixerInfo).getSourceLineInfo();
        }

        @Override
        public Line.Info[] targetLineInfo(Mixer.Info mixerInfo) {
            return AudioSystem.getMixer(mixerInfo).getTargetLineInfo();
        }

        @Override
        public boolean supportsSourceLine(
                Mixer.Info mixerInfo, javax.sound.sampled.AudioFormat format) {
            var info = new DataLine.Info(SourceDataLine.class, format);
            return mixerInfo == null
                    ? AudioSystem.isLineSupported(info)
                    : AudioSystem.getMixer(mixerInfo).isLineSupported(info);
        }

        @Override
        public boolean supportsTargetLine(
                Mixer.Info mixerInfo, javax.sound.sampled.AudioFormat format) {
            var info = new DataLine.Info(TargetDataLine.class, format);
            return mixerInfo == null
                    ? AudioSystem.isLineSupported(info)
                    : AudioSystem.getMixer(mixerInfo).isLineSupported(info);
        }

        @Override
        public SourceDataLine sourceLine(
                Mixer.Info mixerInfo, javax.sound.sampled.AudioFormat format)
                throws LineUnavailableException {
            if (mixerInfo == null) {
                return AudioSystem.getSourceDataLine(format);
            }
            var info = new DataLine.Info(SourceDataLine.class, format);
            return (SourceDataLine) AudioSystem.getMixer(mixerInfo).getLine(info);
        }

        @Override
        public TargetDataLine targetLine(
                Mixer.Info mixerInfo, javax.sound.sampled.AudioFormat format)
                throws LineUnavailableException {
            if (mixerInfo == null) {
                return AudioSystem.getTargetDataLine(format);
            }
            var info = new DataLine.Info(TargetDataLine.class, format);
            return (TargetDataLine) AudioSystem.getMixer(mixerInfo).getLine(info);
        }
    };

    Mixer.Info[] mixerInfos();

    Line.Info[] sourceLineInfo(Mixer.Info mixerInfo);

    Line.Info[] targetLineInfo(Mixer.Info mixerInfo);

    boolean supportsSourceLine(Mixer.Info mixerInfo, javax.sound.sampled.AudioFormat format);

    boolean supportsTargetLine(Mixer.Info mixerInfo, javax.sound.sampled.AudioFormat format);

    SourceDataLine sourceLine(Mixer.Info mixerInfo, javax.sound.sampled.AudioFormat format)
            throws LineUnavailableException;

    TargetDataLine targetLine(Mixer.Info mixerInfo, javax.sound.sampled.AudioFormat format)
            throws LineUnavailableException;

    /**
     * Whether a non-default {@link DeviceId} can be mapped to a mixer.
     * Production Java Sound can; the explicit outcome prevents any future
     * restricted provider from silently dropping a selection.
     */
    default boolean supportsDeviceSelection() {
        return true;
    }
}

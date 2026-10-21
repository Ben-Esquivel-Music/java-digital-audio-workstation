package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.export.AdmBwfExporter;
import com.benesquivelmusic.daw.core.export.WavExporter;
import com.benesquivelmusic.daw.core.recording.SegmentWriter;
import com.benesquivelmusic.daw.core.spatial.objectbased.AudioObject;
import com.benesquivelmusic.daw.core.spatial.objectbased.BedChannel;
import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.export.DitherType;
import com.benesquivelmusic.daw.sdk.spatial.ObjectMetadata;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLabel;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLayout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Audio files on disk for the relink and path tests: plain WAVs, take segments, an ADM BWF scene. */
final class AudioFileFixtures {

    private AudioFileFixtures() {
    }

    /** Writes a 16-bit WAV of a sine on every channel, creating its folder. */
    static Path wav(Path file, int sampleRate, int frames, int channels) throws IOException {
        float[][] audio = new float[channels][frames];
        for (int ch = 0; ch < channels; ch++) {
            for (int i = 0; i < frames; i++) {
                audio[ch][i] = (float) (0.25 * Math.sin(2.0 * Math.PI * 220.0 * i / sampleRate + ch));
            }
        }
        Files.createDirectories(file.getParent());
        WavExporter.write(audio, sampleRate, 16, DitherType.NONE, AudioMetadata.EMPTY, file);
        return file;
    }

    /**
     * Writes and seals segment {@code index} of a stereo take in {@code folder}
     * ({@code segment-<index>.wav}), as the capture path does.
     */
    static Path segment(Path folder, int index, double sampleRate, int frames) throws IOException {
        float[][] block = new float[2][frames];
        for (int i = 0; i < frames; i++) {
            block[0][i] = (float) (0.1 * (index + 1) * Math.sin(i / 7.0));
            block[1][i] = -block[0][i];
        }
        Files.createDirectories(folder);
        String name = String.format("segment-%03d.wav.part", index);
        try (SegmentWriter writer = SegmentWriter.open(folder.resolve(name), sampleRate, 2, 16,
                Duration.ZERO, System::nanoTime)) {
            writer.append(block, 2, frames);
            return writer.seal();
        }
    }

    /** Exports an ADM BWF scene: a stereo bed and two objects, 4096 frames each. */
    static Path admScene(Path output, int sampleRate) throws IOException {
        Files.createDirectories(output.getParent());
        List<BedChannel> beds = List.of(
                new BedChannel("bed-L", SpeakerLabel.L),
                new BedChannel("bed-R", SpeakerLabel.R));
        List<AudioObject> objects = List.of(
                new AudioObject("obj-1", new ObjectMetadata(0.5, 0.0, 0.2, 0.1, 0.9)),
                new AudioObject("obj-2", new ObjectMetadata(-0.5, 0.0, 0.2, 0.1, 0.9)));
        AdmBwfExporter.export(beds, List.of(tone(110.0, sampleRate), tone(220.0, sampleRate)),
                objects, List.of(tone(330.0, sampleRate), tone(440.0, sampleRate)),
                SpeakerLayout.LAYOUT_STEREO, sampleRate, 24, AudioMetadata.EMPTY, output);
        return output;
    }

    private static float[] tone(double hz, int sampleRate) {
        float[] samples = new float[4_096];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (float) (0.3 * Math.sin(2.0 * Math.PI * hz * i / sampleRate));
        }
        return samples;
    }
}

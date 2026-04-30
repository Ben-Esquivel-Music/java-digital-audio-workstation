package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.core.spatial.objectbased.AudioObject;
import com.benesquivelmusic.daw.core.spatial.objectbased.BedChannel;
import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.spatial.ObjectMetadata;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLabel;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLayout;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Exports audio to ADM BWF (Audio Definition Model Broadcast Wave Format)
 * for Dolby Atmos deliverables.
 *
 * <p>The ADM BWF container embeds:</p>
 * <ul>
 *   <li>Standard RIFF/WAVE header with all audio channels interleaved</li>
 *   <li>An {@code axml} chunk containing ADM XML metadata (ITU-R BS.2076)</li>
 *   <li>Channel order: bed channels first (in layout order), then audio objects</li>
 * </ul>
 */
public final class AdmBwfExporter {

    private AdmBwfExporter() {
        // utility class
    }

    /**
     * Number of sample frames to batch into a single off-heap
     * {@link MemorySegment} before flushing to the output stream.
     */
    private static final int CHUNK_FRAMES = 8192;

    // Little-endian value layouts for WAV sample encoding (JEP 454)
    private static final ValueLayout.OfFloat FLOAT_LE =
            ValueLayout.JAVA_FLOAT.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfShort SHORT_LE =
            ValueLayout.JAVA_SHORT.withOrder(ByteOrder.LITTLE_ENDIAN);

    /**
     * Exports an ADM BWF file containing bed channels and audio objects.
     *
     * @param bedChannels  bed channel assignments (ordered by layout)
     * @param bedAudio     audio buffers for bed channels ({@code [bed][sample]})
     * @param audioObjects audio objects with 3D metadata
     * @param objectAudio  audio buffers for objects ({@code [object][sample]})
     * @param layout       the speaker layout for bed channels
     * @param sampleRate   the sample rate in Hz
     * @param bitDepth     the target bit depth (16, 24, or 32)
     * @param metadata     file-level metadata
     * @param outputPath   the output file path
     * @throws IOException if an I/O error occurs
     */
    public static void export(List<BedChannel> bedChannels, List<float[]> bedAudio,
                              List<AudioObject> audioObjects, List<float[]> objectAudio,
                              SpeakerLayout layout, int sampleRate, int bitDepth,
                              AudioMetadata metadata, Path outputPath) throws IOException {
        export(bedChannels, bedAudio, audioObjects, objectAudio,
                List.of(), layout, sampleRate, bitDepth, metadata, outputPath);
    }

    /**
     * Exports an ADM BWF file with optional time-stamped per-object
     * trajectories (story 172). When a trajectory is supplied, the exporter
     * emits one {@code audioBlockFormat} per trajectory frame with
     * {@code rtime} and {@code duration} attributes; when the trajectory list
     * is empty (or its entry for an object is {@code null} / empty), the
     * exporter falls back to a single block format derived from the object's
     * static metadata, matching the legacy behaviour.
     *
     * @param bedChannels  bed channel assignments (ordered by layout)
     * @param bedAudio     audio buffers for bed channels ({@code [bed][sample]})
     * @param audioObjects audio objects with 3D metadata
     * @param objectAudio  audio buffers for objects ({@code [object][sample]})
     * @param trajectories per-object trajectories, parallel to {@code audioObjects};
     *                     may be empty (static export) or contain {@code null} /
     *                     empty entries (fall back to static metadata for that object)
     * @param layout       the speaker layout for bed channels
     * @param sampleRate   the sample rate in Hz
     * @param bitDepth     the target bit depth (16, 24, or 32)
     * @param metadata     file-level metadata
     * @param outputPath   the output file path
     * @throws IOException if an I/O error occurs
     */
    public static void export(List<BedChannel> bedChannels, List<float[]> bedAudio,
                              List<AudioObject> audioObjects, List<float[]> objectAudio,
                              List<ObjectTrajectory> trajectories,
                              SpeakerLayout layout, int sampleRate, int bitDepth,
                              AudioMetadata metadata, Path outputPath) throws IOException {
        Objects.requireNonNull(bedChannels, "bedChannels must not be null");
        Objects.requireNonNull(bedAudio, "bedAudio must not be null");
        Objects.requireNonNull(audioObjects, "audioObjects must not be null");
        Objects.requireNonNull(objectAudio, "objectAudio must not be null");
        Objects.requireNonNull(trajectories, "trajectories must not be null");
        Objects.requireNonNull(layout, "layout must not be null");
        Objects.requireNonNull(outputPath, "outputPath must not be null");

        int totalChannels = bedChannels.size() + audioObjects.size();
        int numSamples = determineSampleCount(bedAudio, objectAudio);
        int bytesPerSample = bitDepth / 8;
        boolean isFloat = (bitDepth == 32);
        short formatCode = isFloat ? (short) 3 : (short) 1;

        // Build ADM XML
        byte[] admXml = buildAdmXml(bedChannels, audioObjects, trajectories, layout, sampleRate, numSamples);

        // axml chunk: "axml" + size(4) + xml bytes (padded to even)
        int axmlPayloadSize = admXml.length;
        int axmlPaddedSize = axmlPayloadSize + (axmlPayloadSize % 2);
        int axmlChunkSize = 8 + axmlPaddedSize;

        int dataSize = numSamples * totalChannels * bytesPerSample;
        // RIFF(12) + fmt(24) + data header(8) + data + axml chunk
        int riffSize = 4 + 24 + 8 + dataSize + axmlChunkSize;

        try (OutputStream out = Files.newOutputStream(outputPath)) {
            ByteBuffer header = ByteBuffer.allocate(12 + 24 + 8).order(ByteOrder.LITTLE_ENDIAN);

            // RIFF header
            header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
            header.putInt(riffSize);
            header.put("WAVE".getBytes(StandardCharsets.US_ASCII));

            // fmt sub-chunk
            header.put("fmt ".getBytes(StandardCharsets.US_ASCII));
            header.putInt(16);
            header.putShort(formatCode);
            header.putShort((short) totalChannels);
            header.putInt(sampleRate);
            header.putInt(sampleRate * totalChannels * bytesPerSample);
            header.putShort((short) (totalChannels * bytesPerSample));
            header.putShort((short) bitDepth);

            // data sub-chunk header
            header.put("data".getBytes(StandardCharsets.US_ASCII));
            header.putInt(dataSize);

            out.write(header.array());

            // Write interleaved samples: beds first, then objects
            writeSamples(out, bedAudio, objectAudio, numSamples, totalChannels,
                    bitDepth, isFloat);

            // Write axml chunk
            ByteBuffer axmlHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            axmlHeader.put("axml".getBytes(StandardCharsets.US_ASCII));
            axmlHeader.putInt(axmlPaddedSize);
            out.write(axmlHeader.array());
            out.write(admXml);
            if (admXml.length % 2 != 0) {
                out.write(0); // pad byte
            }
        }
    }

    /**
     * Builds ADM XML metadata (simplified ITU-R BS.2076 structure).
     */
    static byte[] buildAdmXml(List<BedChannel> bedChannels,
                               List<AudioObject> audioObjects,
                               SpeakerLayout layout,
                               int sampleRate, int numSamples) {
        return buildAdmXml(bedChannels, audioObjects, List.of(), layout, sampleRate, numSamples);
    }

    /**
     * Builds ADM XML metadata, optionally with time-stamped per-object
     * trajectories. When {@code trajectories} is empty or an entry is
     * {@code null} / empty, a single static {@code audioBlockFormat} is
     * emitted for the object — matching the legacy behaviour.
     */
    static byte[] buildAdmXml(List<BedChannel> bedChannels,
                               List<AudioObject> audioObjects,
                               List<ObjectTrajectory> trajectories,
                               SpeakerLayout layout,
                               int sampleRate, int numSamples) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<audioFormatExtended version=\"ITU-R_BS.2076-2\">\n");

        // Programme
        sb.append("  <audioProgramme audioProgrammeID=\"APR_1001\" audioProgrammeName=\"Main\">\n");
        sb.append("    <audioContentIDRef>ACO_1001</audioContentIDRef>\n");
        sb.append("  </audioProgramme>\n");

        // Content
        sb.append("  <audioContent audioContentID=\"ACO_1001\" audioContentName=\"Main Content\">\n");

        // Bed pack
        if (!bedChannels.isEmpty()) {
            sb.append("    <audioObjectIDRef>AO_1001</audioObjectIDRef>\n");
        }
        // Object references
        for (int i = 0; i < audioObjects.size(); i++) {
            sb.append("    <audioObjectIDRef>AO_%04d</audioObjectIDRef>\n".formatted(1002 + i));
        }
        sb.append("  </audioContent>\n");

        // Bed object
        if (!bedChannels.isEmpty()) {
            sb.append("  <audioObject audioObjectID=\"AO_1001\" audioObjectName=\"Bed\">\n");
            sb.append("    <audioPackFormatIDRef>AP_00010002</audioPackFormatIDRef>\n");
            for (int i = 0; i < bedChannels.size(); i++) {
                sb.append("    <audioTrackUIDRef>ATU_%04d</audioTrackUIDRef>\n".formatted(i + 1));
            }
            sb.append("  </audioObject>\n");
        }

        // Audio objects
        int trackOffset = bedChannels.size();
        int blockId = 1;
        for (int i = 0; i < audioObjects.size(); i++) {
            AudioObject obj = audioObjects.get(i);
            ObjectMetadata meta = obj.getMetadata();
            sb.append("  <audioObject audioObjectID=\"AO_%04d\" audioObjectName=\"Object %d\">\n"
                    .formatted(1002 + i, i + 1));
            sb.append("    <audioPackFormatIDRef>AP_00031001</audioPackFormatIDRef>\n");
            sb.append("    <audioTrackUIDRef>ATU_%04d</audioTrackUIDRef>\n".formatted(trackOffset + i + 1));
            sb.append("  </audioObject>\n");

            // Block format(s) with position. When a trajectory is supplied,
            // emit one audioBlockFormat per frame with rtime and duration
            // attributes (story 172). Otherwise fall back to a single
            // static block from the object's metadata.
            ObjectTrajectory trajectory = (i < trajectories.size()) ? trajectories.get(i) : null;
            if (trajectory != null && !trajectory.isEmpty()) {
                for (ObjectTrajectory.Frame frame : trajectory.frames()) {
                    ObjectMetadata fm = frame.metadata();
                    sb.append("  <audioBlockFormat audioBlockFormatID=\"AB_%04d\" rtime=\"%s\" duration=\"%s\">\n"
                            .formatted(blockId++,
                                    formatAdmTime(frame.rtimeSeconds()),
                                    formatAdmTime(frame.durationSeconds())));
                    sb.append("    <position coordinate=\"X\">%.4f</position>\n".formatted(fm.x()));
                    sb.append("    <position coordinate=\"Y\">%.4f</position>\n".formatted(fm.y()));
                    sb.append("    <position coordinate=\"Z\">%.4f</position>\n".formatted(fm.z()));
                    sb.append("    <width>%.4f</width>\n".formatted(fm.size()));
                    sb.append("    <gain>%.4f</gain>\n".formatted(fm.gain()));
                    sb.append("  </audioBlockFormat>\n");
                }
            } else {
                sb.append("  <audioBlockFormat audioBlockFormatID=\"AB_%04d\">\n".formatted(blockId++));
                sb.append("    <position coordinate=\"X\">%.4f</position>\n".formatted(meta.x()));
                sb.append("    <position coordinate=\"Y\">%.4f</position>\n".formatted(meta.y()));
                sb.append("    <position coordinate=\"Z\">%.4f</position>\n".formatted(meta.z()));
                sb.append("    <width>%.4f</width>\n".formatted(meta.size()));
                sb.append("    <gain>%.4f</gain>\n".formatted(meta.gain()));
                sb.append("  </audioBlockFormat>\n");
            }
        }

        // Channel formats for bed channels
        for (int i = 0; i < bedChannels.size(); i++) {
            BedChannel bed = bedChannels.get(i);
            sb.append("  <audioChannelFormat audioChannelFormatID=\"AC_%04d\" " +
                      "audioChannelFormatName=\"%s\">\n".formatted(i + 1, bed.speakerLabel().name()));
            sb.append("    <speakerLabel>%s</speakerLabel>\n".formatted(
                    admSpeakerLabel(bed.speakerLabel())));
            sb.append("  </audioChannelFormat>\n");
        }

        sb.append("</audioFormatExtended>\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Formats a duration in seconds as an ADM-BWF {@code rtime}/{@code duration}
     * timecode of the form {@code HH:MM:SS.sssss}.
     */
    static String formatAdmTime(double seconds) {
        if (seconds < 0.0) {
            seconds = 0.0;
        }
        long totalMillis = Math.round(seconds * 1000.0);
        long hours = totalMillis / 3_600_000L;
        long mins = (totalMillis / 60_000L) % 60L;
        double secs = (totalMillis % 60_000L) / 1000.0;
        return "%02d:%02d:%08.5f".formatted(hours, mins, secs);
    }

    private static String admSpeakerLabel(SpeakerLabel label) {
        return switch (label) {
            case L -> "M+030";
            case R -> "M-030";
            case C -> "M+000";
            case LFE -> "LFE";
            case LS -> "M+110";
            case RS -> "M-110";
            case LRS -> "M+135";
            case RRS -> "M-135";
            case LTF -> "U+045";
            case RTF -> "U-045";
            case LTR -> "U+135";
            case RTR -> "U-135";
            case LW -> "M+060";
            case RW -> "M-060";
            case LTS -> "U+090";
            case RTS -> "U-090";
        };
    }

    /**
     * Writes interleaved audio samples (beds then objects) using an
     * off-heap {@link MemorySegment} chunk buffer (FFM API — JEP 454).
     *
     * <p>Batches frames into a reusable off-heap buffer and writes each
     * chunk in a single I/O call, reducing the number of
     * {@code OutputStream.write()} calls from {@code numSamples × totalChannels}
     * to {@code ceil(numSamples / CHUNK_FRAMES)}.</p>
     */
    private static void writeSamples(OutputStream out, List<float[]> bedAudio,
                                     List<float[]> objectAudio, int numSamples,
                                     int totalChannels, int bitDepth,
                                     boolean isFloat) throws IOException {
        int bytesPerSample = bitDepth / 8;
        int bytesPerFrame = totalChannels * bytesPerSample;

        try (Arena arena = Arena.ofConfined()) {
            int chunkByteCount = CHUNK_FRAMES * bytesPerFrame;
            MemorySegment chunk = arena.allocate(chunkByteCount);
            byte[] ioBuffer = new byte[chunkByteCount];
            MemorySegment ioSegment = MemorySegment.ofArray(ioBuffer);

            int framesWritten = 0;
            while (framesWritten < numSamples) {
                int framesToWrite = Math.min(CHUNK_FRAMES, numSamples - framesWritten);
                int writeByteCount = framesToWrite * bytesPerFrame;

                for (int i = 0; i < framesToWrite; i++) {
                    int sampleIndex = framesWritten + i;
                    int channelIndex = 0;

                    // Bed channels first
                    for (float[] channel : bedAudio) {
                        long offset = (long) (i * totalChannels + channelIndex) * bytesPerSample;
                        writeSampleToSegment(chunk, offset, channel[sampleIndex], bitDepth, isFloat);
                        channelIndex++;
                    }
                    // Then object channels
                    for (float[] channel : objectAudio) {
                        long offset = (long) (i * totalChannels + channelIndex) * bytesPerSample;
                        writeSampleToSegment(chunk, offset, channel[sampleIndex], bitDepth, isFloat);
                        channelIndex++;
                    }
                }

                MemorySegment.copy(chunk, 0, ioSegment, 0, writeByteCount);
                out.write(ioBuffer, 0, writeByteCount);
                framesWritten += framesToWrite;
            }
        }
    }

    private static void writeSampleToSegment(MemorySegment segment, long offset,
                                              float sample, int bitDepth, boolean isFloat) {
        double clamped = Math.max(-1.0, Math.min(1.0, sample));

        if (isFloat) {
            segment.set(FLOAT_LE, offset, (float) clamped);
        } else {
            double maxVal = (1L << (bitDepth - 1)) - 1;
            long value = Math.round(clamped * maxVal);
            switch (bitDepth) {
                case 16 -> segment.set(SHORT_LE, offset, (short) value);
                case 24 -> {
                    segment.set(ValueLayout.JAVA_BYTE, offset, (byte) (value & 0xFF));
                    segment.set(ValueLayout.JAVA_BYTE, offset + 1, (byte) ((value >> 8) & 0xFF));
                    segment.set(ValueLayout.JAVA_BYTE, offset + 2, (byte) ((value >> 16) & 0xFF));
                }
                default -> throw new IllegalArgumentException("Unsupported bit depth: " + bitDepth);
            }
        }
    }

    private static int determineSampleCount(List<float[]> bedAudio, List<float[]> objectAudio) {
        int max = 0;
        for (float[] buf : bedAudio) {
            max = Math.max(max, buf.length);
        }
        for (float[] buf : objectAudio) {
            max = Math.max(max, buf.length);
        }
        return max;
    }
}

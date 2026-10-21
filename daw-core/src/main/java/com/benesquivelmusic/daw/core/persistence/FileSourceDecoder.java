package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audioimport.AdmBwfImporter;
import com.benesquivelmusic.daw.core.audioimport.AdmClipAudio;
import com.benesquivelmusic.daw.core.audioimport.AudioReadResult;
import com.benesquivelmusic.daw.core.audioimport.ReferenceFileLoader;
import com.benesquivelmusic.daw.core.audioimport.SupportedAudioFormat;
import com.benesquivelmusic.daw.core.recording.SegmentFile;
import com.benesquivelmusic.daw.sdk.audio.SourceRateMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The production {@link SourceDecoder}: rebuilds what the producing path
 * held in memory, through the producing path's own readers (Persistence
 * Integrity book §4.3; decisions D1, D2, D3).
 * <ul>
 *   <li>A single file is decoded with the shared decode
 *       ({@link ReferenceFileLoader#read}) and converted to the project rate
 *       with the import's converter
 *       ({@link ReferenceFileLoader#convertSampleRate}) — sample-identical
 *       to what the import attached.</li>
 *   <li>A {@code .wav} that carries an ADM {@code axml} chunk — judged from
 *       its chunk headers alone ({@link AdmBwfImporter#isAdmBwf}) — is
 *       parsed once and split into the import's bed and object clips
 *       ({@link AdmClipAudio#extract}).</li>
 *   <li>A take is read at its native capture rate
 *       ({@link SegmentFile#readFrames(List)}, which checks that the segments
 *       agree on channels and rate) with the {@link SourceRateMetadata} the
 *       capture path attaches, its rate from the first segment's header.</li>
 * </ul>
 */
final class FileSourceDecoder implements SourceDecoder {

    /** The production decoder; stateless. */
    static final FileSourceDecoder INSTANCE = new FileSourceDecoder();

    private FileSourceDecoder() {
    }

    @Override
    public long estimateCost(AssetReference source, int projectSampleRate) {
        try {
            return switch (source) {
                case AssetReference.SingleFile file -> fileCost(Path.of(file.reference()), projectSampleRate);
                case AssetReference.Take take -> takeCost(take.files());
            };
        } catch (IOException | RuntimeException unknown) {
            // The decode reports what is wrong; the budget charges nothing it cannot judge.
            return 0L;
        }
    }

    /**
     * The readers hold the file's bytes while decoding, the decode holds one
     * decoded copy, and a conversion to the project rate a second one — made
     * only when the file is at another rate, so a file the headers show at
     * the project rate is charged one copy, and one whose rate the headers
     * do not give is charged both (decision D29).
     */
    private static long fileCost(Path file, int projectSampleRate) throws IOException {
        ReferenceFileLoader.DecodeEstimate estimate = ReferenceFileLoader.estimateDecode(file, projectSampleRate);
        return saturatedAdd(saturatedAdd(estimate.decodedBytes(), estimate.convertedBytes()), Files.size(file));
    }

    /**
     * {@link SegmentFile#readFrames(List)} decodes every segment straight
     * into one array in fixed-size chunks and converts nothing: about the
     * decoded size.
     */
    private static long takeCost(List<Path> segments) throws IOException {
        long total = 0L;
        for (Path segment : segments) {
            SegmentFile.Description description = SegmentFile.describe(segment);
            total = saturatedAdd(total, description.frameCount() * description.channels() * (long) Float.BYTES);
        }
        return total;
    }

    @Override
    public DecodedAudio decode(AssetReference source, int projectSampleRate) throws IOException {
        return switch (source) {
            case AssetReference.SingleFile file -> decodeFile(Path.of(file.reference()), projectSampleRate);
            case AssetReference.Take take -> decodeTake(take.files());
        };
    }

    private static DecodedAudio decodeFile(Path file, int projectSampleRate) throws IOException {
        boolean wav = SupportedAudioFormat.fromPath(file).filter(SupportedAudioFormat.WAV::equals).isPresent();
        if (wav && AdmBwfImporter.isAdmBwf(file)) {
            return new DecodedAudio.AdmAudio(
                    AdmClipAudio.extract(AdmBwfImporter.parse(file), projectSampleRate), projectSampleRate);
        }
        AudioReadResult read = ReferenceFileLoader.read(file);
        return new DecodedAudio.FileAudio(
                ReferenceFileLoader.convertSampleRate(read.audioData(), read.sampleRate(), projectSampleRate),
                projectSampleRate);
    }

    private static DecodedAudio decodeTake(List<Path> segments) throws IOException {
        SegmentFile.Description head = SegmentFile.describe(segments.getFirst());
        float[][] audio = SegmentFile.readFrames(segments);
        SourceRateMetadata metadata = new SourceRateMetadata(
                (int) Math.round(head.sampleRate()), audio.length, audio[0].length);
        return new DecodedAudio.TakeAudio(audio, metadata);
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }
}

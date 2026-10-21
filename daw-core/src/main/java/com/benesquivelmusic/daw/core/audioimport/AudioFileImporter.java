package com.benesquivelmusic.daw.core.audioimport;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.export.SampleRateConverter;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Imports audio files into a {@link DawProject}.
 *
 * <p>This service handles reading audio files in all supported formats
 * (WAV, FLAC, AIFF, OGG Vorbis, MP3), optionally converting them to the
 * project's sample rate, creating an {@link AudioClip} with the decoded
 * audio data, and placing it on a track at a given position.</p>
 *
 * <p>If the imported file's sample rate differs from the project's sample rate,
 * automatic conversion is performed using high-quality windowed sinc
 * interpolation via {@link SampleRateConverter}.</p>
 *
 * <p>The decode, the rate conversion and the ADM BWF clip extraction are the
 * shared ones — {@link ReferenceFileLoader#read(Path)},
 * {@link ReferenceFileLoader#convertSampleRate(float[][], int, int)} and
 * {@link AdmClipAudio#extract(AdmImportResult, int)} — which the
 * project-open clip rehydrator also uses, so a clip reloaded from a saved
 * project holds the same samples its import produced (Persistence
 * Integrity book §4.3, §5.1).</p>
 */
public final class AudioFileImporter {

    private final DawProject project;

    /**
     * Creates an importer for the given project.
     *
     * @param project the target project for imported audio
     */
    public AudioFileImporter(DawProject project) {
        this.project = Objects.requireNonNull(project, "project must not be null");
    }

    /**
     * Imports an audio file onto a new track at the specified beat position.
     *
     * <p>A new audio track is created with the file name as its name. The
     * audio data is decoded, converted to the project sample rate if necessary,
     * and placed as an {@link AudioClip} at {@code startBeat}.</p>
     *
     * @param file      the audio file to import
     * @param startBeat the beat position where the clip should start
     * @return the import result containing the new track and clip
     * @throws IOException              if the file cannot be read
     * @throws IllegalArgumentException if the file format is unsupported or invalid
     */
    public AudioImportResult importFile(Path file, double startBeat) throws IOException {
        return importFile(file, startBeat, null, ImportProgressListener.NONE);
    }

    /**
     * Imports an audio file onto an existing track at the specified beat position.
     *
     * <p>If {@code targetTrack} is {@code null}, a new audio track is created.</p>
     *
     * @param file        the audio file to import
     * @param startBeat   the beat position where the clip should start
     * @param targetTrack the track to place the clip on, or {@code null} to create a new track
     * @return the import result containing the track and clip
     * @throws IOException              if the file cannot be read
     * @throws IllegalArgumentException if the file format is unsupported or invalid
     */
    public AudioImportResult importFile(Path file, double startBeat, Track targetTrack) throws IOException {
        return importFile(file, startBeat, targetTrack, ImportProgressListener.NONE);
    }

    /**
     * Imports an audio file with progress reporting.
     *
     * @param file        the audio file to import
     * @param startBeat   the beat position where the clip should start
     * @param targetTrack the track to place the clip on, or {@code null} to create a new track
     * @param listener    the progress listener
     * @return the import result containing the track and clip
     * @throws IOException              if the file cannot be read
     * @throws IllegalArgumentException if the file format is unsupported or invalid
     */
    public AudioImportResult importFile(Path file, double startBeat, Track targetTrack,
                                        ImportProgressListener listener) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        if (startBeat < 0) {
            throw new IllegalArgumentException("startBeat must not be negative: " + startBeat);
        }

        SupportedAudioFormat format = SupportedAudioFormat.fromPath(file)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported audio file format: " + file.getFileName()));

        // ADM BWF (.wav with axml chunk) is routed through the spatial importer
        // so that bed channels and audio objects materialize as separate tracks
        // with their own clips and per-object position/size/gain automation.
        if (format == SupportedAudioFormat.WAV && AdmBwfImporter.isAdmBwf(file)) {
            return importAdmBwf(file, startBeat, targetTrack, listener);
        }

        listener.onProgress(file, 0.0);

        // Read the audio file with the shared decode
        AudioReadResult readResult = ReferenceFileLoader.read(file, format);
        listener.onProgress(file, 0.3);

        // Convert to the project's sample rate (the shared helper returns the
        // decoded array itself when the rates already match)
        int fileSampleRate = readResult.sampleRate();
        int projectSampleRate = (int) project.getFormat().sampleRate();
        boolean wasConverted = fileSampleRate != projectSampleRate;
        float[][] audioData = ReferenceFileLoader.convertSampleRate(
                readResult.audioData(), fileSampleRate, projectSampleRate);
        listener.onProgress(file, 0.8);

        // Calculate clip duration in beats
        int numFrames = audioData[0].length;
        double durationSeconds = (double) numFrames / projectSampleRate;
        double tempo = project.getTransport().getTempo();
        double durationBeats = durationSeconds * (tempo / 60.0);

        // Create or use the target track
        Track track;
        if (targetTrack != null) {
            track = targetTrack;
        } else {
            String trackName = deriveTrackName(file);
            track = project.createAudioTrack(trackName);
        }

        // Create the audio clip
        String clipName = deriveClipName(file);
        AudioClip clip = new AudioClip(clipName, startBeat, durationBeats, file.toString());
        clip.setAudioData(audioData);
        track.addClip(clip);

        listener.onProgress(file, 1.0);

        return new AudioImportResult(track, clip, file, wasConverted);
    }

    /**
     * Imports multiple audio files, creating a new track for each file.
     *
     * <p>Each file is placed at the specified start beat on its own new track.
     * This is the typical behavior when dragging multiple files from the file
     * system onto the arrangement view.</p>
     *
     * @param files     the audio files to import
     * @param startBeat the beat position where each clip should start
     * @return the list of import results (one per file)
     * @throws IOException if any file cannot be read
     */
    public List<AudioImportResult> importFiles(List<Path> files, double startBeat) throws IOException {
        return importFiles(files, startBeat, ImportProgressListener.NONE);
    }

    /**
     * Imports multiple audio files with progress reporting.
     *
     * @param files     the audio files to import
     * @param startBeat the beat position where each clip should start
     * @param listener  the progress listener
     * @return the list of import results (one per file)
     * @throws IOException if any file cannot be read
     */
    public List<AudioImportResult> importFiles(List<Path> files, double startBeat,
                                                ImportProgressListener listener) throws IOException {
        Objects.requireNonNull(files, "files must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        if (files.isEmpty()) {
            throw new IllegalArgumentException("files must not be empty");
        }

        List<AudioImportResult> results = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            listener.onFileStarted(file, i, files.size());
            try {
                AudioImportResult result = importFile(file, startBeat, null, listener);
                results.add(result);
                listener.onFileCompleted(file, result);
            } catch (IOException | IllegalArgumentException e) {
                listener.onFileError(file, e);
                throw e;
            }
        }
        return results;
    }

    /**
     * Imports a file at the current playhead position.
     *
     * <p>This is the behavior for File &gt; Import Audio, where the clip
     * is placed at the current transport position.</p>
     *
     * @param file the audio file to import
     * @return the import result
     * @throws IOException if the file cannot be read
     */
    public AudioImportResult importAtPlayhead(Path file) throws IOException {
        double playheadBeat = project.getTransport().getPositionInBeats();
        return importFile(file, playheadBeat);
    }

    /**
     * Imports an ADM BWF (.wav with embedded {@code axml} chunk) and
     * materializes its bed channels and audio objects as project tracks.
     *
     * <p>Behaviour:</p>
     * <ul>
     *   <li>Each ADM {@code audioObject} becomes a new audio track with an
     *       {@link AudioClip} containing that object's audio.</li>
     *   <li>Bed channels collectively become a single multi-channel
     *       audio track named {@code "Bed (<layout>)"}.</li>
     *   <li>The first created track (bed track if present, otherwise the
     *       first object track) is returned via the {@link AudioImportResult}.</li>
     * </ul>
     *
     * <p>For full access to per-object spatial metadata, automation
     * envelopes and unmatched ADM tags, use {@link #importAdmBwfDetailed(Path, double)}
     * which exposes the underlying {@link AdmImportResult}.</p>
     *
     * @param file      the ADM BWF file
     * @param startBeat the beat position where each clip should start
     * @return an import result for the first track produced
     * @throws IOException if the file cannot be read
     */
    public AudioImportResult importAdmBwf(Path file, double startBeat) throws IOException {
        return importAdmBwf(file, startBeat, null, ImportProgressListener.NONE);
    }

    /**
     * Parses an ADM BWF file and returns its rich, deinterleaved spatial
     * import result without modifying the project.
     *
     * <p>Use this method when the caller needs access to the parsed
     * {@link com.benesquivelmusic.daw.core.spatial.objectbased.AudioObject}
     * metadata, per-object timed automation, the bed {@code SpeakerLayout},
     * or the unmatched {@code customMetadata} (e.g. {@code audioProgrammeName}
     * for round-trip preservation).</p>
     *
     * @param file      the ADM BWF file
     * @param startBeat the beat position where clips would be created
     *                  (currently unused — provided for symmetry with
     *                  {@link #importAdmBwf(Path, double)})
     * @return the parsed ADM import result
     * @throws IOException if the file cannot be read
     */
    public AdmImportResult importAdmBwfDetailed(Path file, double startBeat) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        if (startBeat < 0) {
            throw new IllegalArgumentException("startBeat must not be negative: " + startBeat);
        }
        return AdmBwfImporter.parse(file);
    }

    private AudioImportResult importAdmBwf(Path file, double startBeat, Track targetTrack,
                                           ImportProgressListener listener) throws IOException {
        listener.onProgress(file, 0.0);
        AdmImportResult adm = AdmBwfImporter.parse(file);
        listener.onProgress(file, 0.4);

        int projectSampleRate = (int) project.getFormat().sampleRate();
        boolean wasConverted = adm.sampleRate() != projectSampleRate;
        double tempo = project.getTransport().getTempo();

        // The bed clip (all bed channels, one multi-channel clip) and one clip
        // per ADM audioObject, at the project rate and with their import
        // names — the same extraction the project-open rehydrator uses.
        List<AdmClipAudio> admClips = AdmClipAudio.extract(adm, projectSampleRate);
        listener.onProgress(file, 0.6);

        Track firstTrack = null;
        AudioClip firstClip = null;
        for (AdmClipAudio admClip : admClips) {
            float[][] clipData = admClip.audio();
            double clipBeats = framesToBeats(clipData[0].length, projectSampleRate, tempo);
            String clipName = admClip.clipName();
            // The bed may land on the caller's track; every object gets its own.
            Track clipTrack = (admClip.bed() && targetTrack != null)
                    ? targetTrack
                    : project.createAudioTrack(clipName);
            AudioClip clip = new AudioClip(clipName, startBeat, clipBeats, file.toString());
            clip.setAudioData(clipData);
            clipTrack.addClip(clip);
            if (firstTrack == null) {
                firstTrack = clipTrack;
                firstClip = clip;
            }
        }
        listener.onProgress(file, 1.0);

        if (firstTrack == null) {
            throw new IllegalArgumentException(
                    "ADM BWF contains neither bed channels nor audio objects: " + file);
        }
        return new AudioImportResult(firstTrack, firstClip, file, wasConverted);
    }

    private static double framesToBeats(int numFrames, int sampleRate, double tempo) {
        double durationSeconds = (double) numFrames / sampleRate;
        return durationSeconds * (tempo / 60.0);
    }

    /**
     * Derives a track name from the file name (without extension).
     */
    private static String deriveTrackName(Path file) {
        String fileName = file.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            return fileName.substring(0, dotIndex);
        }
        return fileName;
    }

    /**
     * Derives a clip name from the file name (without extension).
     */
    private static String deriveClipName(Path file) {
        return deriveTrackName(file);
    }
}

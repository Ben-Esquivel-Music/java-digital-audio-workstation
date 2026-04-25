package com.benesquivelmusic.daw.core.audioimport;

import com.benesquivelmusic.daw.core.spatial.objectbased.AudioObject;
import com.benesquivelmusic.daw.core.spatial.objectbased.BedChannel;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLayout;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Result of importing an ADM BWF (Audio Definition Model Broadcast Wave Format)
 * file.
 *
 * <p>Carries the deinterleaved bed and object audio buffers, the spatial
 * metadata for each audio object (with per-{@code audioBlockFormat} timed
 * automation samples), the inferred bed speaker layout, and any ADM metadata
 * that does not map to a first-class concept in the DAW (preserved for
 * round-trip export).</p>
 *
 * @param sampleRate       the sample rate of the imported audio in Hz
 * @param bitDepth         the original bit depth of the imported audio
 * @param bedLayout        the speaker layout inferred from the bed channels
 * @param bedChannels      the bed channel assignments (parallel to {@code bedAudio})
 * @param bedAudio         per-bed-channel audio buffers (parallel to {@code bedChannels})
 * @param audioObjects     the parsed audio objects (parallel to {@code objectAudio})
 * @param objectAudio      per-object audio buffers (parallel to {@code audioObjects})
 * @param objectAutomation per-object lists of timed metadata samples,
 *                         keyed by {@link AudioObject#getTrackId()}; objects with no
 *                         automation are absent from the map
 * @param customMetadata   ADM metadata not directly modelled by the DAW
 *                         (e.g. {@code audioProgrammeName}, {@code audioContentName},
 *                         author tags) preserved for later export
 */
public record AdmImportResult(
        int sampleRate,
        int bitDepth,
        SpeakerLayout bedLayout,
        List<BedChannel> bedChannels,
        List<float[]> bedAudio,
        List<AudioObject> audioObjects,
        List<float[]> objectAudio,
        Map<String, List<AdmAutomationPoint>> objectAutomation,
        Map<String, String> customMetadata) {

    public AdmImportResult {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (bitDepth <= 0) {
            throw new IllegalArgumentException("bitDepth must be positive: " + bitDepth);
        }
        Objects.requireNonNull(bedLayout, "bedLayout must not be null");
        Objects.requireNonNull(bedChannels, "bedChannels must not be null");
        Objects.requireNonNull(bedAudio, "bedAudio must not be null");
        Objects.requireNonNull(audioObjects, "audioObjects must not be null");
        Objects.requireNonNull(objectAudio, "objectAudio must not be null");
        Objects.requireNonNull(objectAutomation, "objectAutomation must not be null");
        Objects.requireNonNull(customMetadata, "customMetadata must not be null");
        if (bedChannels.size() != bedAudio.size()) {
            throw new IllegalArgumentException(
                    "bedChannels and bedAudio must have the same size: "
                            + bedChannels.size() + " vs " + bedAudio.size());
        }
        if (audioObjects.size() != objectAudio.size()) {
            throw new IllegalArgumentException(
                    "audioObjects and objectAudio must have the same size: "
                            + audioObjects.size() + " vs " + objectAudio.size());
        }
        bedChannels = List.copyOf(bedChannels);
        bedAudio = List.copyOf(bedAudio);
        audioObjects = List.copyOf(audioObjects);
        objectAudio = List.copyOf(objectAudio);
        objectAutomation = Collections.unmodifiableMap(new LinkedHashMap<>(objectAutomation));
        customMetadata = Collections.unmodifiableMap(new LinkedHashMap<>(customMetadata));
    }

    /** Returns the total number of automation points across all objects. */
    public int totalAutomationPointCount() {
        int count = 0;
        for (List<AdmAutomationPoint> points : objectAutomation.values()) {
            count += points.size();
        }
        return count;
    }
}

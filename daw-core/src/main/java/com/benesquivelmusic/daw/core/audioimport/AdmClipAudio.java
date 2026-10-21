package com.benesquivelmusic.daw.core.audioimport;

import com.benesquivelmusic.daw.sdk.spatial.SpeakerLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The audio of one clip an ADM BWF import creates, at the project rate,
 * with the name the import gives that clip: the bed clip (every bed channel,
 * named {@link #bedClipName(SpeakerLayout)}) or one object clip (the
 * object's channel, named {@link #objectClipName(int)}).
 *
 * <p>{@link #extract(AdmImportResult, int)} is the one place that turns a
 * parsed ADM file into clip audio. {@link AudioFileImporter} builds its
 * tracks and clips from it, and the project-open clip rehydrator matches a
 * reloaded clip to its audio by the same names — nothing persisted records
 * which part of the file a clip plays (Persistence Integrity book §4.3).</p>
 *
 * @param clipName the name the import gives the clip
 * @param bed      {@code true} for the bed clip, {@code false} for an object clip
 * @param audio    the clip's audio as {@code [channel][sample]} at the project rate
 */
public record AdmClipAudio(String clipName, boolean bed, float[][] audio) {

    private static final String BED_NAME_PREFIX = "Bed (";
    private static final String BED_NAME_SUFFIX = ")";
    private static final String OBJECT_NAME_PREFIX = "Object ";

    public AdmClipAudio {
        Objects.requireNonNull(clipName, "clipName must not be null");
        Objects.requireNonNull(audio, "audio must not be null");
        if (audio.length == 0) {
            throw new IllegalArgumentException("audio must have at least one channel");
        }
    }

    /**
     * Returns the clip audio of a parsed ADM file at {@code projectSampleRate}:
     * the bed clip first (when the file has bed channels), then one clip per
     * audio object in file order. Conversion uses
     * {@link ReferenceFileLoader#convertSampleRate(float[][], int, int)}.
     *
     * @param adm               the parsed file
     * @param projectSampleRate the project rate in Hz
     * @return the clips, in the order the import creates their tracks
     */
    public static List<AdmClipAudio> extract(AdmImportResult adm, int projectSampleRate) {
        Objects.requireNonNull(adm, "adm must not be null");
        List<AdmClipAudio> clips = new ArrayList<>(adm.objectAudio().size() + 1);
        if (!adm.bedAudio().isEmpty()) {
            float[][] bedData = adm.bedAudio().toArray(new float[0][]);
            clips.add(new AdmClipAudio(bedClipName(adm.bedLayout()), true,
                    ReferenceFileLoader.convertSampleRate(bedData, adm.sampleRate(), projectSampleRate)));
        }
        for (int i = 0; i < adm.objectAudio().size(); i++) {
            float[][] objData = new float[][]{adm.objectAudio().get(i)};
            clips.add(new AdmClipAudio(objectClipName(i + 1), false,
                    ReferenceFileLoader.convertSampleRate(objData, adm.sampleRate(), projectSampleRate)));
        }
        return List.copyOf(clips);
    }

    /**
     * The name an import gives the bed track and clip of a file whose bed
     * channels form {@code layout}: {@code "Bed (<layout name>)"}.
     *
     * @param layout the parsed bed layout
     * @return the bed clip name
     */
    public static String bedClipName(SpeakerLayout layout) {
        Objects.requireNonNull(layout, "layout must not be null");
        return BED_NAME_PREFIX + layout.name() + BED_NAME_SUFFIX;
    }

    /**
     * Whether {@code name} has the form an import gives a bed or object
     * clip, whatever the file: {@code "Bed (<layout name>)"} with a layout
     * name ({@link #bedClipName}), or {@code "Object <n>"} with {@code n} a
     * positive whole number as {@link #objectClipName} writes it. Only the
     * form is judged — a plain file a user named {@code Object 1.wav} has it
     * too.
     *
     * @param name a clip name
     * @return {@code true} if the name has an ADM import form
     */
    public static boolean hasImportNameForm(String name) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.startsWith(BED_NAME_PREFIX)) {
            return name.endsWith(BED_NAME_SUFFIX)
                    && name.length() > BED_NAME_PREFIX.length() + BED_NAME_SUFFIX.length();
        }
        if (!name.startsWith(OBJECT_NAME_PREFIX)) {
            return false;
        }
        String number = name.substring(OBJECT_NAME_PREFIX.length());
        if (number.isEmpty() || number.charAt(0) == '0') {
            return false;
        }
        for (int i = 0; i < number.length(); i++) {
            if (number.charAt(i) < '0' || number.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * The name an import gives the track and clip of the file's
     * {@code number}-th audio object (1-based): {@code "Object <number>"}.
     *
     * @param number the object's 1-based position in the file
     * @return the object clip name
     */
    public static String objectClipName(int number) {
        if (number < 1) {
            throw new IllegalArgumentException("number must be at least 1: " + number);
        }
        return OBJECT_NAME_PREFIX + number;
    }
}

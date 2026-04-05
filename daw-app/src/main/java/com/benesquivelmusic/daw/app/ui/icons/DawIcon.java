package com.benesquivelmusic.daw.app.ui.icons;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Every icon available in the DAW icon pack.
 *
 * <p>Each constant maps to an SVG file stored under
 * {@code icons/<category>/<fileName>.svg} in the application resources.
 * Use {@link IconNode#of(DawIcon)} to obtain a ready-to-use JavaFX
 * {@link javafx.scene.Node}.</p>
 */
public enum DawIcon {

    // --- Connectivity ---
    AIRPLAY(IconCategory.CONNECTIVITY, "airplay"),
    ANTENNA(IconCategory.CONNECTIVITY, "antenna"),
    AUX_CABLE(IconCategory.CONNECTIVITY, "aux-cable"),
    BLUETOOTH(IconCategory.CONNECTIVITY, "bluetooth"),
    CAST(IconCategory.CONNECTIVITY, "cast"),
    CLOUD(IconCategory.CONNECTIVITY, "cloud"),
    ETHERNET(IconCategory.CONNECTIVITY, "ethernet"),
    HDMI(IconCategory.CONNECTIVITY, "hdmi"),
    LINK(IconCategory.CONNECTIVITY, "link"),
    MIDI_CABLE(IconCategory.CONNECTIVITY, "midi-cable"),
    NFC(IconCategory.CONNECTIVITY, "nfc"),
    OPTICAL(IconCategory.CONNECTIVITY, "optical"),
    SPDIF(IconCategory.CONNECTIVITY, "spdif"),
    SYNC(IconCategory.CONNECTIVITY, "sync"),
    THUNDERBOLT(IconCategory.CONNECTIVITY, "thunderbolt"),
    USB(IconCategory.CONNECTIVITY, "usb"),
    WIFI(IconCategory.CONNECTIVITY, "wifi"),
    XLR(IconCategory.CONNECTIVITY, "xlr"),

    // --- DAW ---
    AUTOMATION(IconCategory.DAW, "automation"),
    CHORUS(IconCategory.DAW, "chorus"),
    COMPRESSOR(IconCategory.DAW, "compressor"),
    DELAY(IconCategory.DAW, "delay"),
    DISTORTION(IconCategory.DAW, "distortion"),
    EQ(IconCategory.DAW, "eq"),
    FLANGER(IconCategory.DAW, "flanger"),
    GAIN(IconCategory.DAW, "gain"),
    HIGH_PASS(IconCategory.DAW, "high-pass"),
    KNOB(IconCategory.DAW, "knob"),
    LIMITER(IconCategory.DAW, "limiter"),
    LOOP(IconCategory.DAW, "loop"),
    LOW_PASS(IconCategory.DAW, "low-pass"),
    MARKER(IconCategory.DAW, "marker"),
    METRONOME(IconCategory.DAW, "metronome"),
    MIDI(IconCategory.DAW, "midi"),
    MIXER(IconCategory.DAW, "mixer"),
    NOISE_GATE(IconCategory.DAW, "noise-gate"),
    PAN(IconCategory.DAW, "pan"),
    PHASER(IconCategory.DAW, "phaser"),
    PITCH_SHIFT(IconCategory.DAW, "pitch-shift"),
    REVERB(IconCategory.DAW, "reverb"),
    SHUFFLE(IconCategory.DAW, "shuffle"),
    TIMELINE(IconCategory.DAW, "timeline"),
    WAVEFORM(IconCategory.DAW, "waveform"),

    // --- Editing ---
    ALIGN_CENTER(IconCategory.EDITING, "align-center"),
    ALIGN_LEFT(IconCategory.EDITING, "align-left"),
    ALIGN_RIGHT(IconCategory.EDITING, "align-right"),
    COPY(IconCategory.EDITING, "copy"),
    CROP(IconCategory.EDITING, "crop"),
    CROSSFADE(IconCategory.EDITING, "crossfade"),
    CUT(IconCategory.EDITING, "cut"),
    DELETE(IconCategory.EDITING, "delete"),
    FADE_IN(IconCategory.EDITING, "fade-in"),
    FADE_OUT(IconCategory.EDITING, "fade-out"),
    MOVE(IconCategory.EDITING, "move"),
    NORMALIZE(IconCategory.EDITING, "normalize"),
    PASTE(IconCategory.EDITING, "paste"),
    REDO(IconCategory.EDITING, "redo"),
    REVERSE(IconCategory.EDITING, "reverse"),
    SELECT_ALL(IconCategory.EDITING, "select-all"),
    SNAP(IconCategory.EDITING, "snap"),
    SPLIT(IconCategory.EDITING, "split"),
    TRIM(IconCategory.EDITING, "trim"),
    UNDO(IconCategory.EDITING, "undo"),
    ZOOM_IN(IconCategory.EDITING, "zoom-in"),
    ZOOM_OUT(IconCategory.EDITING, "zoom-out"),

    // --- File Types ---
    AAC(IconCategory.FILE_TYPES, "aac"),
    AIFF(IconCategory.FILE_TYPES, "aiff"),
    FLAC(IconCategory.FILE_TYPES, "flac"),
    MIDI_FILE(IconCategory.FILE_TYPES, "midi-file"),
    MP3(IconCategory.FILE_TYPES, "mp3"),
    OGG(IconCategory.FILE_TYPES, "ogg"),
    WAV(IconCategory.FILE_TYPES, "wav"),
    WMA(IconCategory.FILE_TYPES, "wma"),

    // --- General ---
    ALBUM(IconCategory.GENERAL, "album"),
    BELL(IconCategory.GENERAL, "bell"),
    BOOKMARK(IconCategory.GENERAL, "bookmark"),
    CASSETTE(IconCategory.GENERAL, "cassette"),
    CLOCK(IconCategory.GENERAL, "clock"),
    DOWNLOAD(IconCategory.GENERAL, "download"),
    FAVORITE(IconCategory.GENERAL, "favorite"),
    FILM(IconCategory.GENERAL, "film"),
    FOLDER(IconCategory.GENERAL, "folder"),
    HISTORY(IconCategory.GENERAL, "history"),
    INFO(IconCategory.GENERAL, "info"),
    LIBRARY(IconCategory.GENERAL, "library"),
    NOTIFICATION(IconCategory.GENERAL, "notification"),
    PLAYLIST(IconCategory.GENERAL, "playlist"),
    PODCAST(IconCategory.GENERAL, "podcast"),
    QUEUE_MUSIC(IconCategory.GENERAL, "queue-music"),
    SEARCH(IconCategory.GENERAL, "search"),
    SETTINGS(IconCategory.GENERAL, "settings"),
    SHARE(IconCategory.GENERAL, "share"),
    TAG(IconCategory.GENERAL, "tag"),
    TIMER(IconCategory.GENERAL, "timer"),
    UPLOAD(IconCategory.GENERAL, "upload"),
    VINYL(IconCategory.GENERAL, "vinyl"),

    // --- Instruments ---
    ACCORDION(IconCategory.INSTRUMENTS, "accordion"),
    ACOUSTIC_GUITAR(IconCategory.INSTRUMENTS, "acoustic-guitar"),
    BANJO(IconCategory.INSTRUMENTS, "banjo"),
    BASS_GUITAR(IconCategory.INSTRUMENTS, "bass-guitar"),
    BONGOS(IconCategory.INSTRUMENTS, "bongos"),
    CELLO(IconCategory.INSTRUMENTS, "cello"),
    CLARINET(IconCategory.INSTRUMENTS, "clarinet"),
    DJEMBE(IconCategory.INSTRUMENTS, "djembe"),
    DRUMS(IconCategory.INSTRUMENTS, "drums"),
    ELECTRIC_GUITAR(IconCategory.INSTRUMENTS, "electric-guitar"),
    FLUTE(IconCategory.INSTRUMENTS, "flute"),
    HARMONICA(IconCategory.INSTRUMENTS, "harmonica"),
    HARP(IconCategory.INSTRUMENTS, "harp"),
    KEYBOARD(IconCategory.INSTRUMENTS, "keyboard"),
    MANDOLIN(IconCategory.INSTRUMENTS, "mandolin"),
    MARACAS(IconCategory.INSTRUMENTS, "maracas"),
    SAXOPHONE(IconCategory.INSTRUMENTS, "saxophone"),
    TAMBOURINE(IconCategory.INSTRUMENTS, "tambourine"),
    TROMBONE(IconCategory.INSTRUMENTS, "trombone"),
    TRUMPET(IconCategory.INSTRUMENTS, "trumpet"),
    TUBA(IconCategory.INSTRUMENTS, "tuba"),
    UKULELE(IconCategory.INSTRUMENTS, "ukulele"),
    VIOLIN(IconCategory.INSTRUMENTS, "violin"),
    XYLOPHONE(IconCategory.INSTRUMENTS, "xylophone"),

    // --- Media ---
    AMPLIFIER(IconCategory.MEDIA, "amplifier"),
    BOOMBOX(IconCategory.MEDIA, "boombox"),
    CAMERA(IconCategory.MEDIA, "camera"),
    CD(IconCategory.MEDIA, "cd"),
    DRUM(IconCategory.MEDIA, "drum"),
    EQUALIZER(IconCategory.MEDIA, "equalizer"),
    FILM_STRIP(IconCategory.MEDIA, "film-strip"),
    GUITAR(IconCategory.MEDIA, "guitar"),
    HEADPHONES(IconCategory.MEDIA, "headphones"),
    MICROPHONE(IconCategory.MEDIA, "microphone"),
    MONITOR(IconCategory.MEDIA, "monitor"),
    MP3_PLAYER(IconCategory.MEDIA, "mp3-player"),
    MUSIC_NOTE(IconCategory.MEDIA, "music-note"),
    PIANO(IconCategory.MEDIA, "piano"),
    RADIO(IconCategory.MEDIA, "radio"),
    RECORD_PLAYER(IconCategory.MEDIA, "record-player"),
    SPEAKER_WIRELESS(IconCategory.MEDIA, "speaker-wireless"),
    TURNTABLE(IconCategory.MEDIA, "turntable"),

    // --- Metering ---
    CORRELATION(IconCategory.METERING, "correlation"),
    LOUDNESS_METER(IconCategory.METERING, "loudness-meter"),
    OSCILLOSCOPE(IconCategory.METERING, "oscilloscope"),
    PEAK(IconCategory.METERING, "peak"),
    PHASE_METER(IconCategory.METERING, "phase-meter"),
    RMS(IconCategory.METERING, "rms"),
    SPECTRUM(IconCategory.METERING, "spectrum"),
    VU_METER(IconCategory.METERING, "vu-meter"),

    // --- Navigation ---
    BACK(IconCategory.NAVIGATION, "back"),
    CLOSE(IconCategory.NAVIGATION, "close"),
    COLLAPSE(IconCategory.NAVIGATION, "collapse"),
    EXPAND(IconCategory.NAVIGATION, "expand"),
    FORWARD(IconCategory.NAVIGATION, "forward"),
    FULLSCREEN(IconCategory.NAVIGATION, "fullscreen"),
    HOME(IconCategory.NAVIGATION, "home"),
    MENU(IconCategory.NAVIGATION, "menu"),
    MINIMIZE(IconCategory.NAVIGATION, "minimize"),
    PIP(IconCategory.NAVIGATION, "pip"),

    // --- Notifications ---
    ALERT(IconCategory.NOTIFICATIONS, "alert"),
    BADGE(IconCategory.NOTIFICATIONS, "badge"),
    BELL_RING(IconCategory.NOTIFICATIONS, "bell-ring"),
    ERROR(IconCategory.NOTIFICATIONS, "error"),
    INFO_CIRCLE(IconCategory.NOTIFICATIONS, "info-circle"),
    STATUS(IconCategory.NOTIFICATIONS, "status"),
    SUCCESS(IconCategory.NOTIFICATIONS, "success"),
    WARNING(IconCategory.NOTIFICATIONS, "warning"),

    // --- Playback ---
    EJECT(IconCategory.PLAYBACK, "eject"),
    FAST_FORWARD(IconCategory.PLAYBACK, "fast-forward"),
    PAUSE_CIRCLE(IconCategory.PLAYBACK, "pause-circle"),
    PAUSE(IconCategory.PLAYBACK, "pause"),
    PLAY_CIRCLE(IconCategory.PLAYBACK, "play-circle"),
    PLAY(IconCategory.PLAYBACK, "play"),
    POWER(IconCategory.PLAYBACK, "power"),
    QUEUE_NEXT(IconCategory.PLAYBACK, "queue-next"),
    RECORD(IconCategory.PLAYBACK, "record"),
    REPEAT_ONE(IconCategory.PLAYBACK, "repeat-one"),
    REPEAT(IconCategory.PLAYBACK, "repeat"),
    REWIND(IconCategory.PLAYBACK, "rewind"),
    SKIP_BACK(IconCategory.PLAYBACK, "skip-back"),
    SKIP_FORWARD(IconCategory.PLAYBACK, "skip-forward"),
    SLOW_MOTION(IconCategory.PLAYBACK, "slow-motion"),
    SPEED_UP(IconCategory.PLAYBACK, "speed-up"),
    STOP(IconCategory.PLAYBACK, "stop"),

    // --- Recording ---
    ARM_TRACK(IconCategory.RECORDING, "arm-track"),
    INPUT(IconCategory.RECORDING, "input"),
    MUTE(IconCategory.RECORDING, "mute"),
    OUTPUT(IconCategory.RECORDING, "output"),
    PAD(IconCategory.RECORDING, "pad"),
    PHANTOM_POWER(IconCategory.RECORDING, "phantom-power"),
    PHASE(IconCategory.RECORDING, "phase"),
    SOLO(IconCategory.RECORDING, "solo"),

    // --- Social ---
    BROADCAST(IconCategory.SOCIAL, "broadcast"),
    COMMENT(IconCategory.SOCIAL, "comment"),
    DISLIKE(IconCategory.SOCIAL, "dislike"),
    FOLLOW(IconCategory.SOCIAL, "follow"),
    LIKE(IconCategory.SOCIAL, "like"),
    LIVE(IconCategory.SOCIAL, "live"),
    RATE(IconCategory.SOCIAL, "rate"),
    STREAM(IconCategory.SOCIAL, "stream"),

    // --- Volume ---
    AUDIO_BALANCE(IconCategory.VOLUME, "audio-balance"),
    BASS_BOOST(IconCategory.VOLUME, "bass-boost"),
    LOUDNESS(IconCategory.VOLUME, "loudness"),
    MONO(IconCategory.VOLUME, "mono"),
    SPEAKER(IconCategory.VOLUME, "speaker"),
    STEREO(IconCategory.VOLUME, "stereo"),
    SURROUND(IconCategory.VOLUME, "surround"),
    TREBLE_BOOST(IconCategory.VOLUME, "treble-boost"),
    VOLUME_DOWN(IconCategory.VOLUME, "volume-down"),
    VOLUME_MUTE(IconCategory.VOLUME, "volume-mute"),
    VOLUME_OFF(IconCategory.VOLUME, "volume-off"),
    VOLUME_SLIDER(IconCategory.VOLUME, "volume-slider"),
    VOLUME_UP(IconCategory.VOLUME, "volume-up");

    private final IconCategory category;
    private final String fileName;

    DawIcon(IconCategory category, String fileName) {
        this.category = category;
        this.fileName = fileName;
    }

    /**
     * Returns the category this icon belongs to.
     */
    public IconCategory category() {
        return category;
    }

    /**
     * Returns the base file name (without extension) of the SVG resource.
     */
    public String fileName() {
        return fileName;
    }

    /**
     * Returns the absolute classpath resource path for this icon's SVG file.
     */
    public String resourcePath() {
        return "/com/benesquivelmusic/daw/app/icons/"
                + category.directoryName() + "/" + fileName + ".svg";
    }

    private static final Map<String, DawIcon> BY_FILE_NAME =
            Stream.of(values()).collect(Collectors.toUnmodifiableMap(DawIcon::fileName, icon -> icon));

    /**
     * Looks up a {@code DawIcon} by its file name.
     *
     * @param fileName the base file name (without extension), e.g. "keyboard", "eq"
     * @return an {@link Optional} containing the matching icon, or empty if no match
     */
    public static Optional<DawIcon> fromFileName(String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_FILE_NAME.get(fileName));
    }
}

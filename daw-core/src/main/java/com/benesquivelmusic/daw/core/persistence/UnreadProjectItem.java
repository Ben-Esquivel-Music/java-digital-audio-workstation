package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.midi.MidiCcLaneType;

import java.util.Locale;
import java.util.Objects;

/**
 * One saved item a project open could not honour as written (Persistence
 * Integrity book §2.4, §9.4): a MIDI note, CC lane or CC event the reader
 * skipped, an arpeggiator setting it adjusted, a MIDI effect it kept as a
 * pass-through placeholder, or a cue send, VCA membership, channel link or
 * bed routing it could not honour — naming a track the file does not
 * contain, a malformed id, a channel linked to itself, or a repeat of an
 * earlier assignment — which it removed; its container was kept.
 *
 * <p>{@link ProjectDeserializer} logs each item once at WARNING
 * ({@link #logLine()}) and collects it ({@link ProjectDeserializer#getUnreadItems()});
 * {@link ProjectManager#getLastUnreadItems()} keeps the open's list so the
 * application can qualify the open announcement. Problems with transport
 * preferences (pre/post-roll), with track ids and with VCA groups (an
 * unreadable gain, a repeated id) are logged only: they are not items.</p>
 *
 * @param kind     what was not honoured, and how
 * @param location where the item was saved: a track for every kind except
 *                 {@link Kind#MIXER_REFERENCE_REMOVED}, a mixer container
 *                 for that kind
 * @param index    the item's zero-based position in its container in
 *                 document order — the note within the clip's notes, the lane
 *                 within its lanes, the event within its lane, the effect
 *                 within the track's MIDI-effect chain, the assignment within
 *                 its cue bus, VCA group, channel links or bed routings (the
 *                 log line numbers it from 1)
 * @param detail   why the item was not honoured — the parse or model
 *                 message — and, for a note, a lane or an event, what was
 *                 saved: {@code <reason>[; saved as <snippet>][, at transport
 *                 beat <b>]}, the snippet being the element with the
 *                 attributes the writer emits, in the writer's order, each
 *                 value as saved, and the beat the saved column over four for
 *                 a note or an event whose column is an integer &ge; 0
 * @param count    how many saved items this entry stands for: {@code 1},
 *                 except for a {@link Kind#CC_LANE_SKIPPED skipped lane},
 *                 which stands for its saved events — {@code 0} for a lane
 *                 saved with none (the open announcement still counts such
 *                 a lane as one skipped CC event)
 * @param lane     for a {@link Kind#CC_EVENT_SKIPPED skipped CC event}, the
 *                 lane it was saved in; {@code null} for every other kind
 */
public record UnreadProjectItem(Kind kind, Location location, int index, String detail, int count,
                                EventLane lane) {

    /** The most characters of one file value a log line quotes before it cuts the value off. */
    static final int LOG_VALUE_LIMIT = 64;

    /** What the open did with a saved item it could not honour as written. */
    public enum Kind {
        /** A MIDI note that could not be read was skipped. */
        MIDI_NOTE_SKIPPED,
        /** A CC lane that could not be read was skipped with all its events. */
        CC_LANE_SKIPPED,
        /** A CC event (lane breakpoint) that could not be read was skipped. */
        CC_EVENT_SKIPPED,
        /** An invalid arpeggiator setting was replaced by a valid value. */
        MIDI_EFFECT_SETTING_ADJUSTED,
        /**
         * A MIDI effect this build does not recognise was kept, as saved, as a
         * pass-through placeholder ({@link UnresolvedMidiEffect}).
         */
        MIDI_EFFECT_NOT_RECOGNIZED,
        /**
         * A cue send, VCA membership, channel link or bed routing the open
         * could not honour — naming a track the file does not contain, a
         * malformed id, a channel linked to itself, or a repeat of an earlier
         * assignment — was removed; its container was kept.
         */
        MIXER_REFERENCE_REMOVED;

        /** Whether items of this kind are located on a track. */
        public boolean isTrackScoped() {
            return this != MIXER_REFERENCE_REMOVED;
        }

        /**
         * Whether the next save of the opened project drops what the file
         * saved for an item of this kind: a skipped note, lane or event and a
         * removed mixer assignment are not written back, and an adjusted
         * setting is written back with the value the open chose, not the raw
         * one. A MIDI effect kept as a placeholder is written back as it was
         * saved — except for any child elements or text of its entry, which
         * {@link ProjectDeserializer#losesSavedContent()} counts separately.
         *
         * @return {@code true} when saving loses the item as saved
         */
        public boolean losesSavedContent() {
            return switch (this) {
                case MIDI_NOTE_SKIPPED, CC_LANE_SKIPPED, CC_EVENT_SKIPPED, MIDI_EFFECT_SETTING_ADJUSTED,
                     MIXER_REFERENCE_REMOVED -> true;
                case MIDI_EFFECT_NOT_RECOGNIZED -> false;
            };
        }
    }

    /**
     * Where an unread item was saved; the reader's analyzer-insert WARNINGs
     * name the mixer strip of an insert with it too.
     */
    public sealed interface Location permits OnTrack, InMixer {

        /** A short description for the log, e.g. {@code Track 2 'Keys'}. */
        String describe();
    }

    /**
     * An item saved on a track.
     *
     * @param trackIndex the track's zero-based position in the file's track
     *                   list (the log numbers it from 1, as the application
     *                   numbers tracks)
     * @param trackName  the track's name as restored
     */
    public record OnTrack(int trackIndex, String trackName) implements Location {
        public OnTrack {
            if (trackIndex < 0) {
                throw new IllegalArgumentException("trackIndex must be >= 0: " + trackIndex);
            }
            Objects.requireNonNull(trackName, "trackName must not be null");
        }

        /** {@code Track <trackIndex + 1> '<name>'}, the name quoted by {@link #logText}. */
        @Override
        public String describe() {
            return "Track " + (trackIndex + 1) + " '" + logText(trackName) + "'";
        }
    }

    /**
     * An item saved in a mixer container: a cue bus, a VCA group, the channel
     * links or the bed bus. The reader's analyzer-insert WARNINGs also name a
     * strip with no track this way: the master, a return bus, or a channel
     * no track owns.
     *
     * @param container the container as the log names it, e.g.
     *                  {@code cue bus 'Singer'} (a name taken from the file
     *                  already quoted by {@link #logText})
     */
    public record InMixer(String container) implements Location {
        public InMixer {
            Objects.requireNonNull(container, "container must not be null");
            if (container.isBlank()) {
                throw new IllegalArgumentException("container must not be blank");
            }
        }

        @Override
        public String describe() {
            return "Mixer " + container;
        }
    }

    /**
     * The CC lane a {@linkplain Kind#CC_EVENT_SKIPPED skipped CC event} was
     * saved in, as the open read it.
     *
     * @param laneIndex the lane's zero-based position within its clip's CC
     *                  lanes (the log numbers it from 1)
     * @param type      the lane's type
     * @param ccNumber  the lane's controller number, or {@code -1} for a type
     *                  that has none ({@link MidiCcLaneType#VELOCITY},
     *                  {@link MidiCcLaneType#PITCH_BEND})
     */
    public record EventLane(int laneIndex, MidiCcLaneType type, int ccNumber) {
        public EventLane {
            if (laneIndex < 0) {
                throw new IllegalArgumentException("laneIndex must be >= 0: " + laneIndex);
            }
            Objects.requireNonNull(type, "type must not be null");
        }

        /** {@code CC lane <laneIndex + 1> (<TYPE>[, CC <ccNumber>])}, e.g. {@code CC lane 1 (MOD_WHEEL, CC 1)}. */
        String describe() {
            return "CC lane " + (laneIndex + 1) + " (" + type + (ccNumber >= 0 ? ", CC " + ccNumber : "") + ")";
        }
    }

    public UnreadProjectItem {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(location, "location must not be null");
        Objects.requireNonNull(detail, "detail must not be null");
        if (kind.isTrackScoped() != (location instanceof OnTrack)) {
            throw new IllegalArgumentException(kind + " cannot be located at " + location);
        }
        if (index < 0) {
            throw new IllegalArgumentException("index must be >= 0: " + index);
        }
        if (kind == Kind.CC_LANE_SKIPPED ? count < 0 : count != 1) {
            throw new IllegalArgumentException(kind + " cannot stand for " + count + " saved items");
        }
        if ((lane != null) != (kind == Kind.CC_EVENT_SKIPPED)) {
            throw new IllegalArgumentException(kind + (lane == null
                    ? " needs the lane the event was saved in" : " is not saved in a CC lane"));
        }
    }

    /**
     * An item of any kind but {@link Kind#CC_EVENT_SKIPPED}, which is saved
     * in no CC lane.
     */
    public UnreadProjectItem(Kind kind, Location location, int index, String detail, int count) {
        this(kind, location, index, detail, count, null);
    }

    /**
     * The WARNING line the open logs for this item:
     * {@code <where>: <what> — <detail>}; the track, item and lane positions
     * are counted from 1; values in the detail are as saved. For example
     * {@code Track 2 'Keys': MIDI note 5 skipped — velocity must be 0–127: 128;
     * saved as <note note-number="60" start-column="17" duration-columns="4"
     * velocity="128" channel="0"/>, at transport beat 4.25}. A skipped lane
     * saved with no events reads {@code CC lane <i> skipped with no saved
     * events}.
     *
     * @return the log line
     */
    public String logLine() {
        String what = switch (kind) {
            case MIDI_NOTE_SKIPPED -> "MIDI note " + (index + 1) + " skipped";
            case CC_LANE_SKIPPED -> "CC lane " + (index + 1) + (count == 0 ? " skipped with no saved events"
                    : " skipped with its " + count + (count == 1 ? " saved event" : " saved events"));
            case CC_EVENT_SKIPPED -> "CC event " + (index + 1) + " of " + lane.describe() + " skipped";
            case MIDI_EFFECT_SETTING_ADJUSTED -> "MIDI effect " + (index + 1) + " setting adjusted";
            case MIDI_EFFECT_NOT_RECOGNIZED -> "MIDI effect " + (index + 1) + " not recognized (kept as saved)";
            case MIXER_REFERENCE_REMOVED -> "mixer assignment " + (index + 1) + " removed";
        };
        return location.describe() + ": " + what + " — " + detail;
    }

    /**
     * Renders a string taken from a project file for a log line: the value
     * is cut after {@value #LOG_VALUE_LIMIT} characters, with {@code …}
     * appended; then {@code &}, {@code <} and {@code "} are escaped as XML
     * escapes them, and each control character (U+0000–U+001F, U+007F) is
     * written as {@code &#xN;}.
     *
     * <p>It governs the WARNINGs {@link ProjectDeserializer} and
     * {@link AnalyzerInsertPersistence} write while a project file is read:
     * each of them passes the file text it embeds through this method —
     * snippet values, raw values inside reasons, track, clip, cue-bus,
     * VCA-group and return-bus names, clip references and analyzer
     * settings — so a file can neither split one of those lines nor forge
     * one. A log line written by any other class is not covered by this
     * rule.</p>
     *
     * @param value the text as saved
     * @return the text as a log line shows it
     */
    static String logText(String value) {
        String cut = value;
        boolean truncated = value.codePointCount(0, value.length()) > LOG_VALUE_LIMIT;
        if (truncated) {
            cut = value.substring(0, value.offsetByCodePoints(0, LOG_VALUE_LIMIT));
        }
        StringBuilder text = new StringBuilder(cut.length() + 8);
        for (int i = 0; i < cut.length(); i++) {
            char c = cut.charAt(i);
            switch (c) {
                case '&' -> text.append("&amp;");
                case '<' -> text.append("&lt;");
                case '"' -> text.append("&quot;");
                default -> {
                    if (c < 0x20 || c == 0x7F) {
                        text.append("&#x").append(Integer.toHexString(c).toUpperCase(Locale.ROOT))
                                .append(';');
                    } else {
                        text.append(c);
                    }
                }
            }
        }
        if (truncated) {
            text.append('…');
        }
        return text.toString();
    }
}

package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.midi.MidiCcLaneType;

import java.util.Locale;
import java.util.Objects;

/**
 * One saved item a project open could not honour as written (Persistence
 * Integrity book §2.4, §9.4): a MIDI note, CC lane or CC event the reader
 * skipped, an arpeggiator setting it adjusted, a MIDI effect it kept as a
 * pass-through placeholder, a cue send, VCA membership, channel link or
 * bed routing it could not honour — naming a track the file does not
 * contain, a malformed id, a channel linked to itself, or a repeat of an
 * earlier assignment — which it removed (its container was kept); and,
 * story 334, a tempo or meter change of the transport's tempo map or an
 * unusable beat-0 tempo or meter it skipped, a comp region it skipped, an
 * insert it could not load (kept at its place as saved), a ninth or later
 * insert of a strip it skipped, and an insert parameter it adjusted.
 *
 * <p>{@link ProjectDeserializer} logs each item once at WARNING
 * ({@link #logLine()}) and collects it ({@link ProjectDeserializer#getUnreadItems()});
 * {@link ProjectManager#getLastUnreadItems()} keeps the open's list so the
 * application can qualify the open announcement. Problems with transport
 * preferences (pre/post-roll), with track ids and with VCA groups (an
 * unreadable gain, a repeated id) are logged only: they are not items.</p>
 *
 * @param kind       what was not honoured, and how
 * @param location   where the item was saved, as its kind's
 *                   {@linkplain Kind#scope() scope} admits: a track for the
 *                   MIDI kinds and a comp region; a track or a mixer
 *                   container for a removed mixer assignment and for the
 *                   insert kinds — the strip holding the insert, the track
 *                   for a track's own channel; the transport for a tempo or
 *                   meter entry
 * @param index      the item's zero-based position in its container in
 *                   document order — the note within the clip's notes, the
 *                   lane within its lanes, the event within its lane, the
 *                   effect within the track's MIDI-effect chain, the
 *                   assignment within its cue bus, VCA group, channel links
 *                   or bed routings, the entry within its tempo or meter
 *                   list ({@code 0} for a beat-0 attribute), the region
 *                   within the track's comp regions, the insert among the
 *                   strip's saved inserts (the log line numbers it from 1)
 * @param detail     why the item was not honoured — the parse or model
 *                   message — and, for a note, a lane, an event, a tempo or
 *                   meter change or a comp region, what was saved:
 *                   {@code <reason>[; saved as <snippet>][, at transport
 *                   beat <b>]}, the snippet being the element with the
 *                   attributes the writer emits, in the writer's order, each
 *                   value as saved, and the beat the saved column over four
 *                   for a note or an event whose column is an integer &ge; 0
 * @param count      how many saved items this entry stands for: {@code 1},
 *                   except for a {@link Kind#CC_LANE_SKIPPED skipped lane},
 *                   which stands for its saved events — {@code 0} for a lane
 *                   saved with none (the open announcement still counts such
 *                   a lane as one skipped CC event)
 * @param lane       for a {@link Kind#CC_EVENT_SKIPPED skipped CC event}, the
 *                   lane it was saved in; {@code null} for every other kind
 * @param insertName for a kind that {@linkplain Kind#namesItsInsert() names
 *                   its insert}, the insert's name as the open restored it
 *                   (never blank); empty for every other kind
 */
public record UnreadProjectItem(Kind kind, Location location, int index, String detail, int count,
                                EventLane lane, String insertName) {

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
         * assignment — was removed; its container was kept. So was a working
         * insert's {@code sidechain-source} that names no strip of the
         * project (story 334): the insert detects on its own input. Its scope
         * also admits a track, for an assignment saved on a track's own strip.
         */
        MIXER_REFERENCE_REMOVED,
        /** A tempo or meter change, or an unusable beat-0 tempo or meter, was skipped (story 334). */
        TEMPO_MAP_ENTRY_SKIPPED,
        /** A comp region of a track's takes was skipped (story 334). */
        COMP_REGION_SKIPPED,
        /**
         * An insert the open could not load was kept at its place in the
         * chain, passing audio through, and is written back as saved (story
         * 334; Persistence Integrity book §4.5).
         */
        INSERT_NOT_LOADED,
        /** An insert beyond the most a strip holds was skipped (story 334). */
        INSERT_SKIPPED,
        /** An insert parameter was not applied as saved: rejected, clamped, unknown, repeated or not finite (story 334). */
        INSERT_PARAMETER_ADJUSTED;

        /** Where items of this kind are saved: the locations an item of this kind may name. */
        public Scope scope() {
            return switch (this) {
                case MIDI_NOTE_SKIPPED, CC_LANE_SKIPPED, CC_EVENT_SKIPPED, MIDI_EFFECT_SETTING_ADJUSTED,
                     MIDI_EFFECT_NOT_RECOGNIZED, COMP_REGION_SKIPPED -> Scope.TRACK;
                case MIXER_REFERENCE_REMOVED, INSERT_NOT_LOADED, INSERT_SKIPPED, INSERT_PARAMETER_ADJUSTED ->
                        Scope.TRACK_OR_MIXER;
                case TEMPO_MAP_ENTRY_SKIPPED -> Scope.TRANSPORT;
            };
        }

        /**
         * Whether an item of this kind names the insert it concerns
         * ({@link UnreadProjectItem#insertName()}): the three insert kinds.
         */
        public boolean namesItsInsert() {
            return switch (this) {
                case INSERT_NOT_LOADED, INSERT_SKIPPED, INSERT_PARAMETER_ADJUSTED -> true;
                case MIDI_NOTE_SKIPPED, CC_LANE_SKIPPED, CC_EVENT_SKIPPED, MIDI_EFFECT_SETTING_ADJUSTED,
                     MIDI_EFFECT_NOT_RECOGNIZED, MIXER_REFERENCE_REMOVED, TEMPO_MAP_ENTRY_SKIPPED,
                     COMP_REGION_SKIPPED -> false;
            };
        }

        /**
         * Whether the next save of the opened project drops what the file
         * saved for an item of this kind: a skipped note, lane, event, tempo
         * or meter entry, comp region or insert and a removed mixer
         * assignment are not written back, and an adjusted setting or insert
         * parameter is written back with the value the open chose, not the
         * raw one. A MIDI effect kept as a placeholder and an insert the open
         * could not load are written back as they were saved — except for any
         * other child elements or text of their entry, which
         * {@link ProjectDeserializer#losesSavedContent()} counts separately.
         *
         * @return {@code true} when saving loses the item as saved
         */
        public boolean losesSavedContent() {
            return switch (this) {
                case MIDI_NOTE_SKIPPED, CC_LANE_SKIPPED, CC_EVENT_SKIPPED, MIDI_EFFECT_SETTING_ADJUSTED,
                     MIXER_REFERENCE_REMOVED, TEMPO_MAP_ENTRY_SKIPPED, COMP_REGION_SKIPPED, INSERT_SKIPPED,
                     INSERT_PARAMETER_ADJUSTED -> true;
                case MIDI_EFFECT_NOT_RECOGNIZED, INSERT_NOT_LOADED -> false;
            };
        }
    }

    /** The locations a {@link Kind kind} of item may name ({@link Kind#scope()}). */
    public enum Scope {
        /** On a track ({@link OnTrack}). */
        TRACK,
        /** On a track ({@link OnTrack}) or in a mixer container or strip ({@link InMixer}). */
        TRACK_OR_MIXER,
        /** In the transport ({@link InTransport}). */
        TRANSPORT;

        /**
         * Whether an item of this scope may be located at {@code location}.
         *
         * @param location the location
         * @return {@code true} when this scope admits it
         */
        public boolean admits(Location location) {
            return switch (this) {
                case TRACK -> location instanceof OnTrack;
                case TRACK_OR_MIXER -> location instanceof OnTrack || location instanceof InMixer;
                case TRANSPORT -> location instanceof InTransport;
            };
        }
    }

    /**
     * Where an unread item was saved; the reader's analyzer-insert WARNINGs
     * name the mixer strip of an insert with it too.
     */
    public sealed interface Location permits OnTrack, InMixer, InTransport {

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
     * An item saved in the transport's tempo map (story 334): an entry of
     * its tempo-change or meter-change list, or its beat-0 tempo or meter.
     *
     * @param entry which list or beat-0 attribute the item was saved in
     */
    public record InTransport(TransportEntry entry) implements Location {
        public InTransport {
            Objects.requireNonNull(entry, "entry must not be null");
        }

        /** {@code Transport}. */
        @Override
        public String describe() {
            return "Transport";
        }
    }

    /** Where in the transport's tempo map an {@link InTransport} item was saved. */
    public enum TransportEntry {
        /** The list of tempo changes after beat 0. */
        TEMPO_CHANGES,
        /** The list of meter (time-signature) changes after beat 0. */
        METER_CHANGES,
        /** The beat-0 tempo attribute. */
        BEAT_ZERO_TEMPO,
        /** The beat-0 meter attributes, numerator and denominator, as a pair. */
        BEAT_ZERO_METER
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
        Objects.requireNonNull(insertName, "insertName must not be null");
        if (!kind.scope().admits(location)) {
            throw new IllegalArgumentException(kind + " cannot be located at " + location);
        }
        if (kind.namesItsInsert() ? insertName.isBlank() : !insertName.isEmpty()) {
            throw new IllegalArgumentException(kind + (kind.namesItsInsert()
                    ? " needs the name of its insert" : " names no insert"));
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
     * An item of a kind that names no insert ({@link Kind#namesItsInsert()}
     * is {@code false}), saved in the CC lane {@code lane} for a
     * {@link Kind#CC_EVENT_SKIPPED skipped CC event} and in none
     * ({@code null}) for every other kind.
     */
    public UnreadProjectItem(Kind kind, Location location, int index, String detail, int count, EventLane lane) {
        this(kind, location, index, detail, count, lane, "");
    }

    /**
     * An item of a kind that names no insert and is saved in no CC lane:
     * any kind but {@link Kind#CC_EVENT_SKIPPED} and the insert kinds.
     */
    public UnreadProjectItem(Kind kind, Location location, int index, String detail, int count) {
        this(kind, location, index, detail, count, null, "");
    }

    /**
     * An item about one insert of a strip, standing for one saved item.
     *
     * @param kind        an insert kind ({@link Kind#namesItsInsert()})
     * @param strip       the strip holding the insert: the track for a
     *                    track's own channel, else the mixer strip
     * @param insertIndex the insert's zero-based position among the strip's
     *                    saved inserts (a skipped one keeps its number)
     * @param insertName  the insert's name as the open restored it
     * @param detail      why the item was not honoured, as saved
     * @return the item
     */
    public static UnreadProjectItem atInsert(Kind kind, Location strip, int insertIndex, String insertName,
                                             String detail) {
        return new UnreadProjectItem(kind, strip, insertIndex, detail, 1, null, insertName);
    }

    /**
     * The WARNING line the open logs for this item:
     * {@code <where>: <what> — <detail>}; the track, item and lane positions
     * are counted from 1; values in the detail are as saved. For example
     * {@code Track 2 'Keys': MIDI note 5 skipped — velocity must be 0–127: 128;
     * saved as <note note-number="60" start-column="17" duration-columns="4"
     * velocity="128" channel="0"/>, at transport beat 4.25}. A skipped lane
     * saved with no events reads {@code CC lane <i> skipped with no saved
     * events}. A tempo-map entry reads {@code Transport: tempo change <n>
     * skipped}, {@code meter change <n> skipped}, {@code tempo at beat 0
     * skipped} or {@code meter at beat 0 skipped}; an insert item names its
     * insert after its number, quoted, for example {@code Track 2 'Vox':
     * insert 2 'Old FX' not loaded (bypassed, kept as saved) — effect-type
     * 'NOT_A_TYPE' is not an effect this build knows}.
     *
     * @return the log line
     */
    public String logLine() {
        int n = index + 1;
        String what = switch (kind) {
            case MIDI_NOTE_SKIPPED -> "MIDI note " + n + " skipped";
            case CC_LANE_SKIPPED -> "CC lane " + n + (count == 0 ? " skipped with no saved events"
                    : " skipped with its " + count + (count == 1 ? " saved event" : " saved events"));
            case CC_EVENT_SKIPPED -> "CC event " + n + " of " + lane.describe() + " skipped";
            case MIDI_EFFECT_SETTING_ADJUSTED -> "MIDI effect " + n + " setting adjusted";
            case MIDI_EFFECT_NOT_RECOGNIZED -> "MIDI effect " + n + " not recognized (kept as saved)";
            case MIXER_REFERENCE_REMOVED -> "mixer assignment " + n + " removed";
            case TEMPO_MAP_ENTRY_SKIPPED -> switch (((InTransport) location).entry()) {
                case TEMPO_CHANGES -> "tempo change " + n + " skipped";
                case METER_CHANGES -> "meter change " + n + " skipped";
                case BEAT_ZERO_TEMPO -> "tempo at beat 0 skipped";
                case BEAT_ZERO_METER -> "meter at beat 0 skipped";
            };
            case COMP_REGION_SKIPPED -> "comp region " + n + " skipped";
            case INSERT_NOT_LOADED -> "insert " + n + " '" + logText(insertName)
                    + "' not loaded (bypassed, kept as saved)";
            case INSERT_SKIPPED -> "insert " + n + " '" + logText(insertName) + "' skipped";
            case INSERT_PARAMETER_ADJUSTED -> "insert " + n + " '" + logText(insertName) + "' parameter adjusted";
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
     * VCA-group, return-bus and insert names, clip references and analyzer
     * settings — so a file can neither split one of those lines nor forge
     * one. The same rule governs the log lines story 334 adds outside the
     * reader that quote text a file or the installed-plugin registry holds:
     * {@link ProjectSerializer}'s line for a plugin state it could not save,
     * and the startup lines of the installed-plugin registry
     * ({@code PluginRegistry} and its registry store,
     * {@code InstalledPluginStore}). A log line written by any other class is
     * not covered by this rule.</p>
     *
     * @param value the text as saved
     * @return the text as a log line shows it
     */
    public static String logText(String value) {
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

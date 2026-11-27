package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.status.EngineHealthText;
import com.benesquivelmusic.daw.sdk.audio.ClockKind;
import com.benesquivelmusic.daw.sdk.audio.ClockLockStatus;
import com.benesquivelmusic.daw.sdk.audio.ClockSource;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * What the clock badge ({@link ClockStatusIndicator}) owes for what it
 * measures (rulings E31, E61, E108, E110, E111, E173, E216 to E223 and E280): at
 * each measuring look, the take's clock marks for every change of the
 * measurement that the badge has not applied, in order, and at most one
 * notice.
 *
 * <p>A look replays the transitions its status carries past the last one the
 * badge applied for that serial; the first measuring look of a serial
 * replays them from its start (E219), unless a loss or a close consumed that
 * serial (E220, E252, E259, E268). A first kept transition more than one
 * past what was applied is a gap: more changes came than the status keeps,
 * so the take is marked unlocked from where the first missed change can have
 * begun, or from the take's start-frame at a serial's first measuring look
 * (E218). A status that carries no transitions is judged by its level
 * alone. The notice is the unlock's or the relock's when the level changed
 * from the one on show, and otherwise, when unlocks came and went between
 * two looks, one that says how many.</p>
 *
 * <p>FX thread.</p>
 */
final class ClockLockAnnouncer {

    /** How long before a look an unlock it missed can have begun: two one-second polls and one of margin (E218). */
    static final long UNLOCK_LEAD_NANOS = TimeUnit.SECONDS.toNanos(3);

    /**
     * What one look handed to the take.
     *
     * @param unlocks    the unlocks it replayed
     * @param gap        whether more changes came than the status kept
     * @param takeMarked whether an audio take holds an unlock mark after one of the look's unlocks (ruling E223)
     */
    private record HandedOver(int unlocks, boolean gap, boolean takeMarked) {
    }

    private final NotificationManager notifications;
    private final TakeClockMarks takeMarks;
    private final LongSupplier nanos;
    /** The status the last measuring look applied; {@code null} before the first and after a loss or a close. */
    private ClockLockStatus lastApplied;
    /** When the look that applied {@link #lastApplied} ran, on {@link #nanos}. */
    private long appliedAtNanos;
    /**
     * The status last applied before the last loss or close; that serial's transitions up to its next measuring look
     * are never replayed; {@code null} before the first.
     */
    private ClockLockStatus consumed;

    /**
     * @param notifications where the notices go
     * @param takeMarks     the take the badge marks
     * @param nanos         the badge's monotonic nanosecond clock
     */
    ClockLockAnnouncer(NotificationManager notifications, TakeClockMarks takeMarks, LongSupplier nanos) {
        this.notifications = Objects.requireNonNull(notifications, "notifications must not be null");
        this.takeMarks = Objects.requireNonNull(takeMarks, "takeMarks must not be null");
        this.nanos = Objects.requireNonNull(nanos, "nanos must not be null");
    }

    /**
     * The first look of a loss: the serial last applied is forgotten with no notice and no mark (rulings E81, E84,
     * E220, E252 and E268). The status the look read is never the one consumed: it can be that of a stream opened
     * after the loss began, and once the declaration has stopped the lost stream, its status is never applied again:
     * a stopped stream, closed or pending its release, never reads as open, and a look applies a status only when
     * its first read found a stream open.
     */
    void consumeAtALoss() {
        consume(lastApplied);
    }

    /**
     * A look that finds the stream closed: the serial last applied is forgotten with no notice and no mark (rulings
     * E81, E220 and E252). The status the look read is never the one in play: a status read at a closing look can
     * only be that of a stream that opened during that look, and a closed stream's serial is never published again
     * (ruling E259).
     */
    void consumeAtAClose() {
        consume(lastApplied);
    }

    private void consume(ClockLockStatus inPlay) {
        if (inPlay != null) {
            consumed = inPlay;
        }
        lastApplied = null;
    }

    /**
     * A measuring look applies {@code current}; a look that read no status applies nothing.
     *
     * @param current            the status the look read, or {@code null}
     * @param source             the source on show
     * @param audioTakeFirstSeen whether this is the first measuring look to see the audio take recording now
     */
    void measured(ClockLockStatus current, ClockSource source, boolean audioTakeFirstSeen) {
        if (current == null) {
            return;
        }
        if (!(source.kind() instanceof ClockKind.Internal)) {
            boolean shownUnlocked = shownUnlocked(current);
            // E173: the start mark comes before the changes this look hands over.
            boolean startMarked = audioTakeFirstSeen && shownUnlocked && takeMarks.takeStartsUnlocked();
            if (isDecided(current)) {
                notifyFor(current, source.name(), shownUnlocked, handOver(current, shownUnlocked, startMarked));
            }
        }
        lastApplied = current;
        appliedAtNanos = nanos.getAsLong();
    }

    private HandedOver handOver(ClockLockStatus current, boolean shownUnlocked, boolean startMarked) {
        boolean sameSerial = appliedBefore(current);
        long seen = sameSerial ? lastApplied.lastSequence() : 0L;
        boolean replays = sameSerial || consumed == null || consumed.serial() != current.serial();
        List<ClockLockStatus.Transition> unseen = replays
                ? current.transitions().stream().filter(change -> change.sequence() > seen).toList()
                : List.of();
        boolean gap = !unseen.isEmpty() && unseen.getFirst().sequence() > seen + 1;
        boolean takeMarked = startMarked;
        boolean unlocked = shownUnlocked;
        int unlocks = 0;
        if (gap) {
            takeMarked |= sameSerial
                    ? takeMarks.lockChanged(false, gapEvidenceNanos())
                    : takeMarks.takeStartsUnlocked();
            unlocked = true;
        }
        for (ClockLockStatus.Transition change : unseen) {
            takeMarked |= takeMarks.lockChanged(change.locked(), change.evidenceNanos());
            unlocked = !change.locked();
            if (unlocked) {
                unlocks++;
            }
        }
        boolean nowUnlocked = current.lock() == ClockLockStatus.Lock.UNLOCKED;
        if (nowUnlocked != unlocked) {
            // A status that carries no transitions is judged by its level alone.
            takeMarked |= takeMarks.lockChanged(!nowUnlocked, current.evidenceNanos());
        }
        return new HandedOver(unlocks, gap, takeMarked);
    }

    /**
     * When the missed changes of the serial last applied can have begun (ruling E218): the later of the
     * evidence of the status applied, when it has any, and three seconds before the look that applied it.
     */
    private long gapEvidenceNanos() {
        long beforeTheLook = appliedAtNanos - UNLOCK_LEAD_NANOS;
        long applied = lastApplied.evidenceNanos();
        return applied != 0L && applied - beforeTheLook > 0L ? applied : beforeTheLook;
    }

    private void notifyFor(ClockLockStatus current, String sourceName, boolean shownUnlocked, HandedOver handed) {
        boolean nowUnlocked = current.lock() == ClockLockStatus.Lock.UNLOCKED;
        String device = deviceName(current.device());
        // Read after the look's hand-over: a take sealed early by now is never said to be marked (ruling E280).
        TakeClockMarks.TakeRecording recording = takeMarks.takeRecording();
        if (nowUnlocked && !shownUnlocked) {
            String key = switch (recording) {
                case AUDIO -> "clockBadge.notice.unlocked.take";
                case WITHOUT_AUDIO -> "clockBadge.notice.unlocked.takeWithoutAudio";
                case NONE -> "clockBadge.notice.unlocked";
            };
            notifications.notify(EngineHealthText.text(key, device, sourceName));
        } else if (!nowUnlocked && shownUnlocked) {
            notifications.notifyInformation(EngineHealthText.text("clockBadge.notice.relocked", device, sourceName));
        } else if (!nowUnlocked && (handed.unlocks() > 0 || handed.gap())) {
            notifications.notify(lostAndRelocked(recording, device, sourceName, handed));
        }
    }

    /** E216's one notice for unlocks that came and went between two looks, ending as E223 rules. */
    private static String lostAndRelocked(TakeClockMarks.TakeRecording recording, String device, String sourceName,
                                          HandedOver handed) {
        String ending = switch (recording) {
            case AUDIO -> handed.takeMarked() ? ".take" : ".takeWithoutAudio";
            case WITHOUT_AUDIO -> ".takeWithoutAudio";
            case NONE -> "";
        };
        if (handed.gap()) {
            return EngineHealthText.text("clockBadge.notice.lostAndRelocked.orMore" + ending, device, sourceName,
                    String.valueOf(handed.unlocks() + 1));
        }
        return EngineHealthText.text("clockBadge.notice.lostAndRelocked" + ending, device, sourceName,
                handed.unlocks(), String.valueOf(handed.unlocks()));
    }

    private boolean appliedBefore(ClockLockStatus current) {
        return lastApplied != null && lastApplied.serial() == current.serial();
    }

    /** A {@link ClockLockStatus.Lock#CHECKING} or a first {@link ClockLockStatus.Lock#LOCKED} counts as locked. */
    private boolean shownUnlocked(ClockLockStatus current) {
        return appliedBefore(current) && lastApplied.lock() == ClockLockStatus.Lock.UNLOCKED;
    }

    private static boolean isDecided(ClockLockStatus status) {
        return status.lock() == ClockLockStatus.Lock.LOCKED || status.lock() == ClockLockStatus.Lock.UNLOCKED;
    }

    /** The device the driver side names (E111), never the default placeholder; a DeviceId name is never blank. */
    private static String deviceName(DeviceId measured) {
        return measured.isDefault() ? EngineHealthText.text("clockBadge.notice.unnamedDevice") : measured.name();
    }
}

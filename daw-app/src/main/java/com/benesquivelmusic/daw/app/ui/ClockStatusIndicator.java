package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.audio.ClockKind;
import com.benesquivelmusic.daw.sdk.audio.ClockLockEvent;
import com.benesquivelmusic.daw.sdk.audio.ClockSource;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.util.Duration;

import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Transport-bar badge showing the active hardware clock source ("INT 48k",
 * "EXT W/C 96k", "SPDIF 44.1k") that flashes red when the driver reports
 * the external clock as unlocked.
 *
 * <p>The lock state derives from the {@link Flow.Publisher} returned by
 * {@link AudioEngineController#clockLockEvents()}, which an ASIO
 * implementation populates from {@code ASIOFuture(kAsioGetExternalClockLocked)}
 * polled at 1 Hz from a non-audio thread plus the asynchronous
 * {@code kAsioResyncRequest} callback. CoreAudio surfaces the same
 * information through {@code kAudioDevicePropertyClockSourceLocked}.</p>
 *
 * <p>On a lock-loss event the indicator additionally:</p>
 * <ul>
 *   <li>Pushes a warning to the registered {@link NotificationManager}
 *       ("External clock not locked — recording quality compromised").</li>
 *   <li>Invokes the registered {@code recordingPauseHandler} runnable so
 *       the transport can pause recording (not playback) — uncorrected
 *       sample slips during recording would silently corrupt the take,
 *       whereas during playback they are merely audible.</li>
 * </ul>
 *
 * <p>The badge text is set declaratively via
 * {@link #showSource(ClockSource, int)} whenever the active clock source
 * or sample rate changes; lock state is updated reactively from the
 * subscribed publisher.</p>
 */
public final class ClockStatusIndicator extends Label {

    private static final String STYLE_LOCKED =
            "-fx-padding: 2 8 2 8; -fx-background-radius: 8; "
                    + "-fx-background-color: #2c3e50; -fx-text-fill: #ecf0f1; "
                    + "-fx-font-family: 'Monospaced'; -fx-font-size: 10px;";
    private static final String STYLE_UNLOCKED =
            "-fx-padding: 2 8 2 8; -fx-background-radius: 8; "
                    + "-fx-background-color: #e74c3c; -fx-text-fill: white; "
                    + "-fx-font-family: 'Monospaced'; -fx-font-weight: bold; -fx-font-size: 10px;";
    private static final String STYLE_DIM =
            "-fx-padding: 2 8 2 8; -fx-background-radius: 8; "
                    + "-fx-background-color: #34495e; -fx-text-fill: #7f8c8d; "
                    + "-fx-font-family: 'Monospaced'; -fx-font-size: 10px;";

    private final NotificationManager notifications;
    private final Runnable recordingPauseHandler;
    private final Timeline flashTimeline;
    private final AtomicReference<ClockSource> activeSource = new AtomicReference<>();
    private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
    private volatile int sampleRate;
    private volatile boolean locked = true;

    /**
     * Creates a new clock-status indicator.
     *
     * @param notifications          notification sink for lock-loss
     *                               warnings; must not be null
     * @param recordingPauseHandler  runnable to invoke when the driver
     *                               reports lock loss while the user is
     *                               recording — typically a wrapper that
     *                               calls {@code TransportController.onPause()}
     *                               only if a recording pipeline is
     *                               active. May be {@code null} when the
     *                               indicator is used in a context that
     *                               cannot pause recording (for example
     *                               in a settings preview).
     */
    public ClockStatusIndicator(NotificationManager notifications, Runnable recordingPauseHandler) {
        this.notifications = Objects.requireNonNull(notifications, "notifications must not be null");
        this.recordingPauseHandler = recordingPauseHandler;
        getStyleClass().add("clock-status-indicator");
        setText("CLK —");
        setStyle(STYLE_DIM);
        setTooltip(new Tooltip(
                "Hardware clock source. Flashes red when the external clock is not locked."));
        // 0.5 s on/off flash for the unlocked state — matches the cadence
        // ASIO drivers themselves use in their own clock-lock LEDs.
        this.flashTimeline = new Timeline(
                new KeyFrame(Duration.seconds(0.5), _ -> toggleFlashStyle()));
        flashTimeline.setCycleCount(Animation.INDEFINITE);
    }

    /**
     * Updates the badge text to reflect the active clock source and
     * project sample rate. Safe to call from any thread.
     *
     * @param source     the clock source the device is locked to,
     *                   or {@code null} when the active backend has no
     *                   clock-source selection (the badge dims out)
     * @param sampleRate project sample rate in Hz; non-positive values
     *                   are rendered as a dash ("—")
     */
    public void showSource(ClockSource source, int sampleRate) {
        this.activeSource.set(source);
        this.sampleRate = sampleRate;
        runOnFx(this::refresh);
    }

    /**
     * Subscribes to the given {@link ClockLockEvent} publisher. Cancels
     * any previous subscription. Safe to call from any thread.
     *
     * @param publisher the publisher to consume; must not be null
     */
    public void bind(Flow.Publisher<ClockLockEvent> publisher) {
        Objects.requireNonNull(publisher, "publisher must not be null");
        Flow.Subscription previous = subscription.getAndSet(null);
        if (previous != null) {
            previous.cancel();
        }
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) {
                subscription.set(s);
                s.request(Long.MAX_VALUE);
            }
            @Override public void onNext(ClockLockEvent event) { onLockEvent(event); }
            @Override public void onError(Throwable t) { /* ignored */ }
            @Override public void onComplete() { /* ignored */ }
        });
    }

    /** Visible for tests — allows direct injection of a synthetic lock event. */
    void onLockEvent(ClockLockEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        ClockSource current = activeSource.get();
        if (current != null && event.sourceId() != current.id()) {
            // Event for a different (inactive) source — ignore.
            return;
        }
        boolean previouslyLocked = this.locked;
        this.locked = event.locked();
        if (previouslyLocked && !event.locked()) {
            notifications.notify("External clock not locked — recording quality compromised");
            if (recordingPauseHandler != null) {
                try {
                    recordingPauseHandler.run();
                } catch (RuntimeException ignored) {
                    // The pause handler is best-effort; never let a transport-
                    // layer exception crash the device-event subscriber.
                }
            }
        }
        runOnFx(this::refresh);
    }

    private void toggleFlashStyle() {
        // Alternate between the unlocked solid red and a slightly darker
        // shade so the eye perceives a flash even on slow displays.
        if (Objects.equals(getStyle(), STYLE_UNLOCKED)) {
            setStyle(STYLE_UNLOCKED.replace("#e74c3c", "#7d2018"));
        } else {
            setStyle(STYLE_UNLOCKED);
        }
    }

    private void refresh() {
        ClockSource source = activeSource.get();
        if (source == null) {
            setText("CLK —");
            setStyle(STYLE_DIM);
            stopFlash();
            return;
        }
        String shortLabel = source.kind().shortLabel();
        String rateLabel = sampleRate <= 0
                ? "—"
                : (sampleRate % 1000 == 0
                        ? (sampleRate / 1000) + "k"
                        : String.format("%.1fk", sampleRate / 1000.0));
        if (locked) {
            setText(shortLabel + " " + rateLabel);
            setStyle(STYLE_LOCKED);
            stopFlash();
        } else {
            setText("⚠ " + shortLabel + " " + rateLabel + " UNLOCKED");
            setStyle(STYLE_UNLOCKED);
            startFlash();
        }
    }

    private void startFlash() {
        if (flashTimeline.getStatus() != Animation.Status.RUNNING) {
            flashTimeline.playFromStart();
        }
    }

    private void stopFlash() {
        if (flashTimeline.getStatus() == Animation.Status.RUNNING) {
            flashTimeline.stop();
        }
    }

    /** Visible for tests — current locked flag. */
    boolean isLocked() {
        return locked;
    }

    /** Visible for tests — last source the indicator was told to show. */
    ClockSource activeSource() {
        return activeSource.get();
    }

    /**
     * Returns a {@link ClockKind#shortLabel() short label} for an
     * arbitrary kind, used by callers that have a {@link ClockKind} but
     * not yet a full {@link ClockSource}.
     *
     * @param kind the clock kind; must not be null
     * @return the short label
     */
    public static String labelFor(ClockKind kind) {
        return Objects.requireNonNull(kind, "kind must not be null").shortLabel();
    }

    private static void runOnFx(Runnable r) {
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }
}

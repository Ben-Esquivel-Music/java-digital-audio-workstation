package com.benesquivelmusic.daw.app.ui.recording;

import com.benesquivelmusic.daw.app.ui.AudioEngineController;
import com.benesquivelmusic.daw.app.ui.NotificationLevel;
import com.benesquivelmusic.daw.app.ui.SettingsModel;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The production {@link SessionInputSelection}: the session input device is
 * {@link SettingsModel#getAudioInputDevice()}, and selecting one persists it
 * there and re-applies the engine configuration through
 * {@link AudioEngineController#applyConfiguration} (story 322, Audio Engine
 * Wiring Design Book §5.6).
 *
 * <p>{@link #select(AudioDeviceInfo)} mirrors {@code MainController.applyStartupAudioSettings}
 * exactly: the persisted backend (or the provisioned one when blank), the
 * persisted output device, sample rate, buffer size, bit depth and worker-pool
 * size are combined with the newly chosen input device into one
 * {@link AudioEngineController.Request} and applied on a virtual thread, so
 * the FX thread is never blocked by a device open. A failure is surfaced
 * through the injected notification seam with an "Open Audio Settings" action
 * — the same actionable-notification idiom as startup — and never thrown at
 * the caller.</p>
 *
 * <p>Selecting the device whose {@link AudioDeviceInfo#qualifiedName()
 * qualified name} already <em>is</em> the persisted session name persists
 * nothing and starts no worker (the interface's no-op contract): every
 * confirmed per-track dialog used to reach {@code applyConfiguration}, which
 * stops the pump and reopens the stream — so "Add Audio Track → OK" with the
 * preselected session device stopped playback. Pre-322 adding a track never
 * touched the engine, and it still must not. The gate is that EXACT string
 * compare, deliberately narrower than {@link #isSessionDevice} (fix round
 * 2): the predicate keeps story 316's bare-name tolerance so a legacy
 * persisted "Mic" still preselects and still agrees with the mismatch check,
 * but under it a bare name matches the same-named endpoint of EVERY host API
 * — and the callers write the per-track index, the status bar and the dirty
 * flag unconditionally, so a tolerant gate left a pick of "Mic [WASAPI]" over
 * a persisted bare "Mic" persisted nowhere and applied never: the silent
 * ignore design book §5.6 forbids. Under the exact rule a legacy bare name
 * upgrades itself to the qualified form on the first confirm (one persist,
 * one apply — what every confirm did before the gate existed) and a
 * differently-qualified pick persists and applies.</p>
 *
 * <p><strong>Latest selection wins</strong> (PR #977 review). An ASIO reopen
 * takes seconds — long enough for a second input-port dialog to be confirmed
 * — and {@code applyConfiguration} serialises concurrent callers in
 * unspecified order, so two independent workers could leave settings and the
 * UI naming B while the engine runs A. Each selection therefore bumps a
 * {@linkplain #generation generation} on the caller's thread together with
 * the persist, and every worker applies under one {@linkplain #applyLock
 * lock}, skipping its apply when its generation is no longer the latest. In
 * every interleaving the device the engine is asked to end on is the one last
 * persisted (a failed apply is reported, not repaired): A applies only when
 * it read the generation before B was selected — it was already applying,
 * and B applies after it; otherwise A reads B's newer generation under the
 * lock and skips, so a worker never applies from a waiting state once
 * superseded. A superseded worker's failure is stale and is logged but
 * never shown — the newer worker reports its own outcome. A lock rather than
 * a single-thread executor because this class has no dispose/shutdown seam
 * and the worker is handed back to callers as a plain {@link Thread}.</p>
 */
public final class SettingsBackedSessionInputSelection implements SessionInputSelection {

    private static final Logger LOG = Logger.getLogger(SettingsBackedSessionInputSelection.class.getName());

    /**
     * Where an apply failure is reported. Production marshals this onto the FX
     * thread (the worker never touches JavaFX itself).
     */
    @FunctionalInterface
    public interface NotificationSink {
        /**
         * Shows an actionable notification.
         *
         * @param level       the severity
         * @param message     the text
         * @param actionLabel the action button label
         * @param action      the action to run when the button is clicked
         */
        void show(NotificationLevel level, String message, String actionLabel, Runnable action);
    }

    private final SettingsModel settings;
    private final AudioEngineController controller;
    private final NotificationSink notifications;
    private final Runnable openAudioSettings;

    /**
     * The selection counter: bumped on the caller's thread in
     * {@link #selectAndApply} right where the persist happens, so "the latest
     * selection" and "the persisted device" are always the same one. The
     * persist and the bump are two steps; they name the same device only
     * because {@link #select} is called from one thread — the FX thread, per
     * the interface's threading contract — so no second selection can land
     * between them. A worker captures its own value and compares it under
     * {@link #applyLock}.
     */
    private final AtomicLong generation = new AtomicLong();

    /**
     * Serialises the apply section across workers. Virtual threads park on it
     * cheaply; the FX thread never takes it (the persist and the bump happen
     * before the worker starts).
     */
    private final ReentrantLock applyLock = new ReentrantLock();

    /**
     * Creates the settings-backed selection.
     *
     * @param settings          the persisted preferences the session device lives in; must not be {@code null}
     * @param controller        the engine controller that applies a configuration; must not be {@code null}
     * @param notifications     the actionable-notification seam for apply failures; must not be {@code null}
     * @param openAudioSettings the action behind the failure notification's button; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public SettingsBackedSessionInputSelection(SettingsModel settings,
                                               AudioEngineController controller,
                                               NotificationSink notifications,
                                               Runnable openAudioSettings) {
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.controller = Objects.requireNonNull(controller, "controller must not be null");
        this.notifications = Objects.requireNonNull(notifications, "notifications must not be null");
        this.openAudioSettings = Objects.requireNonNull(openAudioSettings, "openAudioSettings must not be null");
    }

    @Override
    public String currentDeviceName() {
        return settings.getAudioInputDevice();
    }

    @Override
    public void select(AudioDeviceInfo device) {
        selectAndApply(device);
    }

    /**
     * {@link #select(AudioDeviceInfo)} exposing the worker so a caller (a test)
     * can wait for the apply to finish. The persist and the generation bump
     * happen synchronously before this method returns; only the engine
     * reconfiguration is deferred. When {@code device.qualifiedName()} equals
     * {@link #currentDeviceName()} — exactly, never by the bare-name tolerance
     * of {@link #isSessionDevice} — nothing happens and no worker exists (see
     * the class Javadoc).
     *
     * @param device the device the user chose; must not be {@code null}
     * @return the started virtual thread performing the apply — it skips its
     *         apply when a newer selection superseded it while it waited for
     *         the lock — or empty when {@code device}'s qualified name already
     *         was the session name
     */
    public Optional<Thread> selectAndApply(AudioDeviceInfo device) {
        Objects.requireNonNull(device, "device must not be null");
        String inputDevice = device.qualifiedName();
        if (inputDevice.equals(currentDeviceName())) {
            return Optional.empty();
        }
        settings.setAudioInputDevice(inputDevice);
        long mine = generation.incrementAndGet();
        Thread worker = Thread.ofVirtual().name("daw-session-input-apply")
                .unstarted(() -> applyUnlessSuperseded(mine, inputDevice));
        worker.start();
        return Optional.of(worker);
    }

    /**
     * The worker body: under {@link #applyLock}, applies {@code inputDevice}
     * unless a newer selection has bumped {@link #generation} past
     * {@code mine} — that selection's own worker applies its device. A failure
     * is always logged but shown only while {@code mine} is still the latest;
     * a superseded failure is stale.
     */
    private void applyUnlessSuperseded(long mine, String inputDevice) {
        applyLock.lock();
        try {
            if (mine != generation.get()) {
                LOG.fine(() -> "Session input '" + inputDevice + "' superseded before it was applied");
                return;
            }
            String backend = "<configured backend>";
            try {
                String persistedBackend = settings.getAudioBackend();
                backend = persistedBackend.isBlank()
                        ? controller.getProvisionedBackendName() : persistedBackend;
                controller.applyConfiguration(new AudioEngineController.Request(
                        backend,
                        inputDevice,
                        settings.getAudioOutputDevice(),
                        SampleRate.fromHz((int) settings.getSampleRate()),
                        settings.getBufferSize(),
                        settings.getBitDepth(),
                        settings.getWorkerPoolSize()));
            } catch (RuntimeException failure) {
                LOG.log(Level.WARNING, "Failed to apply the session input device '" + inputDevice + "'", failure);
                if (mine != generation.get()) {
                    return; // stale: a newer selection was made meanwhile and reports its own outcome
                }
                String reason = failure.getMessage() == null || failure.getMessage().isBlank()
                        ? "the configuration was rejected"
                        : failure.getMessage();
                notifications.show(
                        NotificationLevel.ERROR,
                        "Session input '" + inputDevice + "' could not be applied on backend '"
                                + backend + "': " + reason
                                + ". Reconnect the device or choose another in Audio Settings.",
                        "Open Audio Settings",
                        openAudioSettings);
            }
        } finally {
            applyLock.unlock();
        }
    }
}

package com.benesquivelmusic.daw.app.ui.recording;

import com.benesquivelmusic.daw.app.ui.AudioEngineController;
import com.benesquivelmusic.daw.app.ui.NotificationLevel;
import com.benesquivelmusic.daw.app.ui.SettingsModel;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;

import java.util.Objects;
import java.util.Optional;
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
     * can wait for the apply to finish. The persist happens synchronously
     * before this method returns; only the engine reconfiguration is deferred.
     * When {@code device.qualifiedName()} equals {@link #currentDeviceName()}
     * — exactly, never by the bare-name tolerance of {@link #isSessionDevice}
     * — nothing happens and no worker exists (see the class Javadoc).
     *
     * @param device the device the user chose; must not be {@code null}
     * @return the started virtual thread performing the apply, or empty when
     *         {@code device}'s qualified name already was the session name
     */
    public Optional<Thread> selectAndApply(AudioDeviceInfo device) {
        Objects.requireNonNull(device, "device must not be null");
        String inputDevice = device.qualifiedName();
        if (inputDevice.equals(currentDeviceName())) {
            return Optional.empty();
        }
        settings.setAudioInputDevice(inputDevice);
        Thread worker = Thread.ofVirtual().name("daw-session-input-apply").unstarted(() -> {
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
        });
        worker.start();
        return Optional.of(worker);
    }
}

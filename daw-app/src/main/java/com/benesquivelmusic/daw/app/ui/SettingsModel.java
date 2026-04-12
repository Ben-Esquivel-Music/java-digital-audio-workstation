package com.benesquivelmusic.daw.app.ui;

import java.util.Objects;
import java.util.prefs.Preferences;

/**
 * Persists application settings across restarts using the Java {@link Preferences} API.
 *
 * <p>Stores audio configuration (sample rate, bit depth, buffer size),
 * project defaults (auto-save interval, default tempo), appearance
 * preferences (UI scale), and plugin scan paths.</p>
 */
public final class SettingsModel {

    // ── Audio keys ───────────────────────────────────────────────────────────
    private static final String KEY_SAMPLE_RATE = "audio.sampleRate";
    private static final String KEY_BIT_DEPTH = "audio.bitDepth";
    private static final String KEY_BUFFER_SIZE = "audio.bufferSize";
    private static final String KEY_AUDIO_BACKEND = "audio.backend";
    private static final String KEY_AUDIO_INPUT_DEVICE = "audio.inputDevice";
    private static final String KEY_AUDIO_OUTPUT_DEVICE = "audio.outputDevice";

    // ── Project keys ─────────────────────────────────────────────────────────
    private static final String KEY_AUTO_SAVE_INTERVAL_SECONDS = "project.autoSaveIntervalSeconds";
    private static final String KEY_DEFAULT_TEMPO = "project.defaultTempo";

    // ── Appearance keys ──────────────────────────────────────────────────────
    private static final String KEY_UI_SCALE = "appearance.uiScale";

    // ── Plugin keys ──────────────────────────────────────────────────────────
    private static final String KEY_PLUGIN_SCAN_PATHS = "plugins.scanPaths";

    // ── Defaults ─────────────────────────────────────────────────────────────
    static final double DEFAULT_SAMPLE_RATE = 96_000.0;
    static final int DEFAULT_BIT_DEPTH = 24;
    static final int DEFAULT_BUFFER_SIZE = 256;
    static final int DEFAULT_AUTO_SAVE_INTERVAL_SECONDS = 120;
    static final double DEFAULT_TEMPO = 120.0;
    static final double DEFAULT_UI_SCALE = 1.0;
    static final String DEFAULT_PLUGIN_SCAN_PATHS = "";
    /** Empty backend name means "auto-select best available". */
    static final String DEFAULT_AUDIO_BACKEND = "";
    /** Empty device name means "use default device". */
    static final String DEFAULT_AUDIO_DEVICE = "";

    private final Preferences prefs;

    private double sampleRate;
    private int bitDepth;
    private int bufferSize;
    private int autoSaveIntervalSeconds;
    private double defaultTempo;
    private double uiScale;
    private String pluginScanPaths;
    private String audioBackend;
    private String audioInputDevice;
    private String audioOutputDevice;
    private KeyBindingManager keyBindingManager;

    /**
     * Creates a new settings model backed by the given {@link Preferences} node.
     *
     * @param prefs the backing preferences node (must not be {@code null})
     */
    public SettingsModel(Preferences prefs) {
        this.prefs = Objects.requireNonNull(prefs, "prefs must not be null");
        load();
    }

    private void load() {
        sampleRate = prefs.getDouble(KEY_SAMPLE_RATE, DEFAULT_SAMPLE_RATE);
        bitDepth = prefs.getInt(KEY_BIT_DEPTH, DEFAULT_BIT_DEPTH);
        bufferSize = prefs.getInt(KEY_BUFFER_SIZE, DEFAULT_BUFFER_SIZE);
        autoSaveIntervalSeconds = prefs.getInt(KEY_AUTO_SAVE_INTERVAL_SECONDS,
                DEFAULT_AUTO_SAVE_INTERVAL_SECONDS);
        defaultTempo = prefs.getDouble(KEY_DEFAULT_TEMPO, DEFAULT_TEMPO);
        uiScale = prefs.getDouble(KEY_UI_SCALE, DEFAULT_UI_SCALE);
        pluginScanPaths = prefs.get(KEY_PLUGIN_SCAN_PATHS, DEFAULT_PLUGIN_SCAN_PATHS);
        audioBackend = prefs.get(KEY_AUDIO_BACKEND, DEFAULT_AUDIO_BACKEND);
        audioInputDevice = prefs.get(KEY_AUDIO_INPUT_DEVICE, DEFAULT_AUDIO_DEVICE);
        audioOutputDevice = prefs.get(KEY_AUDIO_OUTPUT_DEVICE, DEFAULT_AUDIO_DEVICE);
    }

    // ── Audio ────────────────────────────────────────────────────────────────

    /** Returns the configured sample rate in Hz. */
    public double getSampleRate() {
        return sampleRate;
    }

    /**
     * Sets the sample rate and persists the change.
     *
     * @param sampleRate sample rate in Hz (must be positive)
     */
    public void setSampleRate(double sampleRate) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        this.sampleRate = sampleRate;
        prefs.putDouble(KEY_SAMPLE_RATE, sampleRate);
    }

    /** Returns the configured bit depth. */
    public int getBitDepth() {
        return bitDepth;
    }

    /**
     * Sets the bit depth and persists the change.
     *
     * @param bitDepth bits per sample (must be positive)
     */
    public void setBitDepth(int bitDepth) {
        if (bitDepth <= 0) {
            throw new IllegalArgumentException("bitDepth must be positive: " + bitDepth);
        }
        this.bitDepth = bitDepth;
        prefs.putInt(KEY_BIT_DEPTH, bitDepth);
    }

    /** Returns the configured buffer size in sample frames. */
    public int getBufferSize() {
        return bufferSize;
    }

    /**
     * Sets the buffer size and persists the change.
     *
     * @param bufferSize buffer size in sample frames (must be positive)
     */
    public void setBufferSize(int bufferSize) {
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be positive: " + bufferSize);
        }
        this.bufferSize = bufferSize;
        prefs.putInt(KEY_BUFFER_SIZE, bufferSize);
    }

    /**
     * Returns the preferred audio backend name, or an empty string to let
     * {@code AudioBackendFactory} pick the best available one.
     */
    public String getAudioBackend() {
        return audioBackend;
    }

    /** Sets the preferred audio backend name and persists the change. */
    public void setAudioBackend(String backend) {
        Objects.requireNonNull(backend, "backend must not be null");
        this.audioBackend = backend;
        prefs.put(KEY_AUDIO_BACKEND, backend);
    }

    /**
     * Returns the preferred audio input device name, or an empty string to
     * use the backend default.
     */
    public String getAudioInputDevice() {
        return audioInputDevice;
    }

    /** Sets the preferred audio input device name and persists the change. */
    public void setAudioInputDevice(String deviceName) {
        Objects.requireNonNull(deviceName, "deviceName must not be null");
        this.audioInputDevice = deviceName;
        prefs.put(KEY_AUDIO_INPUT_DEVICE, deviceName);
    }

    /**
     * Returns the preferred audio output device name, or an empty string to
     * use the backend default.
     */
    public String getAudioOutputDevice() {
        return audioOutputDevice;
    }

    /** Sets the preferred audio output device name and persists the change. */
    public void setAudioOutputDevice(String deviceName) {
        Objects.requireNonNull(deviceName, "deviceName must not be null");
        this.audioOutputDevice = deviceName;
        prefs.put(KEY_AUDIO_OUTPUT_DEVICE, deviceName);
    }

    // ── Project ──────────────────────────────────────────────────────────────

    /** Returns the auto-save interval in seconds. */
    public int getAutoSaveIntervalSeconds() {
        return autoSaveIntervalSeconds;
    }

    /**
     * Sets the auto-save interval and persists the change.
     *
     * @param seconds auto-save interval in seconds (must be positive)
     */
    public void setAutoSaveIntervalSeconds(int seconds) {
        if (seconds <= 0) {
            throw new IllegalArgumentException(
                    "autoSaveIntervalSeconds must be positive: " + seconds);
        }
        this.autoSaveIntervalSeconds = seconds;
        prefs.putInt(KEY_AUTO_SAVE_INTERVAL_SECONDS, seconds);
    }

    /** Returns the default project tempo in BPM. */
    public double getDefaultTempo() {
        return defaultTempo;
    }

    /**
     * Sets the default project tempo and persists the change.
     *
     * @param tempo BPM value (must be between 20 and 999)
     */
    public void setDefaultTempo(double tempo) {
        if (tempo < 20.0 || tempo > 999.0) {
            throw new IllegalArgumentException(
                    "defaultTempo must be between 20 and 999 BPM: " + tempo);
        }
        this.defaultTempo = tempo;
        prefs.putDouble(KEY_DEFAULT_TEMPO, tempo);
    }

    // ── Appearance ───────────────────────────────────────────────────────────

    /** Returns the UI scale factor. */
    public double getUiScale() {
        return uiScale;
    }

    /**
     * Sets the UI scale factor and persists the change.
     *
     * @param scale scale factor (must be between 0.5 and 3.0)
     */
    public void setUiScale(double scale) {
        if (scale < 0.5 || scale > 3.0) {
            throw new IllegalArgumentException(
                    "uiScale must be between 0.5 and 3.0: " + scale);
        }
        this.uiScale = scale;
        prefs.putDouble(KEY_UI_SCALE, scale);
    }

    // ── Plugins ──────────────────────────────────────────────────────────────

    /** Returns the plugin scan paths as a semicolon-separated string. */
    public String getPluginScanPaths() {
        return pluginScanPaths;
    }

    /**
     * Sets the plugin scan paths and persists the change.
     *
     * @param paths semicolon-separated paths (must not be {@code null})
     */
    public void setPluginScanPaths(String paths) {
        Objects.requireNonNull(paths, "paths must not be null");
        this.pluginScanPaths = paths;
        prefs.put(KEY_PLUGIN_SCAN_PATHS, paths);
    }

    // ── Key Bindings ────────────────────────────────────────────────────────

    /**
     * Returns the {@link KeyBindingManager} that manages keyboard shortcut
     * customization. The manager is lazily created and backed by a child
     * preferences node so key binding keys do not collide with other settings.
     *
     * @return the key binding manager (never {@code null})
     */
    public KeyBindingManager getKeyBindingManager() {
        if (keyBindingManager == null) {
            keyBindingManager = new KeyBindingManager(prefs.node("keybindings"));
        }
        return keyBindingManager;
    }

    // ── Reset ────────────────────────────────────────────────────────────────

    /**
     * Resets all settings to their default values and persists the changes.
     */
    public void resetToDefaults() {
        setSampleRate(DEFAULT_SAMPLE_RATE);
        setBitDepth(DEFAULT_BIT_DEPTH);
        setBufferSize(DEFAULT_BUFFER_SIZE);
        setAutoSaveIntervalSeconds(DEFAULT_AUTO_SAVE_INTERVAL_SECONDS);
        setDefaultTempo(DEFAULT_TEMPO);
        setUiScale(DEFAULT_UI_SCALE);
        setPluginScanPaths(DEFAULT_PLUGIN_SCAN_PATHS);
        setAudioBackend(DEFAULT_AUDIO_BACKEND);
        setAudioInputDevice(DEFAULT_AUDIO_DEVICE);
        setAudioOutputDevice(DEFAULT_AUDIO_DEVICE);
    }
}

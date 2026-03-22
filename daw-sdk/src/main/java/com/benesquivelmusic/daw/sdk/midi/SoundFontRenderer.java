package com.benesquivelmusic.daw.sdk.midi;

import java.nio.file.Path;
import java.util.List;

/**
 * Interface for SoundFont-based MIDI rendering.
 *
 * <p>Implementations provide MIDI-to-audio synthesis using SoundFont 2 (SF2)
 * instrument libraries. The renderer loads SoundFont files, accepts MIDI
 * events (note on/off, control change, pitch bend, program change), and
 * produces stereo float audio output that can be mixed into the DAW's
 * audio pipeline.</p>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #initialize(double, int)} — set up the synthesizer at the desired sample rate</li>
 *   <li>{@link #loadSoundFont(Path)} — load one or more SF2 files</li>
 *   <li>{@link #selectPreset(int, int, int)} — assign instruments to MIDI channels</li>
 *   <li>{@link #sendEvent(MidiEvent)} — route MIDI events to the synthesizer</li>
 *   <li>{@link #render(float[][], int)} — render audio into the DAW's buffer</li>
 *   <li>{@link #close()} — release all resources</li>
 * </ol>
 *
 * <h2>Thread Safety</h2>
 * <p>The {@link #render(float[][], int)} method may be called on the real-time
 * audio thread. Implementations should ensure that rendering is lock-free
 * and allocation-free after initialization.</p>
 *
 * @see MidiEvent
 * @see SoundFontInfo
 * @see SoundFontPreset
 */
public interface SoundFontRenderer extends AutoCloseable {

    /**
     * Initializes the synthesizer engine.
     *
     * @param sampleRate the audio sample rate in Hz (e.g., 44100, 48000)
     * @param bufferSize the expected buffer size in sample frames
     * @throws SoundFontRendererException if initialization fails
     */
    void initialize(double sampleRate, int bufferSize);

    /**
     * Loads a SoundFont 2 (.sf2) file and returns its metadata.
     *
     * @param path the path to the SF2 file
     * @return information about the loaded SoundFont, including available presets
     * @throws SoundFontRendererException if the file cannot be loaded
     */
    SoundFontInfo loadSoundFont(Path path);

    /**
     * Unloads a previously loaded SoundFont.
     *
     * @param soundFontId the SoundFont identifier returned by {@link #loadSoundFont(Path)}
     * @throws SoundFontRendererException if the SoundFont cannot be unloaded
     */
    void unloadSoundFont(int soundFontId);

    /**
     * Returns information about all currently loaded SoundFonts.
     *
     * @return an unmodifiable list of loaded SoundFont metadata
     */
    List<SoundFontInfo> getLoadedSoundFonts();

    /**
     * Selects a preset (instrument) for the specified MIDI channel.
     *
     * @param channel the MIDI channel (0–15)
     * @param bank    the bank number
     * @param program the program number
     * @throws SoundFontRendererException if the preset cannot be selected
     */
    void selectPreset(int channel, int bank, int program);

    /**
     * Sends a MIDI event to the synthesizer for processing.
     *
     * @param event the MIDI event to send
     * @throws SoundFontRendererException if the event cannot be processed
     */
    void sendEvent(MidiEvent event);

    /**
     * Renders synthesized audio into the provided output buffer.
     *
     * <p>The output buffer is indexed as {@code [channel][frame]} with stereo
     * output (2 channels). Rendered samples are in the range {@code [-1.0f, 1.0f]}
     * and are <em>added</em> to (mixed into) the existing buffer contents.</p>
     *
     * @param outputBuffer the output audio buffer {@code [channel][frame]}
     * @param numFrames    the number of sample frames to render
     */
    void render(float[][] outputBuffer, int numFrames);

    /**
     * Renders a sequence of MIDI events into an offline audio buffer
     * (bounce-to-audio).
     *
     * <p>The events are processed in order. After all events have been sent,
     * the remaining audio (e.g., release tails) is rendered for the specified
     * number of additional tail frames.</p>
     *
     * @param events      the MIDI events to render, in chronological order
     * @param totalFrames the total number of audio frames to render
     * @return stereo audio data {@code [2][totalFrames]}
     */
    float[][] bounce(List<MidiEvent> events, int totalFrames);

    /**
     * Enables or disables the built-in reverb effect.
     *
     * @param enabled {@code true} to enable reverb, {@code false} to disable
     */
    void setReverbEnabled(boolean enabled);

    /**
     * Enables or disables the built-in chorus effect.
     *
     * @param enabled {@code true} to enable chorus, {@code false} to disable
     */
    void setChorusEnabled(boolean enabled);

    /**
     * Sets the master gain (volume) of the synthesizer.
     *
     * @param gain the gain level (0.0 = silent, 1.0 = unity)
     */
    void setGain(float gain);

    /**
     * Returns whether this renderer is available on the current platform.
     *
     * <p>For native renderers (e.g., FluidSynth), this checks whether the
     * native library can be loaded. For the Java Sound fallback, this always
     * returns {@code true}.</p>
     *
     * @return {@code true} if the renderer can be used
     */
    boolean isAvailable();

    /**
     * Returns the human-readable name of this renderer (e.g., "FluidSynth", "Java Sound").
     *
     * @return the renderer name
     */
    String getRendererName();

    /**
     * Sends all-notes-off on all channels, silencing the synthesizer.
     */
    void allNotesOff();

    /**
     * Releases all resources held by this renderer.
     */
    @Override
    void close();
}

package com.benesquivelmusic.daw.core.midi.javasound;

import com.benesquivelmusic.daw.sdk.midi.MidiEvent;
import com.benesquivelmusic.daw.sdk.midi.SoundFontInfo;
import com.benesquivelmusic.daw.sdk.midi.SoundFontPreset;
import com.benesquivelmusic.daw.sdk.midi.SoundFontRenderer;
import com.benesquivelmusic.daw.sdk.midi.SoundFontRendererException;

import javax.sound.midi.Instrument;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Patch;
import javax.sound.midi.Soundbank;
import javax.sound.midi.Synthesizer;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Fallback {@link SoundFontRenderer} implementation using the Java Sound API.
 *
 * <p>This renderer uses {@code javax.sound.midi.Synthesizer} to provide
 * basic MIDI synthesis when the FluidSynth native library is unavailable.
 * The Java Sound API synthesizer uses lower-quality General MIDI samples
 * compared to FluidSynth, but is always available on any Java platform.</p>
 *
 * <p>Limitations compared to FluidSynth:</p>
 * <ul>
 *   <li>Lower quality instrument samples (built-in Java SE General MIDI)</li>
 *   <li>No SoundFont 2 file loading on all platforms (depends on JRE implementation)</li>
 *   <li>Limited effects control</li>
 *   <li>Audio rendering is not available as raw float buffers — this renderer
 *       returns silence for {@link #render(float[][], int)} and {@link #bounce(List, int)}</li>
 * </ul>
 */
public final class JavaSoundRenderer implements SoundFontRenderer {

    private static final int STEREO_CHANNELS = 2;

    private Synthesizer synthesizer;
    private final List<SoundFontInfo> loadedSoundFonts = new ArrayList<>();
    private boolean initialized;
    private int nextSoundFontId;

    @Override
    public void initialize(double sampleRate, int bufferSize) {
        if (initialized) {
            throw new IllegalStateException("Renderer is already initialized");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be positive: " + bufferSize);
        }

        try {
            synthesizer = MidiSystem.getSynthesizer();
            synthesizer.open();
        } catch (MidiUnavailableException e) {
            throw new SoundFontRendererException("Failed to open Java Sound synthesizer", e);
        }

        initialized = true;
    }

    @Override
    public SoundFontInfo loadSoundFont(Path path) {
        ensureInitialized();
        Objects.requireNonNull(path, "path must not be null");

        try {
            File file = path.toFile();
            Soundbank soundbank = MidiSystem.getSoundbank(file);
            boolean loaded = synthesizer.loadAllInstruments(soundbank);
            if (!loaded) {
                throw new SoundFontRendererException(
                        "Java Sound could not load SoundFont: " + path);
            }

            int sfId = nextSoundFontId++;
            List<SoundFontPreset> presets = new ArrayList<>();
            for (Instrument instrument : soundbank.getInstruments()) {
                Patch patch = instrument.getPatch();
                presets.add(new SoundFontPreset(
                        patch.getBank(), patch.getProgram(), instrument.getName()));
            }

            SoundFontInfo info = new SoundFontInfo(sfId, path, presets);
            loadedSoundFonts.add(info);
            return info;
        } catch (IOException | InvalidMidiDataException e) {
            throw new SoundFontRendererException("Failed to load SoundFont: " + path, e);
        }
    }

    @Override
    public void unloadSoundFont(int soundFontId) {
        ensureInitialized();
        loadedSoundFonts.removeIf(sf -> sf.id() == soundFontId);
    }

    @Override
    public List<SoundFontInfo> getLoadedSoundFonts() {
        return Collections.unmodifiableList(loadedSoundFonts);
    }

    @Override
    public void selectPreset(int channel, int bank, int program) {
        ensureInitialized();
        MidiChannel[] channels = synthesizer.getChannels();
        if (channel < 0 || channel >= channels.length) {
            throw new IllegalArgumentException(
                    "channel must be 0–" + (channels.length - 1) + ": " + channel);
        }
        channels[channel].programChange(bank, program);
    }

    @Override
    public void sendEvent(MidiEvent event) {
        ensureInitialized();
        Objects.requireNonNull(event, "event must not be null");

        MidiChannel[] channels = synthesizer.getChannels();
        MidiChannel ch = channels[event.channel()];

        switch (event.type()) {
            case NOTE_ON -> ch.noteOn(event.data1(), event.data2());
            case NOTE_OFF -> ch.noteOff(event.data1());
            case CONTROL_CHANGE -> ch.controlChange(event.data1(), event.data2());
            case PROGRAM_CHANGE -> ch.programChange(event.data1());
            case PITCH_BEND -> ch.setPitchBend(event.data1());
        }
    }

    @Override
    public void render(float[][] outputBuffer, int numFrames) {
        ensureInitialized();
        // Java Sound API does not provide raw float audio output from the synthesizer.
        // Audio is routed through the system's audio output instead.
        // This is a known limitation of the fallback renderer.
    }

    @Override
    public float[][] bounce(List<MidiEvent> events, int totalFrames) {
        ensureInitialized();
        Objects.requireNonNull(events, "events must not be null");

        // Send events for playback through the system audio
        for (MidiEvent event : events) {
            sendEvent(event);
        }

        // Return silence — Java Sound does not support raw audio capture from its synthesizer
        return new float[STEREO_CHANNELS][totalFrames];
    }

    @Override
    public void setReverbEnabled(boolean enabled) {
        // Java Sound API does not expose reverb enable/disable on the synthesizer
    }

    @Override
    public void setChorusEnabled(boolean enabled) {
        // Java Sound API does not expose chorus enable/disable on the synthesizer
    }

    @Override
    public void setGain(float gain) {
        ensureInitialized();
        if (gain < 0.0f) {
            throw new IllegalArgumentException("gain must be non-negative: " + gain);
        }
        // Apply gain via master volume controller (CC 7) on all channels
        int midiVolume = Math.min(127, Math.round(gain * 127.0f));
        for (MidiChannel ch : synthesizer.getChannels()) {
            if (ch != null) {
                ch.controlChange(7, midiVolume);
            }
        }
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String getRendererName() {
        return "Java Sound";
    }

    @Override
    public void allNotesOff() {
        ensureInitialized();
        for (MidiChannel ch : synthesizer.getChannels()) {
            if (ch != null) {
                ch.allNotesOff();
            }
        }
    }

    @Override
    public void close() {
        if (!initialized) {
            return;
        }
        initialized = false;

        if (synthesizer != null && synthesizer.isOpen()) {
            synthesizer.close();
        }
        synthesizer = null;
        loadedSoundFonts.clear();
    }

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("Renderer is not initialized — call initialize() first");
        }
    }
}

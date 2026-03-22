package com.benesquivelmusic.daw.core.midi.fluidsynth;

import com.benesquivelmusic.daw.sdk.midi.MidiEvent;
import com.benesquivelmusic.daw.sdk.midi.SoundFontInfo;
import com.benesquivelmusic.daw.sdk.midi.SoundFontPreset;
import com.benesquivelmusic.daw.sdk.midi.SoundFontRenderer;
import com.benesquivelmusic.daw.sdk.midi.SoundFontRendererException;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * {@link SoundFontRenderer} implementation using FluidSynth via FFM (JEP 454).
 *
 * <p>FluidSynth is the industry-standard open-source SoundFont 2 synthesizer,
 * providing high-quality instrument rendering for MIDI tracks. This renderer
 * delegates all synthesis to the native FluidSynth library via
 * {@link FluidSynthBindings}.</p>
 *
 * <h2>Resource Management</h2>
 * <p>This renderer allocates native memory (FluidSynth settings, synth instance,
 * and audio rendering buffers) that must be released by calling {@link #close()}.
 * Use try-with-resources to ensure proper cleanup.</p>
 */
public final class FluidSynthRenderer implements SoundFontRenderer {

    private static final int STEREO_CHANNELS = 2;

    private final FluidSynthBindings bindings;
    private final List<SoundFontInfo> loadedSoundFonts = new ArrayList<>();

    private Arena renderArena;
    private MemorySegment settings;
    private MemorySegment synth;
    private MemorySegment leftBuffer;
    private MemorySegment rightBuffer;
    private int bufferSize;
    private boolean initialized;

    /**
     * Creates a new FluidSynth renderer.
     *
     * @param bindings the FluidSynth FFM bindings instance
     */
    public FluidSynthRenderer(FluidSynthBindings bindings) {
        this.bindings = Objects.requireNonNull(bindings, "bindings must not be null");
    }

    @Override
    public void initialize(double sampleRate, int bufferSize) {
        if (initialized) {
            throw new IllegalStateException("Renderer is already initialized");
        }
        if (!bindings.isAvailable()) {
            throw new SoundFontRendererException(
                    "FluidSynth native library is not available on this system");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be positive: " + bufferSize);
        }

        this.bufferSize = bufferSize;
        this.renderArena = Arena.ofConfined();

        // Create settings and configure sample rate
        settings = bindings.newSettings();
        if (settings.equals(MemorySegment.NULL)) {
            renderArena.close();
            throw new FluidSynthException("Failed to create FluidSynth settings", FluidSynthBindings.FLUID_FAILED);
        }

        // Set sample rate
        var sampleRateKey = renderArena.allocateFrom("synth.sample-rate");
        bindings.settingsSetNum(settings, sampleRateKey, sampleRate);

        // Disable audio driver (we render manually via write_float)
        var audioDriverKey = renderArena.allocateFrom("audio.driver");
        var noneValue = renderArena.allocateFrom("none");
        bindings.settingsSetStr(settings, audioDriverKey, noneValue);

        // Create synth
        synth = bindings.newSynth(settings);
        if (synth.equals(MemorySegment.NULL)) {
            bindings.deleteSettings(settings);
            renderArena.close();
            throw new FluidSynthException("Failed to create FluidSynth instance", FluidSynthBindings.FLUID_FAILED);
        }

        // Pre-allocate render buffers
        long bufferBytes = (long) bufferSize * ValueLayout.JAVA_FLOAT.byteSize();
        leftBuffer = renderArena.allocate(ValueLayout.JAVA_FLOAT, bufferSize);
        rightBuffer = renderArena.allocate(ValueLayout.JAVA_FLOAT, bufferSize);

        initialized = true;
    }

    @Override
    public SoundFontInfo loadSoundFont(Path path) {
        ensureInitialized();
        Objects.requireNonNull(path, "path must not be null");

        var filenameSeg = renderArena.allocateFrom(path.toAbsolutePath().toString());
        int sfId = bindings.synthSfload(synth, filenameSeg, 1);
        FluidSynthException.checkResult(sfId, "fluid_synth_sfload");

        // Enumerate presets
        List<SoundFontPreset> presets = enumeratePresets(sfId);
        var info = new SoundFontInfo(sfId, path, presets);
        loadedSoundFonts.add(info);
        return info;
    }

    @Override
    public void unloadSoundFont(int soundFontId) {
        ensureInitialized();
        int result = bindings.synthSfunload(synth, soundFontId, 1);
        FluidSynthException.checkResult(result, "fluid_synth_sfunload");
        loadedSoundFonts.removeIf(sf -> sf.id() == soundFontId);
    }

    @Override
    public List<SoundFontInfo> getLoadedSoundFonts() {
        return Collections.unmodifiableList(loadedSoundFonts);
    }

    @Override
    public void selectPreset(int channel, int bank, int program) {
        ensureInitialized();
        validateChannel(channel);
        int result = bindings.synthBankSelect(synth, channel, bank);
        FluidSynthException.checkResult(result, "fluid_synth_bank_select");
        result = bindings.synthProgramChange(synth, channel, program);
        FluidSynthException.checkResult(result, "fluid_synth_program_change");
    }

    @Override
    public void sendEvent(MidiEvent event) {
        ensureInitialized();
        Objects.requireNonNull(event, "event must not be null");

        int result = switch (event.type()) {
            case NOTE_ON -> bindings.synthNoteon(synth, event.channel(), event.data1(), event.data2());
            case NOTE_OFF -> bindings.synthNoteoff(synth, event.channel(), event.data1());
            case CONTROL_CHANGE -> bindings.synthCc(synth, event.channel(), event.data1(), event.data2());
            case PROGRAM_CHANGE -> bindings.synthProgramChange(synth, event.channel(), event.data1());
            case PITCH_BEND -> bindings.synthPitchBend(synth, event.channel(), event.data1());
        };
        FluidSynthException.checkResult(result, "sendEvent(" + event.type() + ")");
    }

    @Override
    public void render(float[][] outputBuffer, int numFrames) {
        ensureInitialized();
        if (outputBuffer == null || outputBuffer.length < STEREO_CHANNELS) {
            throw new IllegalArgumentException("outputBuffer must have at least 2 channels");
        }
        if (numFrames <= 0 || numFrames > bufferSize) {
            throw new IllegalArgumentException(
                    "numFrames must be 1–" + bufferSize + ": " + numFrames);
        }

        int result = bindings.synthWriteFloat(synth, numFrames,
                leftBuffer, 0, 1, rightBuffer, 0, 1);
        FluidSynthException.checkResult(result, "fluid_synth_write_float");

        // Copy rendered audio into output (additive mix)
        for (int i = 0; i < numFrames; i++) {
            outputBuffer[0][i] += leftBuffer.getAtIndex(ValueLayout.JAVA_FLOAT, i);
            outputBuffer[1][i] += rightBuffer.getAtIndex(ValueLayout.JAVA_FLOAT, i);
        }
    }

    @Override
    public float[][] bounce(List<MidiEvent> events, int totalFrames) {
        ensureInitialized();
        Objects.requireNonNull(events, "events must not be null");
        if (totalFrames <= 0) {
            throw new IllegalArgumentException("totalFrames must be positive: " + totalFrames);
        }

        float[][] output = new float[STEREO_CHANNELS][totalFrames];

        // Send all events then render the full duration
        for (MidiEvent event : events) {
            sendEvent(event);
        }

        int framesRendered = 0;
        while (framesRendered < totalFrames) {
            int chunk = Math.min(bufferSize, totalFrames - framesRendered);
            int result = bindings.synthWriteFloat(synth, chunk,
                    leftBuffer, 0, 1, rightBuffer, 0, 1);
            FluidSynthException.checkResult(result, "fluid_synth_write_float (bounce)");

            for (int i = 0; i < chunk; i++) {
                output[0][framesRendered + i] = leftBuffer.getAtIndex(ValueLayout.JAVA_FLOAT, i);
                output[1][framesRendered + i] = rightBuffer.getAtIndex(ValueLayout.JAVA_FLOAT, i);
            }
            framesRendered += chunk;
        }

        return output;
    }

    @Override
    public void setReverbEnabled(boolean enabled) {
        ensureInitialized();
        bindings.synthSetReverbOn(synth, enabled ? 1 : 0);
    }

    @Override
    public void setChorusEnabled(boolean enabled) {
        ensureInitialized();
        bindings.synthSetChorusOn(synth, enabled ? 1 : 0);
    }

    @Override
    public void setGain(float gain) {
        ensureInitialized();
        if (gain < 0.0f) {
            throw new IllegalArgumentException("gain must be non-negative: " + gain);
        }
        bindings.synthSetGain(synth, gain);
    }

    @Override
    public boolean isAvailable() {
        return bindings.isAvailable();
    }

    @Override
    public String getRendererName() {
        return "FluidSynth";
    }

    @Override
    public void allNotesOff() {
        ensureInitialized();
        for (int ch = 0; ch < MidiEvent.MAX_CHANNELS; ch++) {
            bindings.synthAllNotesOff(synth, ch);
        }
    }

    @Override
    public void close() {
        if (!initialized) {
            return;
        }
        initialized = false;

        if (synth != null && !synth.equals(MemorySegment.NULL)) {
            bindings.synthSystemReset(synth);
            bindings.deleteSynth(synth);
            synth = null;
        }
        if (settings != null && !settings.equals(MemorySegment.NULL)) {
            bindings.deleteSettings(settings);
            settings = null;
        }
        if (renderArena != null) {
            renderArena.close();
            renderArena = null;
        }
        loadedSoundFonts.clear();
    }

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("Renderer is not initialized — call initialize() first");
        }
    }

    private static void validateChannel(int channel) {
        if (channel < 0 || channel >= MidiEvent.MAX_CHANNELS) {
            throw new IllegalArgumentException(
                    "channel must be 0–" + (MidiEvent.MAX_CHANNELS - 1) + ": " + channel);
        }
    }

    private List<SoundFontPreset> enumeratePresets(int sfId) {
        List<SoundFontPreset> presets = new ArrayList<>();
        MemorySegment sfont = bindings.synthGetSfontById(synth, sfId);
        if (sfont.equals(MemorySegment.NULL)) {
            return presets;
        }
        sfont = sfont.reinterpret(Long.MAX_VALUE);

        bindings.sfontIterationStart(sfont);
        while (true) {
            MemorySegment preset = bindings.sfontIterationNext(sfont);
            if (preset.equals(MemorySegment.NULL)) {
                break;
            }
            preset = preset.reinterpret(Long.MAX_VALUE);

            int bank = bindings.presetGetBanknum(preset);
            int program = bindings.presetGetNum(preset);
            MemorySegment namePtr = bindings.presetGetName(preset);
            String name = "Preset " + bank + ":" + program;
            if (!namePtr.equals(MemorySegment.NULL)) {
                name = namePtr.reinterpret(256).getString(0);
            }
            presets.add(new SoundFontPreset(bank, program, name));
        }
        return presets;
    }
}

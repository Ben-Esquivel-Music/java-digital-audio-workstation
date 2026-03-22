package com.benesquivelmusic.daw.core.midi.fluidsynth;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Optional;

/**
 * Raw FFM (Foreign Function &amp; Memory API — JEP 454) bindings for the
 * FluidSynth C library.
 *
 * <p>This class provides thin Java wrappers around FluidSynth functions using
 * {@link Linker} and {@link SymbolLookup} to locate and invoke native symbols
 * at runtime — no JNI or generated code required.</p>
 *
 * <h2>Supported Functions</h2>
 * <ul>
 *   <li>Settings: {@code new_fluid_settings}, {@code delete_fluid_settings},
 *       {@code fluid_settings_setstr}, {@code fluid_settings_setnum},
 *       {@code fluid_settings_setint}</li>
 *   <li>Synth lifecycle: {@code new_fluid_synth}, {@code delete_fluid_synth}</li>
 *   <li>SoundFont: {@code fluid_synth_sfload}, {@code fluid_synth_sfunload}</li>
 *   <li>MIDI events: {@code fluid_synth_noteon}, {@code fluid_synth_noteoff},
 *       {@code fluid_synth_cc}, {@code fluid_synth_pitch_bend},
 *       {@code fluid_synth_program_change}, {@code fluid_synth_bank_select},
 *       {@code fluid_synth_program_select}</li>
 *   <li>Rendering: {@code fluid_synth_write_float}</li>
 *   <li>Effects: {@code fluid_synth_set_reverb_on}, {@code fluid_synth_set_chorus_on}</li>
 *   <li>Gain: {@code fluid_synth_set_gain}, {@code fluid_synth_get_gain}</li>
 *   <li>Control: {@code fluid_synth_all_notes_off}, {@code fluid_synth_system_reset}</li>
 *   <li>SoundFont iteration: {@code fluid_synth_get_sfont_by_id},
 *       {@code fluid_sfont_iteration_start}, {@code fluid_sfont_iteration_next},
 *       {@code fluid_preset_get_name}, {@code fluid_preset_get_banknum},
 *       {@code fluid_preset_get_num}</li>
 * </ul>
 *
 * <h2>Native Library Loading</h2>
 * <p>The FluidSynth shared library ({@code libfluidsynth.so}, {@code libfluidsynth.dylib},
 * or {@code fluidsynth.dll}) must be on the system library path. Use
 * {@link #isAvailable()} to check before calling any other method.</p>
 *
 * @see <a href="https://www.fluidsynth.org/api/">FluidSynth API Reference</a>
 */
public final class FluidSynthBindings {

    /** FluidSynth success return code. */
    public static final int FLUID_OK = 0;

    /** FluidSynth failure return code. */
    public static final int FLUID_FAILED = -1;

    /** Number of MIDI channels supported. */
    public static final int MIDI_CHANNELS = 16;

    private static final Linker LINKER = Linker.nativeLinker();

    private final SymbolLookup lookup;
    private final Arena arena;
    private final boolean available;

    // Settings management
    private MethodHandle newFluidSettings;
    private MethodHandle deleteFluidSettings;
    private MethodHandle fluidSettingsSetstr;
    private MethodHandle fluidSettingsSetnum;
    private MethodHandle fluidSettingsSetint;

    // Synth lifecycle
    private MethodHandle newFluidSynth;
    private MethodHandle deleteFluidSynth;

    // SoundFont management
    private MethodHandle fluidSynthSfload;
    private MethodHandle fluidSynthSfunload;

    // MIDI events
    private MethodHandle fluidSynthNoteon;
    private MethodHandle fluidSynthNoteoff;
    private MethodHandle fluidSynthCc;
    private MethodHandle fluidSynthPitchBend;
    private MethodHandle fluidSynthProgramChange;
    private MethodHandle fluidSynthBankSelect;
    private MethodHandle fluidSynthProgramSelect;

    // Audio rendering
    private MethodHandle fluidSynthWriteFloat;

    // Effects control
    private MethodHandle fluidSynthSetReverbOn;
    private MethodHandle fluidSynthSetChorusOn;

    // Gain control
    private MethodHandle fluidSynthSetGain;
    private MethodHandle fluidSynthGetGain;

    // All notes off / system reset
    private MethodHandle fluidSynthAllNotesOff;
    private MethodHandle fluidSynthSystemReset;

    // SoundFont preset iteration
    private MethodHandle fluidSynthGetSfontById;
    private MethodHandle fluidSfontIterationStart;
    private MethodHandle fluidSfontIterationNext;
    private MethodHandle fluidPresetGetName;
    private MethodHandle fluidPresetGetBanknum;
    private MethodHandle fluidPresetGetNum;

    /**
     * Creates bindings for the FluidSynth native library.
     *
     * <p>If the native library cannot be loaded, {@link #isAvailable()} returns
     * {@code false} and no other methods should be called.</p>
     */
    public FluidSynthBindings() {
        SymbolLookup tempLookup = null;
        boolean tempAvailable = false;
        Arena tempArena = Arena.ofAuto();

        try {
            tempLookup = SymbolLookup.libraryLookup(resolveLibraryName(), tempArena);
            tempAvailable = true;
        } catch (IllegalArgumentException | UnsatisfiedLinkError _) {
            // Native library not found — expected on systems without FluidSynth
        }

        this.lookup = tempLookup;
        this.arena = tempArena;
        this.available = tempAvailable;

        if (available) {
            bindFunctions();
        }
    }

    /**
     * Returns whether the FluidSynth native library is available.
     *
     * @return true if the library was loaded successfully
     */
    public boolean isAvailable() {
        return available;
    }

    // ---- Settings ----

    /**
     * Calls {@code new_fluid_settings()}.
     *
     * @return a pointer to the new settings object, or {@code MemorySegment.NULL} on failure
     */
    public MemorySegment newSettings() {
        try {
            return (MemorySegment) newFluidSettings.invokeExact();
        } catch (Throwable e) {
            throw new FluidSynthException("new_fluid_settings invocation failed", FLUID_FAILED, e);
        }
    }

    /**
     * Calls {@code delete_fluid_settings(settings)}.
     *
     * @param settings the settings pointer
     */
    public void deleteSettings(MemorySegment settings) {
        try {
            deleteFluidSettings.invokeExact(settings);
        } catch (Throwable e) {
            throw new FluidSynthException("delete_fluid_settings invocation failed", FLUID_FAILED, e);
        }
    }

    /**
     * Calls {@code fluid_settings_setstr(settings, name, str)}.
     *
     * @param settings the settings pointer
     * @param name     the setting name
     * @param value    the string value
     * @return {@link #FLUID_OK} on success, {@link #FLUID_FAILED} on failure
     */
    public int settingsSetStr(MemorySegment settings, MemorySegment name, MemorySegment value) {
        try {
            return (int) fluidSettingsSetstr.invokeExact(settings, name, value);
        } catch (Throwable e) {
            throw new FluidSynthException("fluid_settings_setstr invocation failed", FLUID_FAILED, e);
        }
    }

    /**
     * Calls {@code fluid_settings_setnum(settings, name, val)}.
     *
     * @param settings the settings pointer
     * @param name     the setting name
     * @param value    the numeric value
     * @return {@link #FLUID_OK} on success, {@link #FLUID_FAILED} on failure
     */
    public int settingsSetNum(MemorySegment settings, MemorySegment name, double value) {
        try {
            return (int) fluidSettingsSetnum.invokeExact(settings, name, value);
        } catch (Throwable e) {
            throw new FluidSynthException("fluid_settings_setnum invocation failed", FLUID_FAILED, e);
        }
    }

    /**
     * Calls {@code fluid_settings_setint(settings, name, val)}.
     *
     * @param settings the settings pointer
     * @param name     the setting name
     * @param value    the integer value
     * @return {@link #FLUID_OK} on success, {@link #FLUID_FAILED} on failure
     */
    public int settingsSetInt(MemorySegment settings, MemorySegment name, int value) {
        try {
            return (int) fluidSettingsSetint.invokeExact(settings, name, value);
        } catch (Throwable e) {
            throw new FluidSynthException("fluid_settings_setint invocation failed", FLUID_FAILED, e);
        }
    }

    // ---- Synth lifecycle ----

    /**
     * Calls {@code new_fluid_synth(settings)}.
     *
     * @param settings the settings pointer
     * @return a pointer to the new synth instance, or {@code MemorySegment.NULL} on failure
     */
    public MemorySegment newSynth(MemorySegment settings) {
        try {
            return (MemorySegment) newFluidSynth.invokeExact(settings);
        } catch (Throwable e) {
            throw new FluidSynthException("new_fluid_synth invocation failed", FLUID_FAILED, e);
        }
    }

    /**
     * Calls {@code delete_fluid_synth(synth)}.
     *
     * @param synth the synth pointer
     */
    public void deleteSynth(MemorySegment synth) {
        try {
            deleteFluidSynth.invokeExact(synth);
        } catch (Throwable e) {
            throw new FluidSynthException("delete_fluid_synth invocation failed", FLUID_FAILED, e);
        }
    }

    // ---- SoundFont management ----

    /**
     * Calls {@code fluid_synth_sfload(synth, filename, resetPresets)}.
     *
     * @param synth        the synth pointer
     * @param filename     the SoundFont file path as a C string
     * @param resetPresets 1 to reset presets on all channels, 0 to keep current
     * @return the SoundFont ID on success, or {@link #FLUID_FAILED}
     */
    public int synthSfload(MemorySegment synth, MemorySegment filename, int resetPresets) {
        try {
            return (int) fluidSynthSfload.invokeExact(synth, filename, resetPresets);
        } catch (Throwable e) {
            throw new FluidSynthException("fluid_synth_sfload invocation failed", FLUID_FAILED, e);
        }
    }

    /**
     * Calls {@code fluid_synth_sfunload(synth, id, resetPresets)}.
     *
     * @param synth        the synth pointer
     * @param id           the SoundFont ID
     * @param resetPresets 1 to reset presets on all channels, 0 to keep current
     * @return {@link #FLUID_OK} on success, {@link #FLUID_FAILED} on failure
     */
    public int synthSfunload(MemorySegment synth, int id, int resetPresets) {
        try {
            return (int) fluidSynthSfunload.invokeExact(synth, id, resetPresets);
        } catch (Throwable e) {
            throw new FluidSynthException("fluid_synth_sfunload invocation failed", FLUID_FAILED, e);
        }
    }

    // ---- MIDI events ----

    /**
     * Calls {@code fluid_synth_noteon(synth, chan, key, vel)}.
     *
     * @param synth    the synth pointer
     * @param channel  the MIDI channel (0–15)
     * @param key      the MIDI note number (0–127)
     * @param velocity the note velocity (0–127)
     * @return {@link #FLUID_OK} on success, {@link #FLUID_FAILED} on failure
     */
    public int synthNoteon(MemorySegment synth, int channel, int key, int velocity) {
        try {
            return (int) fluidSynthNoteon.invokeExact(synth, channel, key, velocity);
        } catch (Throwable e) {
            throw new FluidSynthException("fluid_synth_noteon invocation failed", FLUID_FAILED, e);
        }
    }

    /**
     * Calls {@code fluid_synth_noteoff(synth, chan, key)}.
     *
     * @param synth   the synth pointer
     * @param channel the MIDI channel (0–15)
     * @param key     the MIDI note number (0–127)
     * @return {@link #FLUID_OK} on success, {@link #FLUID_FAILED} on failure
     */
    public int synthNoteoff(MemorySegment synth, int channel, int key) {
        try {
            return (int) fluidSynthNoteoff.invokeExact(synth, channel, key);
        } catch (Throwable e) {
            throw new FluidSynthException("fluid_synth_noteoff invocation failed", FLUID_FAILED, e);
        }
    }

    /**
     * Calls {@code fluid_synth_cc(synth, chan, ctrl, val)}.
     *
     * @param synth      the synth pointer
     * @param channel    the MIDI channel (0–15)
     * @param controller the controller number (0–127)
     * @param value      the controller value (0–127)
     * @return {@link #FLUID_OK} on success, {@link #FLUID_FAILED} on failure
     */
    public int synthCc(MemorySegment synth, int channel, int controller, int value) {
        try {
            return (int) fluidSynthCc.invokeExact(synth, channel, controller, value);
        } catch (Throwable e) {
            throw new FluidSynthException("fluid_synth_cc invocation failed", FLUID_FAILED, e);
        }
    }

    /**
     * Calls {@code fluid_synth_pitch_bend(synth, chan, val)}.
     *
     * @param synth   the synth pointer
     * @param channel the MIDI channel (0–15)
     * @param value   the pitch-bend value (0–16383; 8192 = center)
     * @return {@link #FLUID_OK} on success, {@link #FLUID_FAILED} on failure
     */
    public int synthPitchBend(MemorySegment synth, int channel, int value) {
        try {
            return (int) fluidSynthPitchBend.invokeExact(synth, channel, value);
        } catch (Throwable e) {
            throw new FluidSynthException("fluid_synth_pitch_bend invocation failed", FLUID_FAILED, e);
        }
    }

    /**
     * Calls {@code fluid_synth_program_change(synth, chan, program)}.
     *
     * @param synth   the synth pointer
     * @param channel the MIDI channel (0–15)
     * @param program the program number (0–127)
     * @return {@link #FLUID_OK} on success, {@link #FLUID_FAILED} on failure
     */
    public int synthProgramChange(MemorySegment synth, int channel, int program) {
        try {
            return (int) fluidSynthProgramChange.invokeExact(synth, channel, program);
        } catch (Throwable e) {
            throw new FluidSynthException("fluid_synth_program_change invocation failed", FLUID_FAILED, e);
        }
    }

    /**
     * Calls {@code fluid_synth_bank_select(synth, chan, bank)}.
     *
     * @param synth   the synth pointer
     * @param channel the MIDI channel (0–15)
     * @param bank    the bank number
     * @return {@link #FLUID_OK} on success, {@link #FLUID_FAILED} on failure
     */
    public int synthBankSelect(MemorySegment synth, int channel, int bank) {
        try {
            return (int) fluidSynthBankSelect.invokeExact(synth, channel, bank);
        } catch (Throwable e) {
            throw new FluidSynthException("fluid_synth_bank_select invocation failed", FLUID_FAILED, e);
        }
    }

    /**
     * Calls {@code fluid_synth_program_select(synth, chan, sfontId, bank, preset)}.
     *
     * @param synth    the synth pointer
     * @param channel  the MIDI channel (0–15)
     * @param sfontId  the SoundFont ID
     * @param bank     the bank number
     * @param program  the program (preset) number
     * @return {@link #FLUID_OK} on success, {@link #FLUID_FAILED} on failure
     */
    public int synthProgramSelect(MemorySegment synth, int channel, int sfontId, int bank, int program) {
        try {
            return (int) fluidSynthProgramSelect.invokeExact(synth, channel, sfontId, bank, program);
        } catch (Throwable e) {
            throw new FluidSynthException("fluid_synth_program_select invocation failed", FLUID_FAILED, e);
        }
    }

    // ---- Audio rendering ----

    /**
     * Calls {@code fluid_synth_write_float(synth, len, lout, loff, lincr, rout, roff, rincr)}.
     *
     * <p>Renders {@code len} frames of audio into the left and right output buffers.</p>
     *
     * @param synth the synth pointer
     * @param len   the number of frames to render
     * @param lout  the left output buffer
     * @param loff  the offset into the left buffer (in floats)
     * @param lincr the increment between left samples (typically 1)
     * @param rout  the right output buffer
     * @param roff  the offset into the right buffer (in floats)
     * @param rincr the increment between right samples (typically 1)
     * @return {@link #FLUID_OK} on success, {@link #FLUID_FAILED} on failure
     */
    public int synthWriteFloat(MemorySegment synth, int len,
                               MemorySegment lout, int loff, int lincr,
                               MemorySegment rout, int roff, int rincr) {
        try {
            return (int) fluidSynthWriteFloat.invokeExact(synth, len,
                    lout, loff, lincr, rout, roff, rincr);
        } catch (Throwable e) {
            throw new FluidSynthException("fluid_synth_write_float invocation failed", FLUID_FAILED, e);
        }
    }

    // ---- Effects control ----

    /**
     * Calls {@code fluid_synth_set_reverb_on(synth, on)}.
     *
     * @param synth the synth pointer
     * @param on    1 to enable reverb, 0 to disable
     */
    public void synthSetReverbOn(MemorySegment synth, int on) {
        try {
            fluidSynthSetReverbOn.invokeExact(synth, on);
        } catch (Throwable e) {
            throw new FluidSynthException("fluid_synth_set_reverb_on invocation failed", FLUID_FAILED, e);
        }
    }

    /**
     * Calls {@code fluid_synth_set_chorus_on(synth, on)}.
     *
     * @param synth the synth pointer
     * @param on    1 to enable chorus, 0 to disable
     */
    public void synthSetChorusOn(MemorySegment synth, int on) {
        try {
            fluidSynthSetChorusOn.invokeExact(synth, on);
        } catch (Throwable e) {
            throw new FluidSynthException("fluid_synth_set_chorus_on invocation failed", FLUID_FAILED, e);
        }
    }

    // ---- Gain control ----

    /**
     * Calls {@code fluid_synth_set_gain(synth, gain)}.
     *
     * @param synth the synth pointer
     * @param gain  the master gain (0.0–10.0)
     */
    public void synthSetGain(MemorySegment synth, float gain) {
        try {
            fluidSynthSetGain.invokeExact(synth, gain);
        } catch (Throwable e) {
            throw new FluidSynthException("fluid_synth_set_gain invocation failed", FLUID_FAILED, e);
        }
    }

    /**
     * Calls {@code fluid_synth_get_gain(synth)}.
     *
     * @param synth the synth pointer
     * @return the current master gain
     */
    public float synthGetGain(MemorySegment synth) {
        try {
            return (float) fluidSynthGetGain.invokeExact(synth);
        } catch (Throwable e) {
            throw new FluidSynthException("fluid_synth_get_gain invocation failed", FLUID_FAILED, e);
        }
    }

    // ---- All notes off / system reset ----

    /**
     * Calls {@code fluid_synth_all_notes_off(synth, chan)}.
     *
     * @param synth   the synth pointer
     * @param channel the MIDI channel (0–15), or -1 for all channels
     * @return {@link #FLUID_OK} on success, {@link #FLUID_FAILED} on failure
     */
    public int synthAllNotesOff(MemorySegment synth, int channel) {
        try {
            return (int) fluidSynthAllNotesOff.invokeExact(synth, channel);
        } catch (Throwable e) {
            throw new FluidSynthException("fluid_synth_all_notes_off invocation failed", FLUID_FAILED, e);
        }
    }

    /**
     * Calls {@code fluid_synth_system_reset(synth)}.
     *
     * @param synth the synth pointer
     * @return {@link #FLUID_OK} on success, {@link #FLUID_FAILED} on failure
     */
    public int synthSystemReset(MemorySegment synth) {
        try {
            return (int) fluidSynthSystemReset.invokeExact(synth);
        } catch (Throwable e) {
            throw new FluidSynthException("fluid_synth_system_reset invocation failed", FLUID_FAILED, e);
        }
    }

    // ---- SoundFont preset iteration ----

    /**
     * Calls {@code fluid_synth_get_sfont_by_id(synth, id)}.
     *
     * @param synth the synth pointer
     * @param id    the SoundFont ID
     * @return a pointer to the SoundFont, or {@code MemorySegment.NULL}
     */
    public MemorySegment synthGetSfontById(MemorySegment synth, int id) {
        try {
            return (MemorySegment) fluidSynthGetSfontById.invokeExact(synth, id);
        } catch (Throwable e) {
            throw new FluidSynthException("fluid_synth_get_sfont_by_id invocation failed", FLUID_FAILED, e);
        }
    }

    /**
     * Calls {@code fluid_sfont_iteration_start(sfont)}.
     *
     * @param sfont the SoundFont pointer
     */
    public void sfontIterationStart(MemorySegment sfont) {
        try {
            fluidSfontIterationStart.invokeExact(sfont);
        } catch (Throwable e) {
            throw new FluidSynthException("fluid_sfont_iteration_start invocation failed", FLUID_FAILED, e);
        }
    }

    /**
     * Calls {@code fluid_sfont_iteration_next(sfont)}.
     *
     * @param sfont the SoundFont pointer
     * @return a pointer to the next preset, or {@code MemorySegment.NULL} when exhausted
     */
    public MemorySegment sfontIterationNext(MemorySegment sfont) {
        try {
            return (MemorySegment) fluidSfontIterationNext.invokeExact(sfont);
        } catch (Throwable e) {
            throw new FluidSynthException("fluid_sfont_iteration_next invocation failed", FLUID_FAILED, e);
        }
    }

    /**
     * Calls {@code fluid_preset_get_name(preset)}.
     *
     * @param preset the preset pointer
     * @return a pointer to the preset name string
     */
    public MemorySegment presetGetName(MemorySegment preset) {
        try {
            return (MemorySegment) fluidPresetGetName.invokeExact(preset);
        } catch (Throwable e) {
            throw new FluidSynthException("fluid_preset_get_name invocation failed", FLUID_FAILED, e);
        }
    }

    /**
     * Calls {@code fluid_preset_get_banknum(preset)}.
     *
     * @param preset the preset pointer
     * @return the bank number
     */
    public int presetGetBanknum(MemorySegment preset) {
        try {
            return (int) fluidPresetGetBanknum.invokeExact(preset);
        } catch (Throwable e) {
            throw new FluidSynthException("fluid_preset_get_banknum invocation failed", FLUID_FAILED, e);
        }
    }

    /**
     * Calls {@code fluid_preset_get_num(preset)}.
     *
     * @param preset the preset pointer
     * @return the program number
     */
    public int presetGetNum(MemorySegment preset) {
        try {
            return (int) fluidPresetGetNum.invokeExact(preset);
        } catch (Throwable e) {
            throw new FluidSynthException("fluid_preset_get_num invocation failed", FLUID_FAILED, e);
        }
    }

    // ---- Internal helpers ----

    private void bindFunctions() {
        // Settings
        newFluidSettings = downcallHandle("new_fluid_settings",
                FunctionDescriptor.of(ValueLayout.ADDRESS));
        deleteFluidSettings = downcallHandle("delete_fluid_settings",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        fluidSettingsSetstr = downcallHandle("fluid_settings_setstr",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        fluidSettingsSetnum = downcallHandle("fluid_settings_setnum",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_DOUBLE));
        fluidSettingsSetint = downcallHandle("fluid_settings_setint",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

        // Synth lifecycle
        newFluidSynth = downcallHandle("new_fluid_synth",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        deleteFluidSynth = downcallHandle("delete_fluid_synth",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

        // SoundFont management
        fluidSynthSfload = downcallHandle("fluid_synth_sfload",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        fluidSynthSfunload = downcallHandle("fluid_synth_sfunload",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

        // MIDI events
        fluidSynthNoteon = downcallHandle("fluid_synth_noteon",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        fluidSynthNoteoff = downcallHandle("fluid_synth_noteoff",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        fluidSynthCc = downcallHandle("fluid_synth_cc",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        fluidSynthPitchBend = downcallHandle("fluid_synth_pitch_bend",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        fluidSynthProgramChange = downcallHandle("fluid_synth_program_change",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        fluidSynthBankSelect = downcallHandle("fluid_synth_bank_select",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        fluidSynthProgramSelect = downcallHandle("fluid_synth_program_select",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

        // Audio rendering
        fluidSynthWriteFloat = downcallHandle("fluid_synth_write_float",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

        // Effects control (void return)
        fluidSynthSetReverbOn = downcallHandle("fluid_synth_set_reverb_on",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        fluidSynthSetChorusOn = downcallHandle("fluid_synth_set_chorus_on",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

        // Gain control
        fluidSynthSetGain = downcallHandle("fluid_synth_set_gain",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_FLOAT));
        fluidSynthGetGain = downcallHandle("fluid_synth_get_gain",
                FunctionDescriptor.of(ValueLayout.JAVA_FLOAT, ValueLayout.ADDRESS));

        // All notes off / system reset
        fluidSynthAllNotesOff = downcallHandle("fluid_synth_all_notes_off",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        fluidSynthSystemReset = downcallHandle("fluid_synth_system_reset",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        // SoundFont preset iteration
        fluidSynthGetSfontById = downcallHandle("fluid_synth_get_sfont_by_id",
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        fluidSfontIterationStart = downcallHandle("fluid_sfont_iteration_start",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        fluidSfontIterationNext = downcallHandle("fluid_sfont_iteration_next",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        fluidPresetGetName = downcallHandle("fluid_preset_get_name",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        fluidPresetGetBanknum = downcallHandle("fluid_preset_get_banknum",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        fluidPresetGetNum = downcallHandle("fluid_preset_get_num",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    }

    private MethodHandle downcallHandle(String name, FunctionDescriptor descriptor) {
        Optional<MemorySegment> symbol = lookup.find(name);
        if (symbol.isEmpty()) {
            throw new FluidSynthException("Symbol not found: " + name, FLUID_FAILED);
        }
        return LINKER.downcallHandle(symbol.get(), descriptor);
    }

    private static String resolveLibraryName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "fluidsynth";
        } else if (os.contains("mac")) {
            return "libfluidsynth.dylib";
        } else {
            return "libfluidsynth.so";
        }
    }
}

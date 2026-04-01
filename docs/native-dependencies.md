# Native C Dependencies

External C libraries used at runtime via the **Foreign Function & Memory API** (FFM, JEP 454). No JNI is required — all native interop is handled through `java.lang.foreign`.

Each dependency is optional. The DAW gracefully degrades or falls back to pure-Java alternatives when a native library is unavailable.

---

## 1. PortAudio

| | |
|---|---|
| **Purpose** | Low-latency, cross-platform audio I/O (device enumeration, stream open/start/stop) |
| **Used in** | `daw-core` — `PortAudioBindings`, `PortAudioBackend` |
| **Library files** | `libportaudio.so` (Linux), `libportaudio.dylib` (macOS), `portaudio.dll` (Windows) |
| **Fallback** | `JavaSoundBackend` (Java Sound API) |
| **Source** | <https://github.com/PortAudio/portaudio> |
| **Website** | <http://www.portaudio.com/> |

**Install:**

```bash
# Linux (Debian/Ubuntu)
sudo apt install libportaudio2

# Linux (Fedora/RHEL)
sudo dnf install portaudio

# macOS
brew install portaudio
```

Windows: pre-built binaries available from <http://www.portaudio.com/download.html>.

---

## 2. FluidSynth

| | |
|---|---|
| **Purpose** | SoundFont-based MIDI synthesis (settings, synth lifecycle, note-on/off, program change, rendering, reverb/chorus effects) |
| **Used in** | `daw-core` — `FluidSynthBindings`, `FluidSynthRenderer` |
| **Library files** | `libfluidsynth.so` (Linux), `libfluidsynth.dylib` (macOS), `fluidsynth.dll` (Windows) |
| **Fallback** | `JavaSoundRenderer` (Java Sound MIDI) |
| **Source** | <https://github.com/FluidSynth/fluidsynth> |
| **Website** | <https://www.fluidsynth.org/> |

**Install:**

```bash
# Linux (Debian/Ubuntu)
sudo apt install libfluidsynth-dev

# Linux (Fedora/RHEL)
sudo dnf install fluidsynth

# macOS
brew install fluid-synth
```

Windows: installers at <https://github.com/FluidSynth/fluidsynth/releases>.

---

## 3. CLAP (CLever Audio Plugin)

| | |
|---|---|
| **Purpose** | Loading and hosting third-party CLAP audio effect/instrument plugins |
| **Used in** | `daw-core` — `ClapBindings`, `ClapPluginHost`, `ClapPluginScanner` |
| **Library files** | User-installed `.clap` bundles (platform-specific shared libraries) |
| **Fallback** | Java-based `DawPlugin` implementations loaded from JAR files |
| **Specification** | <https://github.com/free-audio/clap> |
| **Plugin directory** | <https://cleveraudio.org/> |

CLAP plugins are distributed individually by their respective authors. The CLAP specification and SDK are open-source.

---

## 4. RoomAcoustiC++

| | |
|---|---|
| **Purpose** | Native room acoustic impulse-response simulation (hybrid image-source / FDN model for real-time 3D reverb) |
| **Used in** | `daw-core` — `RoomAcousticBridge` |
| **Library files** | `libroomacousticpp.so` (Linux), `libroomacousticpp.dylib` (macOS), `roomacousticpp.dll` (Windows) |
| **Fallback** | `FdnRoomSimulator` (pure-Java FDN implementation) |
| **Source** | <https://github.com/jmannall/RoomAcoustiCpp> |

Build from source following the instructions in the repository README.

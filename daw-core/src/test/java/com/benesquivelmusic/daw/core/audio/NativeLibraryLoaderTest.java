package com.benesquivelmusic.daw.core.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The file names {@link NativeLibraryLoader} and {@link NativeLibraryDetector}
 * try for PortAudio, in order. The operating system is passed explicitly, the
 * lowercased {@code os.name} the loader reads, so every table is pinned on every
 * host. Story 348: the CMake build names the Windows library {@code portaudio.dll}
 * to match the first name here; a change on either side fails this test or the
 * Windows build checks.
 */
class NativeLibraryLoaderTest {

    @Test
    void windowsTriesPortaudioDllThenLibportaudioDll() {
        assertThat(NativeLibraryLoader.platformLibraryNames("windows 11", "portaudio", 2))
                .containsExactly("portaudio.dll", "libportaudio.dll");
    }

    @Test
    void linuxTriesTheVersionedSonameThenTheUnversionedBuild() {
        assertThat(NativeLibraryLoader.platformLibraryNames("linux", "portaudio", 2))
                .containsExactly("libportaudio.so.2", "libportaudio.so");
    }

    @Test
    void macosTriesTheVersionedDylibThenTheUnversionedBuild() {
        assertThat(NativeLibraryLoader.platformLibraryNames("mac os x", "portaudio", 2))
                .containsExactly("libportaudio.2.dylib", "libportaudio.dylib");
    }
}

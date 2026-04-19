package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioBackendSdkTypesTest {

    @Test
    void deviceIdRejectsBlankComponents() {
        assertThrows(IllegalArgumentException.class, () -> new DeviceId(" ", "foo"));
        assertThrows(IllegalArgumentException.class, () -> new DeviceId("ASIO", ""));
        assertThrows(NullPointerException.class, () -> new DeviceId(null, "x"));
        assertThrows(NullPointerException.class, () -> new DeviceId("x", null));
    }

    @Test
    void deviceIdDefaultMarksIsDefault() {
        DeviceId d = DeviceId.defaultFor("ASIO");
        assertEquals("ASIO", d.backend());
        assertTrue(d.isDefault());
        assertFalse(new DeviceId("ASIO", "RME Fireface").isDefault());
    }

    @Test
    void audioFormatValidatesComponents() {
        assertThrows(IllegalArgumentException.class, () -> new AudioFormat(0, 2, 16));
        assertThrows(IllegalArgumentException.class, () -> new AudioFormat(44_100, 0, 16));
        assertThrows(IllegalArgumentException.class, () -> new AudioFormat(44_100, 2, 0));
    }

    @Test
    void audioFormatBytesPerFrame() {
        assertEquals(4, new AudioFormat(44_100, 2, 16).bytesPerFrame());
        assertEquals(6, new AudioFormat(48_000, 2, 24).bytesPerFrame()); // 24-bit = 3 bytes * 2 ch
        assertEquals(12, new AudioFormat(48_000, 3, 32).bytesPerFrame());
    }

    @Test
    void audioFormatCanonicalStringIncludesKeyDimensions() {
        assertEquals("48000Hz/2ch/24bit", AudioFormat.STUDIO_QUALITY_48K.canonical());
    }

    @Test
    void audioBlockChecksSampleArrayLength() {
        assertThrows(IllegalArgumentException.class,
                () -> new AudioBlock(44_100, 2, 4, new float[7]));
        AudioBlock silence = AudioBlock.silence(44_100, 2, 4);
        assertEquals(8, silence.totalSamples());
        for (float s : silence.samples()) {
            assertEquals(0.0f, s);
        }
    }

    @Test
    void sealedPermitsAllFiveBackendsPlusMock() {
        Class<?>[] permitted = AudioBackend.class.getPermittedSubclasses();
        assertNotNull(permitted);
        List<String> names = java.util.Arrays.stream(permitted).map(Class::getSimpleName).toList();
        assertTrue(names.contains("JavaxSoundBackend"), names.toString());
        assertTrue(names.contains("AsioBackend"), names.toString());
        assertTrue(names.contains("CoreAudioBackend"), names.toString());
        assertTrue(names.contains("WasapiBackend"), names.toString());
        assertTrue(names.contains("JackBackend"), names.toString());
        assertTrue(names.contains("MockAudioBackend"), names.toString());
    }
}

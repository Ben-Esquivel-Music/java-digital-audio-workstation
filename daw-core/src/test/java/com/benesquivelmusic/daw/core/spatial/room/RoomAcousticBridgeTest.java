package com.benesquivelmusic.daw.core.spatial.room;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RoomAcousticBridgeTest {

    @Test
    void shouldReturnEmptyWhenNativeLibraryNotAvailable() {
        // The native library is not available in the test environment
        Optional<RoomAcousticBridge> bridge = RoomAcousticBridge.tryLoad();

        assertThat(bridge).isEmpty();
    }

    @Test
    void shouldReturnEmptyForInvalidLibraryPath() {
        Optional<RoomAcousticBridge> bridge =
                RoomAcousticBridge.load(java.nio.file.Path.of("/nonexistent/libroomacousticpp.so"));

        assertThat(bridge).isEmpty();
    }
}

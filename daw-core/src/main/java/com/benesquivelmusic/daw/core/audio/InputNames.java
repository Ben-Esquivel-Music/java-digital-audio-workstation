package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import java.util.Optional;

/**
 * How a sentence the person reads names a capture input (story 338, ruling
 * E127): an input has no name to show when its name is missing, blank or the
 * default device's placeholder, which a capture plan keeps when the backend
 * names no default input; the sentence then says "the audio input".
 */
public final class InputNames {

    private static final String PLACEHOLDER = DeviceId.defaultFor("audio").name();

    private InputNames() {
    }

    /**
     * @param inputName the input's device name, or {@code null}
     * @return the name without surrounding spaces, or empty when the input has no name to show
     */
    public static Optional<String> shown(String inputName) {
        if (inputName == null || inputName.isBlank() || inputName.strip().equals(PLACEHOLDER)) {
            return Optional.empty();
        }
        return Optional.of(inputName.strip());
    }
}

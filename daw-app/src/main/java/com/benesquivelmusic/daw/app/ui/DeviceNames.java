package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import java.util.Optional;

/**
 * How a sentence the person reads names an audio device (story 338, ruling
 * E111). A device has no name to show when there is none, when its name is
 * blank, when it is the default placeholder ({@link DeviceId#isDefault()}) or
 * when it is the transport's stand-in for a device nobody named
 * ({@link TransportController#UNKNOWN_DEVICE}); a sentence then says "The
 * audio device" at its start and "the audio device" inside it.
 */
public final class DeviceNames {

    /** What opens a sentence about a device with no name to show. */
    public static final String UNNAMED_AT_START = "The audio device";
    /** What stands inside a sentence for a device with no name to show. */
    public static final String UNNAMED_INSIDE = "the audio device";

    private DeviceNames() {
    }

    /**
     * @param device the device, or {@code null}
     * @return its name, or empty when it has none to show
     */
    public static Optional<String> shown(DeviceId device) {
        if (device == null || device.name().isBlank() || device.isDefault()
                || device.equals(TransportController.UNKNOWN_DEVICE)) {
            return Optional.empty();
        }
        return Optional.of(device.name());
    }

    /**
     * @param device the device, if any
     * @return its name, or empty when it has none to show
     */
    public static Optional<String> shown(Optional<DeviceId> device) {
        return device.flatMap(DeviceNames::shown);
    }

    /**
     * The device an audio failure notice names (story 337, ruling E45
     * {@code {device}}; story 338, ruling E128): the open stream's device
     * when it has a name to show, else the open stream's backend, else the
     * installed provision's first backend, else nothing.
     *
     * @param openDevice       the open stream's device, if any
     * @param openBackend      the open stream's backend name, if any
     * @param provisionBackend the installed provision's first backend name, if any
     * @return the name, or {@code ""} when there is none
     */
    static String audioNoticeDevice(Optional<DeviceId> openDevice, Optional<String> openBackend,
                                    Optional<String> provisionBackend) {
        return shown(openDevice).or(() -> openBackend).or(() -> provisionBackend).orElse("");
    }

    /**
     * @param device the device, or {@code null}
     * @return its name, or {@value #UNNAMED_AT_START}
     */
    public static String atSentenceStart(DeviceId device) {
        return shown(device).orElse(UNNAMED_AT_START);
    }

    /**
     * @param device the device, or {@code null}
     * @return its name, or {@value #UNNAMED_INSIDE}
     */
    public static String insideSentence(DeviceId device) {
        return shown(device).orElse(UNNAMED_INSIDE);
    }
}

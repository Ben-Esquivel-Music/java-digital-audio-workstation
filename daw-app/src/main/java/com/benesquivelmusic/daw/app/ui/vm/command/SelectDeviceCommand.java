package com.benesquivelmusic.daw.app.ui.vm.command;

import java.util.Objects;

/**
 * Intent to select {@code device} (an insert / plugin) as the focused device
 * (§5.5). The device is typed {@code Object} because the plugin-chain surface
 * that consumes it migrates in story 294.
 *
 * @param device the device to select; must not be {@code null}
 */
public record SelectDeviceCommand(Object device) implements SelectionCommand {

    /** @throws NullPointerException if {@code device} is {@code null} */
    public SelectDeviceCommand {
        Objects.requireNonNull(device, "device must not be null");
    }

    @Override
    public void execute(SelectionIntentHandler handler) {
        handler.selectDevice(device);
    }
}

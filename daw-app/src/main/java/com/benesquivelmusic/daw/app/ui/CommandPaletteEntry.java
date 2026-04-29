package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;

import java.util.Objects;
import java.util.Optional;

/**
 * One row in the {@link CommandPaletteView}'s result list.
 *
 * <p>An entry is a thin description of a runnable user action: a stable
 * identifier (used to persist "recents" across sessions), a human-readable
 * label, an optional shortcut text and description, an enabled flag and
 * optional disabled-reason for the tooltip, an optional {@link DawIcon},
 * and the {@link Runnable} that performs the action.</p>
 *
 * @param id              stable, machine-readable identifier (e.g. the
 *                        {@link DawAction} name) — used as the key for the
 *                        recents persistence file
 * @param label           human-readable label shown in the list
 * @param shortcut        keyboard shortcut display text, or empty
 * @param description     short description for the secondary line, or empty
 * @param enabled         whether the action is currently executable
 * @param disabledReason  tooltip text when {@code enabled} is {@code false}
 * @param icon            optional icon to display, or {@code null}
 * @param handler         action to invoke when the user selects this entry
 */
public record CommandPaletteEntry(String id,
                                  String label,
                                  String shortcut,
                                  String description,
                                  boolean enabled,
                                  String disabledReason,
                                  DawIcon icon,
                                  Runnable handler) {

    public CommandPaletteEntry {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(handler, "handler must not be null");
        if (shortcut == null) shortcut = "";
        if (description == null) description = "";
        if (disabledReason == null) disabledReason = "";
        if (id.isEmpty()) {
            throw new IllegalArgumentException("id must not be empty");
        }
    }

    /** Convenience builder for an enabled entry without a disabled reason. */
    public static CommandPaletteEntry of(String id,
                                         String label,
                                         String shortcut,
                                         String description,
                                         DawIcon icon,
                                         Runnable handler) {
        return new CommandPaletteEntry(id, label, shortcut, description,
                true, "", icon, handler);
    }

    /** Returns the optional disabled-reason tooltip, never null. */
    public Optional<String> disabledReasonOpt() {
        return disabledReason.isEmpty() ? Optional.empty() : Optional.of(disabledReason);
    }
}

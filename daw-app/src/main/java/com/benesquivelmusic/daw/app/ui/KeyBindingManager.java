package com.benesquivelmusic.daw.app.ui;

import javafx.scene.input.KeyCombination;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.prefs.Preferences;

/**
 * Manages customizable keyboard shortcut bindings for {@link DawAction}s.
 *
 * <p>Each action is mapped to a {@link KeyCombination}. Custom bindings are
 * persisted through the Java {@link Preferences} API. Conflict detection
 * prevents two actions from sharing the same key combination.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 *   Preferences prefs = Preferences.userRoot().node("daw/keybindings");
 *   KeyBindingManager mgr = new KeyBindingManager(prefs);
 *   KeyCombination combo = mgr.getBinding(DawAction.PLAY_STOP);
 * }</pre>
 */
public final class KeyBindingManager {

    private static final String PREF_PREFIX = "keybinding.";

    private final Preferences prefs;
    private final EnumMap<DawAction, KeyCombination> bindings;

    /**
     * Creates a new key binding manager backed by the given preferences node.
     *
     * <p>On construction, loads any custom bindings from preferences. Actions
     * without a persisted custom binding retain their factory default.</p>
     *
     * @param prefs the backing preferences node (must not be {@code null})
     */
    public KeyBindingManager(Preferences prefs) {
        this.prefs = Objects.requireNonNull(prefs, "prefs must not be null");
        this.bindings = new EnumMap<>(DawAction.class);
        load();
    }

    /** Sentinel value stored in preferences to indicate an intentionally cleared binding. */
    private static final String CLEARED_SENTINEL = "NONE";

    private void load() {
        for (DawAction action : DawAction.values()) {
            String stored = prefs.get(PREF_PREFIX + action.name(), null);
            if (stored != null) {
                if (stored.equals(CLEARED_SENTINEL)) {
                    // Intentionally cleared — do not fall back to default
                    continue;
                }
                if (!stored.isEmpty()) {
                    bindings.put(action, KeyCombination.valueOf(stored));
                    continue;
                }
            }
            // No stored value — use factory default
            if (action.defaultBinding() != null) {
                bindings.put(action, action.defaultBinding());
            }
        }
    }

    /**
     * Returns the current key combination for the given action,
     * or {@link Optional#empty()} if no binding is set.
     *
     * @param action the action to query (must not be {@code null})
     * @return the current key binding
     */
    public Optional<KeyCombination> getBinding(DawAction action) {
        Objects.requireNonNull(action, "action must not be null");
        return Optional.ofNullable(bindings.get(action));
    }

    /**
     * Returns an unmodifiable snapshot of all current bindings.
     *
     * @return a map from action to key combination
     */
    public Map<DawAction, KeyCombination> getAllBindings() {
        return Collections.unmodifiableMap(new EnumMap<>(bindings));
    }

    /**
     * Sets the key combination for the given action and persists the change.
     *
     * <p>Pass {@code null} to clear the binding for the action.</p>
     *
     * @param action  the action to bind (must not be {@code null})
     * @param binding the new key combination, or {@code null} to clear
     * @throws IllegalArgumentException if the binding conflicts with another action
     */
    public void setBinding(DawAction action, KeyCombination binding) {
        Objects.requireNonNull(action, "action must not be null");
        if (binding != null) {
            Optional<DawAction> conflict = findConflict(action, binding);
            if (conflict.isPresent()) {
                throw new IllegalArgumentException(
                        "Key combination " + binding.getDisplayText()
                                + " is already assigned to " + conflict.get().displayName());
            }
        }
        if (binding != null) {
            bindings.put(action, binding);
            prefs.put(PREF_PREFIX + action.name(), binding.getName());
        } else {
            bindings.remove(action);
            prefs.put(PREF_PREFIX + action.name(), CLEARED_SENTINEL);
        }
    }

    /**
     * Finds the action (if any) that already uses the given key combination,
     * excluding the specified action itself.
     *
     * @param excluding the action to exclude from the search
     * @param binding   the key combination to check
     * @return the conflicting action, or empty if no conflict
     */
    public Optional<DawAction> findConflict(DawAction excluding, KeyCombination binding) {
        Objects.requireNonNull(binding, "binding must not be null");
        String bindingName = binding.getName();
        for (Map.Entry<DawAction, KeyCombination> entry : bindings.entrySet()) {
            if (entry.getKey() != excluding
                    && entry.getValue().getName().equals(bindingName)) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the action bound to the given key combination, if any.
     *
     * @param binding the key combination to look up
     * @return the bound action, or empty
     */
    public Optional<DawAction> getActionForBinding(KeyCombination binding) {
        Objects.requireNonNull(binding, "binding must not be null");
        String bindingName = binding.getName();
        for (Map.Entry<DawAction, KeyCombination> entry : bindings.entrySet()) {
            if (entry.getValue().getName().equals(bindingName)) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    /**
     * Resets all bindings to their factory defaults and clears persisted
     * customizations.
     */
    public void resetToDefaults() {
        bindings.clear();
        for (DawAction action : DawAction.values()) {
            prefs.remove(PREF_PREFIX + action.name());
            if (action.defaultBinding() != null) {
                bindings.put(action, action.defaultBinding());
            }
        }
    }

    /**
     * Returns a display string for the given action's current binding,
     * suitable for showing in tooltips and menus.
     *
     * @param action the action to query
     * @return the display text, or an empty string if unbound
     */
    public String getDisplayText(DawAction action) {
        Objects.requireNonNull(action, "action must not be null");
        KeyCombination binding = bindings.get(action);
        if (binding == null) {
            return "";
        }
        return binding.getDisplayText();
    }

    /**
     * Returns whether the given action has been customized (differs from
     * the factory default).
     *
     * @param action the action to check
     * @return {@code true} if the binding differs from the default
     */
    public boolean isCustomized(DawAction action) {
        Objects.requireNonNull(action, "action must not be null");
        KeyCombination current = bindings.get(action);
        KeyCombination defaultBinding = action.defaultBinding();
        if (current == null && defaultBinding == null) {
            return false;
        }
        if (current == null || defaultBinding == null) {
            return true;
        }
        return !current.getName().equals(defaultBinding.getName());
    }
}

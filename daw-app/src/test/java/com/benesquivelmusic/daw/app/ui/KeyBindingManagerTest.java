package com.benesquivelmusic.daw.app.ui;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyBindingManagerTest {

    private Preferences prefs;
    private KeyBindingManager manager;

    @BeforeEach
    void setUp() {
        prefs = Preferences.userRoot().node("keyBindingManagerTest_" + System.nanoTime());
        manager = new KeyBindingManager(prefs);
    }

    // ── Constructor ──────────────────────────────────────────────────────────

    @Test
    void shouldRejectNullPreferences() {
        assertThatThrownBy(() -> new KeyBindingManager(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── Default bindings ─────────────────────────────────────────────────────

    @Test
    void shouldLoadDefaultBindingsOnConstruction() {
        for (DawAction action : DawAction.values()) {
            if (action.defaultBinding() != null) {
                Optional<KeyCombination> binding = manager.getBinding(action);
                assertThat(binding)
                        .as("binding for %s", action.name())
                        .isPresent();
                assertThat(binding.get().getName())
                        .isEqualTo(action.defaultBinding().getName());
            }
        }
    }

    @Test
    void getAllBindingsShouldReturnAllDefaults() {
        Map<DawAction, KeyCombination> all = manager.getAllBindings();
        for (DawAction action : DawAction.values()) {
            if (action.defaultBinding() != null) {
                assertThat(all).containsKey(action);
            }
        }
    }

    @Test
    void getAllBindingsShouldReturnUnmodifiableMap() {
        Map<DawAction, KeyCombination> all = manager.getAllBindings();
        assertThatThrownBy(() -> all.put(DawAction.PLAY_STOP, null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ── Set binding ──────────────────────────────────────────────────────────

    @Test
    void shouldSetCustomBinding() {
        KeyCombination custom = new KeyCodeCombination(KeyCode.F12);
        manager.setBinding(DawAction.PLAY_STOP, custom);

        Optional<KeyCombination> result = manager.getBinding(DawAction.PLAY_STOP);
        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo(custom.getName());
    }

    @Test
    void shouldClearBindingWithNull() {
        manager.setBinding(DawAction.PLAY_STOP, null);

        Optional<KeyCombination> result = manager.getBinding(DawAction.PLAY_STOP);
        assertThat(result).isEmpty();
    }

    @Test
    void shouldRejectNullAction() {
        assertThatThrownBy(() -> manager.getBinding(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void setBindingShouldRejectNullAction() {
        KeyCombination combo = new KeyCodeCombination(KeyCode.F12);
        assertThatThrownBy(() -> manager.setBinding(null, combo))
                .isInstanceOf(NullPointerException.class);
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    @Test
    void shouldPersistCustomBinding() {
        KeyCombination custom = new KeyCodeCombination(KeyCode.F12);
        manager.setBinding(DawAction.PLAY_STOP, custom);

        KeyBindingManager reloaded = new KeyBindingManager(prefs);
        Optional<KeyCombination> result = reloaded.getBinding(DawAction.PLAY_STOP);
        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo(custom.getName());
    }

    @Test
    void shouldPersistClearedBinding() {
        manager.setBinding(DawAction.PLAY_STOP, null);

        KeyBindingManager reloaded = new KeyBindingManager(prefs);
        Optional<KeyCombination> result = reloaded.getBinding(DawAction.PLAY_STOP);
        assertThat(result).isEmpty();
    }

    // ── Conflict detection ───────────────────────────────────────────────────

    @Test
    void shouldDetectConflictWithExistingBinding() {
        // STOP's default is Escape. Try to assign Escape to PLAY_STOP.
        KeyCombination escapeBinding = DawAction.STOP.defaultBinding();

        assertThatThrownBy(() -> manager.setBinding(DawAction.PLAY_STOP, escapeBinding))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(DawAction.STOP.displayName());
    }

    @Test
    void shouldAllowSameBindingForSameAction() {
        // Re-assigning the same binding should not throw
        KeyCombination existing = DawAction.PLAY_STOP.defaultBinding();
        manager.setBinding(DawAction.PLAY_STOP, existing);

        Optional<KeyCombination> result = manager.getBinding(DawAction.PLAY_STOP);
        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo(existing.getName());
    }

    @Test
    void findConflictShouldReturnEmptyForUnusedBinding() {
        KeyCombination unused = new KeyCodeCombination(KeyCode.F12);
        Optional<DawAction> conflict = manager.findConflict(DawAction.PLAY_STOP, unused);
        assertThat(conflict).isEmpty();
    }

    @Test
    void findConflictShouldReturnConflictingAction() {
        KeyCombination escapeBinding = DawAction.STOP.defaultBinding();
        Optional<DawAction> conflict = manager.findConflict(DawAction.PLAY_STOP, escapeBinding);
        assertThat(conflict).isPresent();
        assertThat(conflict.get()).isEqualTo(DawAction.STOP);
    }

    @Test
    void findConflictShouldExcludeSelf() {
        KeyCombination binding = DawAction.STOP.defaultBinding();
        Optional<DawAction> conflict = manager.findConflict(DawAction.STOP, binding);
        assertThat(conflict).isEmpty();
    }

    // ── getActionForBinding ──────────────────────────────────────────────────

    @Test
    void shouldFindActionForBinding() {
        KeyCombination spaceBinding = DawAction.PLAY_STOP.defaultBinding();
        Optional<DawAction> action = manager.getActionForBinding(spaceBinding);
        assertThat(action).isPresent();
        assertThat(action.get()).isEqualTo(DawAction.PLAY_STOP);
    }

    @Test
    void shouldReturnEmptyForUnboundCombination() {
        KeyCombination unused = new KeyCodeCombination(KeyCode.F12);
        Optional<DawAction> action = manager.getActionForBinding(unused);
        assertThat(action).isEmpty();
    }

    // ── Reset to defaults ────────────────────────────────────────────────────

    @Test
    void resetShouldRestoreAllDefaults() {
        // Customize a binding
        manager.setBinding(DawAction.PLAY_STOP, null);
        assertThat(manager.getBinding(DawAction.PLAY_STOP)).isEmpty();

        // Reset
        manager.resetToDefaults();

        // Verify default restored
        Optional<KeyCombination> result = manager.getBinding(DawAction.PLAY_STOP);
        assertThat(result).isPresent();
        assertThat(result.get().getName())
                .isEqualTo(DawAction.PLAY_STOP.defaultBinding().getName());
    }

    @Test
    void resetShouldPersistDefaults() {
        manager.setBinding(DawAction.PLAY_STOP, null);
        manager.resetToDefaults();

        KeyBindingManager reloaded = new KeyBindingManager(prefs);
        Optional<KeyCombination> result = reloaded.getBinding(DawAction.PLAY_STOP);
        assertThat(result).isPresent();
        assertThat(result.get().getName())
                .isEqualTo(DawAction.PLAY_STOP.defaultBinding().getName());
    }

    // ── Display text ─────────────────────────────────────────────────────────

    @Test
    void getDisplayTextShouldReturnNonEmptyForBoundAction() {
        String display = manager.getDisplayText(DawAction.PLAY_STOP);
        assertThat(display).isNotEmpty();
    }

    @Test
    void getDisplayTextShouldReturnEmptyForUnboundAction() {
        manager.setBinding(DawAction.PLAY_STOP, null);
        String display = manager.getDisplayText(DawAction.PLAY_STOP);
        assertThat(display).isEmpty();
    }

    @Test
    void getDisplayTextShouldRejectNullAction() {
        assertThatThrownBy(() -> manager.getDisplayText(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── isCustomized ─────────────────────────────────────────────────────────

    @Test
    void isCustomizedShouldReturnFalseForDefaultBinding() {
        assertThat(manager.isCustomized(DawAction.PLAY_STOP)).isFalse();
    }

    @Test
    void isCustomizedShouldReturnTrueForChangedBinding() {
        manager.setBinding(DawAction.PLAY_STOP,
                new KeyCodeCombination(KeyCode.F12));
        assertThat(manager.isCustomized(DawAction.PLAY_STOP)).isTrue();
    }

    @Test
    void isCustomizedShouldReturnTrueForClearedBinding() {
        manager.setBinding(DawAction.PLAY_STOP, null);
        assertThat(manager.isCustomized(DawAction.PLAY_STOP)).isTrue();
    }

    @Test
    void isCustomizedShouldRejectNullAction() {
        assertThatThrownBy(() -> manager.isCustomized(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── Corrupted preferences ────────────────────────────────────────────────

    @Test
    void shouldLoadStoredValidBinding() {
        // Store a valid binding in preferences
        KeyCombination custom = new KeyCodeCombination(KeyCode.F12);
        prefs.put("keybinding.PLAY_STOP", custom.getName());

        KeyBindingManager reloaded = new KeyBindingManager(prefs);
        Optional<KeyCombination> result = reloaded.getBinding(DawAction.PLAY_STOP);
        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo(custom.getName());
    }

    @Test
    void shouldTreatEmptyStringAsNoStoredPreference() {
        // An empty string in the preference should fall back to default
        prefs.put("keybinding.PLAY_STOP", "");

        KeyBindingManager reloaded = new KeyBindingManager(prefs);
        Optional<KeyCombination> result = reloaded.getBinding(DawAction.PLAY_STOP);
        assertThat(result).isPresent();
        assertThat(result.get().getName())
                .isEqualTo(DawAction.PLAY_STOP.defaultBinding().getName());
    }
}

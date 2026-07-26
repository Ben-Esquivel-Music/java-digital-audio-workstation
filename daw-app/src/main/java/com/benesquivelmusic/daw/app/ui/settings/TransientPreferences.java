package com.benesquivelmusic.daw.app.ui.settings;

import java.util.HashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;

/**
 * A purely in-memory {@link AbstractPreferences} node — no backing
 * store, no I/O, ever (story 305).
 *
 * <p>{@link SettingsCatalogue#create()} builds its scratch
 * {@code SettingsModel} over a root {@code TransientPreferences}
 * ({@code new TransientPreferences(null, "")}) so that reading canonical
 * defaults and delegating validators through the real setters neither
 * touches nor pollutes the user's real preferences store. {@code flush}
 * and {@code sync} are no-ops; children are further in-memory nodes.</p>
 *
 * <p>Thread-safety follows {@link AbstractPreferences}' own locking —
 * every SPI method below is invoked with the node lock held.</p>
 */
final class TransientPreferences extends AbstractPreferences {

    private final TransientPreferences parentNode;
    private final Map<String, String> values = new HashMap<>();
    private final Map<String, TransientPreferences> children = new HashMap<>();

    /**
     * Creates a node under {@code parent} ({@code null} for the root,
     * whose name must be {@code ""} per the {@link AbstractPreferences}
     * contract).
     *
     * @param parent the parent node, or {@code null} for the root
     * @param name   the node name ({@code ""} for the root)
     */
    TransientPreferences(TransientPreferences parent, String name) {
        super(parent, name);
        this.parentNode = parent;
    }

    @Override
    protected void putSpi(String key, String value) {
        values.put(key, value);
    }

    @Override
    protected String getSpi(String key) {
        return values.get(key);
    }

    @Override
    protected void removeSpi(String key) {
        values.remove(key);
    }

    @Override
    protected void removeNodeSpi() {
        values.clear();
        if (parentNode != null) {
            parentNode.children.remove(name());
        }
    }

    @Override
    protected String[] keysSpi() {
        return values.keySet().toArray(String[]::new);
    }

    @Override
    protected String[] childrenNamesSpi() {
        return children.keySet().toArray(String[]::new);
    }

    @Override
    protected AbstractPreferences childSpi(String name) {
        return children.computeIfAbsent(
                name, childName -> new TransientPreferences(this, childName));
    }

    @Override
    protected void syncSpi() {
        // In-memory only — there is no backing store to synchronize with.
    }

    @Override
    protected void flushSpi() {
        // In-memory only — there is no backing store to flush to.
    }
}

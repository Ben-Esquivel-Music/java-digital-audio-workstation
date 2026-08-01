package com.benesquivelmusic.daw.app.ui.settings;

import java.util.List;

/**
 * Story 309 — synthetic {@link SettingsCatalogue} fixtures for the
 * scope-chip tests. A synthetic catalogue reuses REAL descriptors under
 * a deliberately misleading category id (a real category name whose
 * actual modal scope differs), so any {@code switch (category.id())}
 * shortcut in the shell fails loudly while genuine descriptor-scope
 * derivation passes. Lives in {@code ui.settings} to reach the
 * package-private {@link SettingsCatalogue} constructor; public so the
 * {@code ..app.ui} chip tests (patched into the same module by surefire)
 * can use it.
 */
public final class SyntheticCatalogues {

    private SyntheticCatalogues() {
    }

    /**
     * A one-category catalogue whose id is {@code "audio"} (the REAL
     * audio category's modal scope is AUDIO_DEVICE) but whose descriptors
     * are the real appearance descriptors — every one APPLICATION-scoped.
     * A hard-coded category-name map would label this pane "Audio
     * device"; descriptor derivation must say "Application".
     *
     * @return the synthetic catalogue
     */
    public static SettingsCatalogue audioNamedButApplicationScoped() {
        SettingsCatalogue real = SettingsCatalogue.create();
        SettingsCatalogue.Category appearance = real.categories().stream()
                .filter(category -> "appearance".equals(category.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no appearance category in the real catalogue"));
        return new SettingsCatalogue(List.of(new SettingsCatalogue.Category(
                "audio", "Audio", appearance.groups())));
    }
}

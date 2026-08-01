package com.benesquivelmusic.daw.app.ui.settings;

import com.benesquivelmusic.daw.app.ui.DawAction;
import com.benesquivelmusic.daw.app.ui.SettingsModel;
import com.benesquivelmusic.daw.app.ui.density.DensityManager;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 305 — closes the §1.2 split-catalogue gap structurally: every
 * user-facing {@code SettingsModel} preferences key (reflected from the
 * class's {@code KEY_*} constants, so a newly added key without a
 * descriptor fails the build), the three manager-backed appearance keys,
 * and one key-binding descriptor per {@link DawAction} must all be
 * present in {@link SettingsCatalogue}, under the pinned §3.3 taxonomy.
 *
 * <p>The single documented exclusion is {@code appearance.themeId} — a
 * dead legacy key whose live replacement is
 * {@link ThemeManager#PREF_KEY} (see the catalogue's Javadoc).</p>
 */
class SettingsCatalogueCompletenessTest {

    private static final String EXCLUDED_DEAD_THEME_KEY = "appearance.themeId";

    private final SettingsCatalogue catalogue = SettingsCatalogue.create();

    /**
     * Reflects every {@code static final String KEY_*} constant off
     * {@code SettingsModel}. Same-module deep reflection — always
     * permitted regardless of {@code opens} directives.
     */
    private static Set<String> reflectedSettingsModelKeys() throws IllegalAccessException {
        Set<String> keys = new TreeSet<>();
        for (Field field : SettingsModel.class.getDeclaredFields()) {
            if (field.getName().startsWith("KEY_")
                    && Modifier.isStatic(field.getModifiers())
                    && Modifier.isFinal(field.getModifiers())
                    && field.getType() == String.class) {
                field.setAccessible(true);
                keys.add((String) field.get(null));
            }
        }
        return keys;
    }

    private static Set<String> managerKeys() {
        return Set.of(ThemeManager.PREF_KEY, DensityManager.PREF_KEY, MotionManager.PREF_KEY);
    }

    /**
     * Story 308 — the absorbed Recording/Backups descriptors persist in
     * their pre-existing external stores (the {@code MetronomeController}
     * preferences node, {@code ~/.daw/metronome-settings.json}, the
     * project XML, and {@code ~/.daw/backup-retention.json}), never in
     * {@code SettingsModel} — so, like {@link #managerKeys()}, they are
     * excluded from the reflected-KEY_* equality below.
     */
    private static boolean isAbsorbedExternalStoreKey(String id) {
        return id.startsWith("metronome.") || id.startsWith("backups.");
    }

    private SettingsCatalogue.Category categoryById(String id) {
        return catalogue.categories().stream()
                .filter(category -> category.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No category with id: " + id));
    }

    @Test
    void everySettingsModelKeyShouldHaveExactlyOneDescriptor() throws IllegalAccessException {
        Set<String> reflectedKeys = reflectedSettingsModelKeys();
        // Non-empty guard: an accidental rename of the KEY_ convention must
        // not let this test pass vacuously.
        assertThat(reflectedKeys).isNotEmpty();
        reflectedKeys.remove(EXCLUDED_DEAD_THEME_KEY);

        Set<String> modelBackedIds = catalogue.descriptors().stream()
                .map(SettingDescriptor::id)
                .filter(id -> !id.startsWith("keybinding."))
                .filter(id -> !managerKeys().contains(id))
                .filter(id -> !isAbsorbedExternalStoreKey(id))
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(modelBackedIds)
                .as("SettingsModel-backed descriptor ids must be exactly the reflected "
                        + "KEY_* set minus the documented %s exclusion — a new "
                        + "SettingsModel key without a descriptor fails here",
                        EXCLUDED_DEAD_THEME_KEY)
                .containsExactlyInAnyOrderElementsOf(reflectedKeys);
    }

    @Test
    void deadThemeIdKeyShouldBeExcludedInFavourOfTheLiveThemeKey() {
        assertThat(catalogue.byId(EXCLUDED_DEAD_THEME_KEY))
                .as("the dead legacy theme key must not be catalogued")
                .isEmpty();
        assertThat(catalogue.byId(ThemeManager.PREF_KEY))
                .as("the live theme authority key must be catalogued")
                .isPresent();
    }

    @Test
    void managerBackedDescriptorsShouldBePresent() {
        assertThat(catalogue.byId(ThemeManager.PREF_KEY)).isPresent();
        assertThat(catalogue.byId(DensityManager.PREF_KEY)).isPresent();
        assertThat(catalogue.byId(MotionManager.PREF_KEY)).isPresent();
    }

    @Test
    void everyDawActionShouldHaveExactlyOneKeyCaptureDescriptor() {
        for (DawAction action : DawAction.values()) {
            String id = "keybinding." + action.name();
            SettingDescriptor<?> descriptor = catalogue.byId(id).orElseThrow(
                    () -> new AssertionError("No key-binding descriptor for " + action));
            assertThat(descriptor.controlKind())
                    .as("control kind of %s", id)
                    .isEqualTo(ControlKind.KEY_CAPTURE);
        }
        long keyCaptureCount = catalogue.descriptors().stream()
                .filter(descriptor -> descriptor.controlKind() == ControlKind.KEY_CAPTURE)
                .count();
        assertThat(keyCaptureCount)
                .as("exactly one KEY_CAPTURE descriptor per DawAction")
                .isEqualTo(DawAction.values().length);
    }

    @Test
    void allIdsShouldBeUniqueAndTheTotalCountPinned() {
        List<String> ids = catalogue.descriptors().stream()
                .map(SettingDescriptor::id)
                .toList();
        assertThat(ids).doesNotHaveDuplicates();
        // 17 SettingsModel-backed + 3 manager-backed + 13 story-308
        // absorbed Recording/Backups + one per DawAction.
        assertThat(catalogue.descriptors())
                .hasSize(33 + DawAction.values().length);
    }

    @Test
    void categoriesShouldFollowTheBookOrder() {
        assertThat(catalogue.categories())
                .extracting(SettingsCatalogue.Category::id)
                .containsExactly("audio", "appearance", "project", "recording",
                        "backups", "keyBindings", "plugins", "general");
    }

    @Test
    void recordingAndBackupsCarryTheirAbsorbedDescriptorsAndGeneralStaysPlaceholder() {
        // Story 308 — the Recording/Backups placeholders are populated;
        // per-group descriptor id order is pinned (§3.3).
        SettingsCatalogue.Category recording = categoryById("recording");
        assertThat(recording.groups())
                .extracting(SettingsCatalogue.Group::id)
                .containsExactly("metronome", "clickRouting", "cueMix");
        assertThat(groupIds(recording, "metronome"))
                .containsExactly("metronome.enabled", "metronome.countIn");
        assertThat(groupIds(recording, "clickRouting"))
                .containsExactly("metronome.clickChannel", "metronome.clickGain",
                        "metronome.clickSideOutput");
        assertThat(groupIds(recording, "cueMix"))
                .containsExactly("metronome.clickMainMix", "metronome.clickCueBus");

        SettingsCatalogue.Category backups = categoryById("backups");
        assertThat(backups.groups())
                .extracting(SettingsCatalogue.Group::id)
                .containsExactly("retention", "diskUsage");
        assertThat(groupIds(backups, "retention"))
                .containsExactly("backups.keepRecent", "backups.keepHourly",
                        "backups.keepDaily", "backups.keepWeekly",
                        "backups.maxAgeDays", "backups.maxDiskGiB");
        // Disk usage is the §5.8 group VISUAL (the pie), not a setting —
        // its taxonomy slot stays descriptor-empty by design.
        assertThat(groupIds(backups, "diskUsage")).isEmpty();

        SettingsCatalogue.Category general = categoryById("general");
        assertThat(general.groups())
                .extracting(SettingsCatalogue.Group::id)
                .containsExactly("startup", "language", "resetProfiles");
        for (SettingsCatalogue.Group group : general.groups()) {
            assertThat(group.settings())
                    .as("placeholder group general.%s stays empty (story-309 territory)",
                            group.id())
                    .isEmpty();
        }
    }

    private static List<String> groupIds(SettingsCatalogue.Category category, String groupId) {
        return category.groups().stream()
                .filter(group -> group.id().equals(groupId))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No group " + groupId + " in " + category.id()))
                .settings().stream()
                .map(SettingDescriptor::id)
                .toList();
    }

    @Test
    void keyBindingGroupsShouldMatchDawActionCategoryEnumOrder() {
        SettingsCatalogue.Category keyBindings = categoryById("keyBindings");
        assertThat(keyBindings.groups())
                .extracting(SettingsCatalogue.Group::id)
                .containsExactlyElementsOf(Arrays.stream(DawAction.Category.values())
                        .map(Enum::name)
                        .toList());
        // Group titles delegate to the enum authority, not the bundle.
        assertThat(keyBindings.groups())
                .extracting(SettingsCatalogue.Group::title)
                .containsExactlyElementsOf(Arrays.stream(DawAction.Category.values())
                        .map(DawAction.Category::displayName)
                        .toList());
    }
}

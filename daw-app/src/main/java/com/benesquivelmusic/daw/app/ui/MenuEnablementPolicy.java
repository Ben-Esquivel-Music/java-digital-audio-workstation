package com.benesquivelmusic.daw.app.ui;

/**
 * Pure-logic policy that decides which DAW menu items should be enabled or
 * disabled given a snapshot of project state.
 *
 * <p>Extracted from {@link DawMenuBarController} so the enable/disable
 * mapping can be unit tested without spinning up a JavaFX toolkit. The
 * policy has no UI dependencies — it is a function from {@link MenuState}
 * to {@link MenuEnablement}.</p>
 *
 * <p>This is part of the controller decomposition described in the
 * "Decompose Remaining God-Class Controllers into Focused Services"
 * issue: the menu controller previously mixed three concerns
 * (construction, action dispatch, and enable-state computation). The
 * enable-state policy is now isolated here.</p>
 */
public final class MenuEnablementPolicy {

    private MenuEnablementPolicy() {
        // utility — no instances
    }

    /**
     * Snapshot of project / UI state relevant for menu enable/disable.
     *
     * @param projectDirty whether the project has unsaved changes
     * @param canUndo      whether the undo history has at least one entry
     * @param canRedo      whether the redo stack has at least one entry
     * @param hasClipboard whether the clipboard holds content that can be pasted
     * @param hasSelection whether there is a current clip/note selection
     * @param hasTracks    whether the project contains at least one track
     */
    public record MenuState(boolean projectDirty,
                            boolean canUndo,
                            boolean canRedo,
                            boolean hasClipboard,
                            boolean hasSelection,
                            boolean hasTracks) { }

    /**
     * Resolved enable/disable flags for each state-sensitive menu item.
     * A {@code true} flag means the item should be <em>disabled</em>.
     */
    public record MenuEnablement(boolean saveDisabled,
                                 boolean exportSessionDisabled,
                                 boolean undoDisabled,
                                 boolean redoDisabled,
                                 boolean copyDisabled,
                                 boolean cutDisabled,
                                 boolean pasteDisabled,
                                 boolean duplicateDisabled,
                                 boolean deleteDisabled) { }

    /**
     * Computes which menu items should be disabled for the given state.
     *
     * @param state the current menu-relevant state snapshot
     * @return the resolved disable flags
     */
    public static MenuEnablement compute(MenuState state) {
        return new MenuEnablement(
                !state.projectDirty(),
                !state.hasTracks(),
                !state.canUndo(),
                !state.canRedo(),
                !state.hasSelection(),
                !state.hasSelection(),
                !state.hasClipboard(),
                !state.hasSelection(),
                !state.hasSelection()
        );
    }
}

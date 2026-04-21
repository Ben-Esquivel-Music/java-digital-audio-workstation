package com.benesquivelmusic.daw.core.audio.processing;

import java.nio.file.Path;
import java.util.List;

/**
 * Mixin implemented by {@link com.benesquivelmusic.daw.core.undo.UndoableAction
 * UndoableAction}s that reference one or more asset files tracked by a
 * {@link ClipAssetHistory}.
 *
 * <p>Used by {@link ClipAssetHistory#syncPinsFromHistory} to rebuild the
 * live pin set from the current contents of an
 * {@link com.benesquivelmusic.daw.core.undo.UndoManager}, so that assets
 * referenced by discarded actions (via {@code trimHistory()} or a redo
 * stack clear) are released automatically rather than leaking.</p>
 */
public interface ClipAssetReferencing {

    /** Returns the asset paths this action depends on (never {@code null}). */
    List<Path> referencedAssets();
}

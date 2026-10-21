package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AssetReference;
import com.benesquivelmusic.daw.core.persistence.MissingAsset;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link MissingAssetsDialog.Actions} for the dialog's own tests: records
 * what the report's controls ask, and answers each row's status and the
 * running search from what the test set.
 */
final class RecordingReportActions implements MissingAssetsDialog.Actions {

    final AtomicInteger archives = new AtomicInteger();
    final List<MissingAsset> located = new CopyOnWriteArrayList<>();
    final List<MissingAsset> skipped = new CopyOnWriteArrayList<>();
    final AtomicInteger folderSearches = new AtomicInteger();
    final AtomicInteger cancelledSearches = new AtomicInteger();
    final Map<AssetReference, AssetRelinkFlow.RowStatus> statuses = new ConcurrentHashMap<>();
    volatile Path searched;

    @Override
    public void archiveProject() {
        archives.incrementAndGet();
    }

    @Override
    public void locateFile(MissingAsset row) {
        located.add(row);
    }

    @Override
    public void skip(MissingAsset row) {
        skipped.add(row);
    }

    @Override
    public void locateFolder() {
        folderSearches.incrementAndGet();
    }

    @Override
    public void cancelFolderSearch() {
        cancelledSearches.incrementAndGet();
    }

    @Override
    public AssetRelinkFlow.RowStatus statusOf(AssetReference asset) {
        return statuses.getOrDefault(asset, AssetRelinkFlow.RowStatus.IDLE);
    }

    @Override
    public Optional<Path> searchedFolder() {
        return Optional.ofNullable(searched);
    }
}

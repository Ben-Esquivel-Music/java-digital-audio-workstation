package com.benesquivelmusic.daw.core.plugin;

import java.util.List;
import java.util.Objects;

/**
 * What the startup restore of the installed-plugin registry has to tell the
 * engineer, once per startup (story 334, studio ruling E9 and G4): the
 * entries that stay listed as Missing, whether the list itself could not be
 * read, how many of its entries could not be read and were removed (a
 * repeated id is logged only, never counted), and whether the copy of the
 * list the read owed could not be kept. A list with problems is copied when
 * it is read; when the copy fails, the list is left as it is until a copy of
 * it can be kept, and the notification must not say a copy was kept
 * (ruling-3 Q2).
 *
 * @param missing        the entries that did not load, in list order
 * @param listUnreadable whether the list could not be read, so no installed plugin was restored
 * @param removedEntries how many unreadable entries were removed from the list
 * @param listCopyOwed   whether the read found a problem with the list but could not keep a copy of it
 */
public record RestoreReport(List<InstalledPlugin> missing, boolean listUnreadable, int removedEntries,
                            boolean listCopyOwed) {

    public RestoreReport {
        Objects.requireNonNull(missing, "missing must not be null");
        missing = List.copyOf(missing);
        if (removedEntries < 0) {
            throw new IllegalArgumentException("removedEntries must not be negative: " + removedEntries);
        }
    }
}

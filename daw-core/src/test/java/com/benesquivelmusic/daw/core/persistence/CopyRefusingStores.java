package com.benesquivelmusic.daw.core.persistence;

import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/**
 * Installed-plugin stores whose copies of the list fail while a test says so
 * — a full or read-only configuration folder that refuses the
 * {@code plugin-registry.json.<stamp>.bak} with an
 * {@link AccessDeniedException} — while every other write publishes as usual
 * (story 334, ruling-3 Q2 and A2). Public: the registry's tests build their
 * stores with it too.
 */
public final class CopyRefusingStores {

    private CopyRefusingStores() {
    }

    /**
     * A store of {@code file} whose copies of the list fail while
     * {@code copiesFail} answers {@code true}.
     *
     * @param file       the list's file
     * @param copiesFail whether a copy of the list fails now
     * @return the store
     */
    public static InstalledPluginStore of(Path file, BooleanSupplier copiesFail) {
        return new InstalledPluginStore(file, new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
                (temp, target, options) -> {
                    if (copiesFail.getAsBoolean() && target.getFileName().toString().endsWith(".bak")) {
                        throw new AccessDeniedException(target.toString());
                    }
                    Files.move(temp, target, options);
                }));
    }
}

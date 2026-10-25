package com.benesquivelmusic.daw.core.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

/** Explicit provenance for checkpoints whose indices are monotonic across restarts. */
public final class CheckpointOrder {
    public static final String FILE_NAME = ".checkpoint-order";
    private static final String VERSION = "monotonic-checkpoints-v1";

    private CheckpointOrder() { }

    public static Set<String> read(Path directory) throws IOException {
        Path fact = directory.resolve(FILE_NAME);
        if (!Files.exists(fact)) return Set.of();
        java.util.List<String> lines = Files.readAllLines(fact);
        if (lines.isEmpty() || !VERSION.equals(lines.getFirst())) {
            throw new IOException("Unrecognised checkpoint ordering fact: " + fact);
        }
        Set<String> covered = new HashSet<>();
        for (String line : lines.subList(1, lines.size())) {
            if (CheckpointFileName.parse(line).isEmpty()) throw new IOException("Invalid checkpoint ordering fact: " + fact);
            covered.add(line);
        }
        return Set.copyOf(covered);
    }

    public static Comparator<CheckpointFileName> comparator(Set<String> covered) {
        return (left, right) -> {
            boolean a = covered.contains(left.fileName());
            boolean b = covered.contains(right.fileName());
            if (a != b) return Boolean.compare(a, b);
            return a ? Comparator.comparingLong(CheckpointFileName::index)
                    .thenComparing(CheckpointFileName.ORDER).compare(left, right)
                    : CheckpointFileName.ORDER.compare(left, right);
        };
    }

    /** Record before publishing the named checkpoint; a crash may leave a harmless absent name. */
    public static void record(Path directory, Set<String> covered) throws IOException {
        record(directory, covered, new AtomicFileWriter());
    }

    public static void record(Path directory, Set<String> covered, AtomicFileWriter writer) throws IOException {
        writer.writeString(directory.resolve(FILE_NAME), VERSION + "\n"
                + (covered.isEmpty() ? "" : String.join("\n", covered.stream().sorted().toList()) + "\n"));
    }
}

package com.benesquivelmusic.daw.core.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/** The exact atomic-writer temps inspected by a writable, locked open. */
public record RecoveryTemps(List<Path> files, Path newerHead) {
    private static final Logger LOG = Logger.getLogger(RecoveryTemps.class.getName());
    public RecoveryTemps { files = List.copyOf(files); }

    public static RecoveryTemps inspect(Path folder) throws IOException {
        Path projectFile = folder.resolve(ProjectManager.PROJECT_FILE_NAME);
        Path head = Files.isSymbolicLink(projectFile) ? projectFile.toRealPath() : projectFile;
        LinkedHashSet<Path> temps = new LinkedHashSet<>();
        collect(folder, null, temps);
        collect(folder.resolve("snapshots"), null, temps);
        collect(folder.resolve("checkpoints"), null, temps);
        if (!head.getParent().equals(folder)) collect(head.getParent(), head.getFileName().toString(), temps);
        Path newest = null;
        for (Path temp : temps) {
            Optional<String> target = AtomicFileWriter.publishedNameOf(temp.getFileName().toString());
            if (!temp.getParent().equals(head.getParent()) || !target.orElse("").equals(head.getFileName().toString())) continue;
            if (Files.getLastModifiedTime(temp).compareTo(Files.getLastModifiedTime(head)) <= 0) continue;
            try {
                String xml = Files.readString(temp);
                if (!xml.contains("<daw-project") || !xml.stripTrailing().endsWith("</daw-project>")) continue;
                new ProjectDeserializer().deserialize(xml, folder);
                if (newest == null || Files.getLastModifiedTime(temp).compareTo(Files.getLastModifiedTime(newest)) > 0) newest = temp;
            } catch (IOException | RuntimeException incomplete) {
                LOG.fine(() -> "An unfinished head temp is not a complete recovery base: " + temp);
            }
        }
        return new RecoveryTemps(new ArrayList<>(temps), newest);
    }

    private static void collect(Path directory, String onlyTarget, java.util.Set<Path> result) throws IOException {
        if (!Files.isDirectory(directory)) return;
        try (java.util.stream.Stream<Path> entries = Files.list(directory)) {
            entries.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> AtomicFileWriter.publishedNameOf(path.getFileName().toString())
                            .filter(name -> onlyTarget == null || name.equals(onlyTarget)).isPresent())
                    .forEach(result::add);
        }
    }

    public void clean() throws IOException {
        for (Path path : files) Files.deleteIfExists(path);
        LOG.info(() -> "Handled " + files.size() + " atomic-writer temp file(s) during project recovery");
    }
}

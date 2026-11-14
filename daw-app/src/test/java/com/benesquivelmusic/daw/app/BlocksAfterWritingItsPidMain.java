package com.benesquivelmusic.daw.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CountDownLatch;

/**
 * The child-JVM side of {@code ChildJvmTest} (story 336, studio-engineer
 * ruling R992-9): it writes its own process id to the path its first argument
 * names, through a temporary file moved into place atomically so the parent
 * never reads a partial id, and then blocks until it is destroyed, awaiting a
 * latch that nothing counts down. It uses only the JDK.
 */
public final class BlocksAfterWritingItsPidMain {

    private BlocksAfterWritingItsPidMain() {
    }

    /**
     * @param args the path the process id is written to
     * @throws IOException          if the process id cannot be written
     * @throws InterruptedException never, in practice: nothing interrupts the main thread
     */
    public static void main(String[] args) throws IOException, InterruptedException {
        Path pidFile = Path.of(args[0]);
        Path written = Files.writeString(pidFile.resolveSibling(pidFile.getFileName() + ".tmp"),
                Long.toString(ProcessHandle.current().pid()), StandardCharsets.US_ASCII);
        Files.move(written, pidFile, StandardCopyOption.ATOMIC_MOVE);
        new CountDownLatch(1).await();
    }
}

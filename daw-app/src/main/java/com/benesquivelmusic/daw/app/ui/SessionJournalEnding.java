package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.status.ProjectJournalCoordinator;
import com.benesquivelmusic.daw.core.session.SessionManager;
import com.benesquivelmusic.daw.core.session.WorkingSession;

import java.io.IOException;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The application's {@link JournalEnding} (story 333; rulings R3, R7 and the
 * studio engineer's ruling 2 OQ-1): it ends the journal of the folder a
 * close closes through the {@link ProjectJournalCoordinator}, and folds the
 * journal's segments into that folder's working session — the session the
 * composition root opened for it ({@code MainController}'s active session),
 * or, when the close comes before that session's open reached the FX
 * thread, the one its open is still making ({@link SessionOpening}).
 *
 * <p>A journal end touches the journal only when the coordinator's open
 * journal is the journal of the folder being closed — the project
 * manager's open folder when the end was prepared on the FX thread. Any
 * other journal is not this close's (after Save As's Continue the
 * coordinator may still hold the original folder's journal, whose seal is
 * not the copy's to write): the journal part then does nothing and returns
 * at once (ruling 2 OQ-1, constraint 2). With no journal of the folder open
 * — none was ever opened for it (a Save As copy opened after Continue), or
 * it was closed already — {@link JournalEnd#SEAL} closes the folder's
 * session manifest alone, unless the folder's journal was last closed
 * unsealed ({@link ProjectJournalCoordinator#closedUnsealed}), which a
 * manifest must not certify. Save As's close of a read-only folder asks
 * for no journal end at all, so nothing is written to it (ruling R5)
 * whatever the composition root keeps of its session.</p>
 */
final class SessionJournalEnding implements JournalEnding {

    private static final Logger LOG = Logger.getLogger(SessionJournalEnding.class.getName());

    /**
     * A working session being opened for a project folder, off the FX thread
     * (story 333, FEAS-S11): the composition root records it before its
     * worker starts and completes it once the session is open — empty when
     * none was opened — so a journal end prepared before the open reached
     * the FX thread still folds the segments into it.
     *
     * @param projectDir the folder whose session is being opened
     * @param generation the composition root's refresh generation it belongs to
     * @param opened     the opened session, or empty
     */
    record SessionOpening(Path projectDir, long generation, CompletableFuture<Optional<WorkingSession>> opened) {
        SessionOpening {
            Objects.requireNonNull(projectDir, "projectDir must not be null");
            Objects.requireNonNull(opened, "opened must not be null");
        }
    }

    private final ProjectJournalCoordinator journal;
    private final SessionManager sessionManager;
    private final Supplier<WorkingSession> activeSession;
    private final Supplier<Path> activeSessionProjectDir;
    private final Supplier<SessionOpening> sessionOpening;
    private final Supplier<Path> currentProjectFolder;
    private final InstantSource clock;

    /**
     * Creates the journal ending over the composition root's live facts, each
     * read on the FX thread when an end is prepared.
     *
     * @param journal                 the journal coordinator
     * @param sessionManager          writes the session manifest
     * @param activeSession           the working session open now, or {@code null}
     * @param activeSessionProjectDir the folder that session belongs to, or {@code null}
     * @param sessionOpening          the session being opened now, or {@code null}
     * @param currentProjectFolder    the project manager's open folder — the folder a close closes — or
     *                                {@code null}
     * @param clock                   the session's end time
     */
    SessionJournalEnding(ProjectJournalCoordinator journal, SessionManager sessionManager,
                         Supplier<WorkingSession> activeSession, Supplier<Path> activeSessionProjectDir,
                         Supplier<SessionOpening> sessionOpening, Supplier<Path> currentProjectFolder,
                         InstantSource clock) {
        this.journal = Objects.requireNonNull(journal, "journal must not be null");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.activeSession = Objects.requireNonNull(activeSession, "activeSession must not be null");
        this.activeSessionProjectDir = Objects.requireNonNull(activeSessionProjectDir,
                "activeSessionProjectDir must not be null");
        this.sessionOpening = Objects.requireNonNull(sessionOpening, "sessionOpening must not be null");
        this.currentProjectFolder = Objects.requireNonNull(currentProjectFolder,
                "currentProjectFolder must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public void detach() {
        journal.detachFromBus();
    }

    @Override
    public Runnable prepare(JournalEnd mode) {
        Objects.requireNonNull(mode, "mode must not be null");
        Path folder = currentProjectFolder.get();
        boolean journalOfTheFolder = folder != null && folder.equals(journal.currentProjectDirectory());
        WorkingSession session = activeSession.get();
        WorkingSession sessionOfTheFolder = folder != null && folder.equals(activeSessionProjectDir.get())
                ? session : null;
        SessionOpening opening = sessionOpening.get();
        SessionOpening openingOfTheFolder = folder != null && opening != null && folder.equals(opening.projectDir())
                ? opening : null;
        return () -> {
            if (folder == null) {
                return;
            }
            WorkingSession outgoing = sessionOfTheFolder != null ? sessionOfTheFolder
                    : openingOfTheFolder == null ? null : openingOfTheFolder.opened().join().orElse(null);
            switch (mode) {
                case SEAL -> {
                    if (journalOfTheFolder) {
                        journal.closeCurrentFor(folder, outgoing);
                    } else {
                        closeTheManifestAlone(folder, outgoing);
                    }
                }
                case DRAIN_UNSEALED -> {
                    if (journalOfTheFolder) {
                        journal.drainWithoutSealFor(folder, outgoing);
                    }
                }
                case DRAIN_WITHOUT_MANIFEST -> {
                    if (journalOfTheFolder) {
                        journal.drainWithoutSealFor(folder, null);
                    }
                }
            }
        };
    }

    /**
     * {@link JournalEnd#SEAL} of a folder with no journal of its own open:
     * its session manifest records the close, unless its journal was last
     * closed unsealed. A manifest that cannot be written is logged.
     */
    private void closeTheManifestAlone(Path folder, WorkingSession session) {
        if (session == null) {
            return;
        }
        if (journal.closedUnsealed(folder)) {
            LOG.fine(() -> "The session of " + folder + " is not closed as clean: its journal was closed unsealed");
            return;
        }
        try {
            sessionManager.closeSession(folder, session, clock.instant());
        } catch (IOException notClosed) {
            LOG.log(Level.WARNING, notClosed, () -> "The session manifest of " + folder + " could not be closed");
        }
    }
}

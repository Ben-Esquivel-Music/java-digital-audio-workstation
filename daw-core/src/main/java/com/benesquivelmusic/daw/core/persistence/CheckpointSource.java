package com.benesquivelmusic.daw.core.persistence;

import java.io.IOException;

/**
 * What the {@link CheckpointManager}'s scheduled checkpoints capture (story
 * 331; Persistence Integrity book §2.3, §4.2): the live project, read only
 * on the thread that owns it. The manager calls every method here on the
 * owner executor it was installed with
 * ({@link CheckpointManager#setCaptureSource}) — in the application, the
 * JavaFX application thread — and hands the {@link Renderer} the capture
 * returns to its own writer thread, which renders and writes the checkpoint
 * without touching the model.
 *
 * <p>The application's source is the open project:
 * {@code revision()} is {@code project.getEditRevision()},
 * {@code isClean()} is {@code !project.isDirty()}, and {@code capture()}
 * is {@code serializer.capture(project)::toXml}.</p>
 */
public interface CheckpointSource {

    /**
     * The project's edit revision now
     * ({@link com.benesquivelmusic.daw.core.project.DawProject#getEditRevision()}).
     * A tick whose revision is already checkpointed writes nothing (ruling
     * F4). Called on the owner thread.
     *
     * @return the current edit revision
     */
    long revision();

    /**
     * Whether the project holds no unsaved change — it is as it was last
     * opened or saved. With the revision, it decides whether the first tick
     * after an open may skip (ruling G3). Called on the owner thread, right
     * after {@link #revision()}.
     *
     * @return {@code true} when the project is clean
     */
    boolean isClean();

    /**
     * Captures the project as it is now, on the owner thread, and returns
     * what renders it later: the whole model walk happens here, and the
     * renderer must not read the live model. A capture that throws — a
     * {@link java.util.ConcurrentModificationException} included — is
     * reported as a failed checkpoint; nothing is written.
     *
     * @return the renderer of this capture, called once on the checkpoint writer's thread
     * @throws IOException if the capture cannot be produced
     */
    Renderer capture() throws IOException;

    /** Renders a capture to the checkpoint's content, on the checkpoint writer's thread. */
    @FunctionalInterface
    interface Renderer {

        /**
         * @return the complete checkpoint document
         * @throws IOException if the capture cannot be rendered
         */
        String render() throws IOException;
    }
}

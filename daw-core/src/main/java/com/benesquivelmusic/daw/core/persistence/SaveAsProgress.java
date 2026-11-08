package com.benesquivelmusic.daw.core.persistence;

/**
 * What a Save As copy reports while it runs (story 333, ruling R5;
 * {@link ProjectManager#publishCopy}). Both methods are called on the
 * copying worker, never on the JavaFX application thread: an implementation
 * that updates a view hands the values to that thread itself (Control
 * Synchronization book §2.6's one seam) and must not block. A method that
 * throws is logged and does not stop the copy.
 */
public interface SaveAsProgress {

    /** Reports nothing. */
    SaveAsProgress NONE = new SaveAsProgress() {
        @Override
        public void copied(long bytesCopied, long bytesToCopy) {
        }

        @Override
        public void writingHead() {
        }
    };

    /**
     * The copy's files so far: called once with {@code 0} before the first
     * file, and after each file.
     *
     * @param bytesCopied the bytes of the files copied so far
     * @param bytesToCopy the bytes of every file the copy takes; the rendered {@code project.daw} not included
     */
    void copied(long bytesCopied, long bytesToCopy);

    /**
     * Every file is copied and {@code project.daw} is about to be written,
     * last: the copy asks {@code cancelled} once more when this returns — a
     * cancel taken before this call is seen there — and from then on it can
     * no longer be cancelled; a cancel asked for after it ends as a published
     * copy.
     */
    void writingHead();
}

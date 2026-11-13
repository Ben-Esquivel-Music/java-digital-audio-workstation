package com.benesquivelmusic.daw.app.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dedupe key of a failure (story 336; Failure Surfacing book §3.1): the
 * category, a declared subject or the anchor
 * frame, and the unwrapped exception's class.
 */
class FingerprintTest {

    private static final String HOST_CLASS = "com.benesquivelmusic.daw.app.ui.CommandPaletteView";

    @Test
    void withoutASubjectTheKeyIsCategoryClassAndAnchor() {
        IllegalStateException failure = withFrames(new IllegalStateException("x"),
                frame("java.base", "java.util.Objects", "requireNonNull", 233),
                frame("daw.app", HOST_CLASS, "executeSelected", 302),
                frame("javafx.base", "com.sun.javafx.event.CompositeEventHandler", "dispatchBubblingEvent", 86));

        Fingerprint fingerprint = Fingerprint.of(FailureFunnel.UI_HANDLER, Optional.empty(), failure);

        assertThat(fingerprint.key()).as("the first host frame past the JDK's")
                .isEqualTo("ui.handler|java.lang.IllegalStateException|" + HOST_CLASS + ".executeSelected:302");
        assertThat(fingerprint.anchoredInPlugin()).isFalse();
        assertThat(fingerprint).hasToString(fingerprint.key());
    }

    @Test
    void withADeclaredSubjectTheKeyIsCategorySubjectAndClass() {
        Fingerprint fingerprint = Fingerprint.of(DeclaredSubject.PLUGIN_DISPOSE, Optional.of("Vintage Delay"),
                withFrames(new IllegalStateException(), frame("daw.app", HOST_CLASS, "run", 1)));

        assertThat(fingerprint.key()).isEqualTo("plugin.dispose|Vintage Delay|java.lang.IllegalStateException");
    }

    @Test
    void aPluginFrameAnchorsBeforeTheHostFrameThatCalledIt() {
        IllegalStateException failure = withFrames(new IllegalStateException(),
                frame("java.base", "java.util.ArrayList", "get", 427),
                frame(null, "com.example.Plugin", "dispose", 10),
                frame("daw.core", "com.benesquivelmusic.daw.core.mixer.InsertSlot", "disposeAfterQuiescence", 350));

        Fingerprint fingerprint = Fingerprint.of(FailureFunnel.THREAD_UNCAUGHT, Optional.empty(), failure);

        assertThat(fingerprint.anchor()).isEqualTo("com.example.Plugin.dispose:10");
        assertThat(fingerprint.anchoredInPlugin()).isTrue();
    }

    @Test
    void withNoHostOrPluginFrameTheAnchorIsTheThrowSite() {
        IllegalStateException failure = withFrames(new IllegalStateException(),
                frame("java.base", "java.util.Objects", "requireNonNull", 233),
                frame("java.base", "java.lang.Thread", "run", 1583));

        assertThat(Fingerprint.of(FailureFunnel.THREAD_UNCAUGHT, Optional.empty(), failure).anchor())
                .isEqualTo("java.util.Objects.requireNonNull:233");
    }

    @Test
    void withNoFramesTheAnchorIsADash() {
        OutOfMemoryError failure = withFrames(new OutOfMemoryError("Java heap space"));

        Fingerprint fingerprint = Fingerprint.of(FailureFunnel.THREAD_UNCAUGHT, Optional.empty(), failure);

        assertThat(fingerprint.key()).isEqualTo("thread.uncaught|java.lang.OutOfMemoryError|-");
    }

    @Test
    void twoThrowPointsInOneMethodAndTwoClassesFromOneLineStayApart() {
        StackTraceElement line10 = frame("daw.app", HOST_CLASS, "run", 10);
        StackTraceElement line20 = frame("daw.app", HOST_CLASS, "run", 20);

        String first = Fingerprint.of(FailureFunnel.UI_HANDLER, Optional.empty(),
                withFrames(new IllegalStateException(), line10)).key();
        String secondLine = Fingerprint.of(FailureFunnel.UI_HANDLER, Optional.empty(),
                withFrames(new IllegalStateException(), line20)).key();
        String otherClass = Fingerprint.of(FailureFunnel.UI_HANDLER, Optional.empty(),
                withFrames(new NullPointerException(), line10)).key();

        assertThat(first).isNotEqualTo(secondLine).isNotEqualTo(otherClass);
    }

    @Test
    void anUnreadableStackGivesTheDashAnchorWithoutThrowing() {
        RuntimeException unreadable = new RuntimeException() {
            @Override
            public StackTraceElement[] getStackTrace() {
                throw new NoClassDefFoundError("com/example/Gone");
            }
        };

        Fingerprint fingerprint = Fingerprint.of(FailureFunnel.THREAD_UNCAUGHT, Optional.empty(), unreadable);

        assertThat(fingerprint.anchor()).isEqualTo("-");
        assertThat(fingerprint.exceptionClass()).isEqualTo(unreadable.getClass().getName());
    }

    private static StackTraceElement frame(String module, String className, String method, int line) {
        return new StackTraceElement(null, module, null, className, method,
                className.substring(className.lastIndexOf('.') + 1) + ".java", line);
    }

    private static <T extends Throwable> T withFrames(T failure, StackTraceElement... frames) {
        failure.setStackTrace(frames);
        return failure;
    }
}

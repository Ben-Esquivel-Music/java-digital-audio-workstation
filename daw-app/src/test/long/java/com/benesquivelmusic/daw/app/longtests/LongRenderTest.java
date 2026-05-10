package com.benesquivelmusic.daw.app.longtests;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Marks a test as a long-running render / export end-to-end test
 * (story 209).
 *
 * <p>Activates the {@link LongTestHarness} JUnit 5 extension which:</p>
 * <ul>
 *   <li>Provisions a per-test temporary working directory and
 *       injects it as a {@link java.nio.file.Path} parameter via
 *       {@link LongTestHarness}'s {@code ParameterResolver}.</li>
 *   <li>Records open file-descriptor counts at {@code @BeforeEach}
 *       and re-checks them at {@code @AfterEach}, failing if the
 *       count grows beyond a small slack threshold.</li>
 *   <li>Enforces the {@link #budgetSeconds()} wall-clock budget — a
 *       test that exceeds {@code 2 ×} its budget fails with a
 *       performance-regression message.</li>
 * </ul>
 *
 * <p>Tests carrying this annotation are intentionally <em>not</em> on
 * the default Surefire test source path; they live under
 * {@code daw-app/src/test/long/java} and run only under the
 * {@code long-tests} Maven profile.</p>
 *
 * @see LongTestHarness
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Tag("long")
@ExtendWith(LongTestHarness.class)
public @interface LongRenderTest {

    /**
     * Documented expected wall-clock budget (in seconds) for this test
     * on a reference CI runner. The harness fails the test if the
     * actual wall-clock duration exceeds {@code 2 × budgetSeconds()}.
     */
    double budgetSeconds();

    /** Free-form description of what this long test verifies. */
    String description() default "";
}

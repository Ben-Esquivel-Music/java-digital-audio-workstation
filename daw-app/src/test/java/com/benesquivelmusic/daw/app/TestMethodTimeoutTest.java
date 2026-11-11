package com.benesquivelmusic.daw.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The suite's per-test timeout reaches its test methods (story 334, ruling
 * R-T2). JUnit ignores a timeout key its {@link Timeout} annotation does not
 * name, as it ignored the earlier {@code junit.jupiter.execution.timeout.test.default},
 * which left the forked JVM's last-resort bound as the only guard of a hung
 * test method. So every timeout key in the suite's
 * {@code junit-platform.properties} is one JUnit names, the test-method key
 * gives 60 s, and a test body runs on the timeout's own thread, not on
 * {@code main}.
 */
class TestMethodTimeoutTest {

    private static final String SUITE_PROPERTIES = "/junit-platform.properties";
    private static final String TIMEOUT_KEY_PREFIX = "junit.jupiter.execution.timeout";
    private static final String TESTABLE_METHOD_KEY = "junit.jupiter.execution.timeout.testable.method.default";

    @Test
    void everyTimeoutKeyInTheSuitesJUnitPropertiesIsOneJUnitReads() throws Exception {
        Properties suite = suiteProperties();
        List<String> timeoutKeys = suite.stringPropertyNames().stream()
                .filter(key -> key.startsWith(TIMEOUT_KEY_PREFIX))
                .sorted()
                .toList();
        Set<String> namedByJUnit = timeoutKeysNamedByJUnit();

        assertThat(namedByJUnit).as("JUnit's Timeout names its keys in *_PROPERTY_NAME constants").isNotEmpty();
        assertThat(timeoutKeys).as("the suite sets timeout keys").isNotEmpty();
        assertThat(timeoutKeys).as("every timeout key in %s is one JUnit's Timeout names", SUITE_PROPERTIES)
                .isSubsetOf(namedByJUnit);
        assertThat(suite.getProperty(TESTABLE_METHOD_KEY)).as("the test-method timeout").isEqualTo("60 s");
    }

    @Test
    void aTestMethodRunsOnTheTimeoutsOwnThreadNotOnMain() {
        assertThat(Thread.currentThread().getName()).as("the thread running this test body").isNotEqualTo("main");
    }

    private static Properties suiteProperties() throws IOException {
        Properties suite = new Properties();
        try (InputStream in = TestMethodTimeoutTest.class.getResourceAsStream(SUITE_PROPERTIES)) {
            assertThat(in).as("%s on the test class path", SUITE_PROPERTIES).isNotNull();
            suite.load(in);
        }
        return suite;
    }

    /** The value of each {@code public static final String} field of {@link Timeout} whose name ends {@code _PROPERTY_NAME}. */
    private static Set<String> timeoutKeysNamedByJUnit() throws IllegalAccessException {
        Set<String> keys = new TreeSet<>();
        for (Field field : Timeout.class.getFields()) {
            int modifiers = field.getModifiers();
            if (field.getName().endsWith("_PROPERTY_NAME") && field.getType() == String.class
                    && Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers)) {
                keys.add((String) field.get(null));
            }
        }
        return keys;
    }
}

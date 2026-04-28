package com.benesquivelmusic.daw.sdk.mastering.album;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IsrcValidatorTest {

    @Test
    void shouldAcceptHyphenatedForm() {
        assertThat(IsrcValidator.isValid("US-RC1-26-00042")).isTrue();
    }

    @Test
    void shouldAcceptTightForm() {
        assertThat(IsrcValidator.isValid("USRC12600042")).isTrue();
    }

    @Test
    void shouldAcceptLowercaseAndNormalize() {
        assertThat(IsrcValidator.isValid("us-rc1-26-00042")).isTrue();
        assertThat(IsrcValidator.normalize("us-rc1-26-00042")).isEqualTo("US-RC1-26-00042");
    }

    @Test
    void shouldRejectNull() {
        assertThat(IsrcValidator.isValid(null)).isFalse();
    }

    @Test
    void shouldRejectMalformed() {
        // Wrong country length, too short, too long, non-digit year, etc.
        assertThat(IsrcValidator.isValid("USA-RC1-26-00042")).isFalse();
        assertThat(IsrcValidator.isValid("US-RC1-26-0042")).isFalse();
        assertThat(IsrcValidator.isValid("US-RC1-AB-00042")).isFalse();
        assertThat(IsrcValidator.isValid("12-RC1-26-00042")).isFalse();
        assertThat(IsrcValidator.isValid("US-RC!-26-00042")).isFalse();
        assertThat(IsrcValidator.isValid("")).isFalse();
        assertThat(IsrcValidator.isValid("US-RC1-26-99999-extra")).isFalse();
    }

    @Test
    void normalizeShouldRejectInvalid() {
        assertThatThrownBy(() -> IsrcValidator.normalize("not-an-isrc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid ISRC");
    }

    @Test
    void normalizeShouldRejectNull() {
        assertThatThrownBy(() -> IsrcValidator.normalize(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void autoHyphenateShouldFormatProgressively() {
        assertThat(IsrcValidator.autoHyphenate("")).isEmpty();
        assertThat(IsrcValidator.autoHyphenate("U")).isEqualTo("U");
        assertThat(IsrcValidator.autoHyphenate("US")).isEqualTo("US");
        assertThat(IsrcValidator.autoHyphenate("USR")).isEqualTo("US-R");
        assertThat(IsrcValidator.autoHyphenate("USRC1")).isEqualTo("US-RC1");
        assertThat(IsrcValidator.autoHyphenate("USRC12")).isEqualTo("US-RC1-2");
        assertThat(IsrcValidator.autoHyphenate("USRC126")).isEqualTo("US-RC1-26");
        assertThat(IsrcValidator.autoHyphenate("USRC1260")).isEqualTo("US-RC1-26-0");
        assertThat(IsrcValidator.autoHyphenate("USRC1260004")).isEqualTo("US-RC1-26-0004");
        assertThat(IsrcValidator.autoHyphenate("USRC12600042")).isEqualTo("US-RC1-26-00042");
        // Already hyphenated input is preserved.
        assertThat(IsrcValidator.autoHyphenate("US-RC1-26-00042")).isEqualTo("US-RC1-26-00042");
        // Excess characters are truncated.
        assertThat(IsrcValidator.autoHyphenate("USRC12600042EXTRA")).isEqualTo("US-RC1-26-00042");
        // Lowercase becomes uppercase.
        assertThat(IsrcValidator.autoHyphenate("usrc1")).isEqualTo("US-RC1");
    }

    @Test
    void nextShouldIncrementDesignation() {
        assertThat(IsrcValidator.next("US-RC1-26-00042")).isEqualTo("US-RC1-26-00043");
        assertThat(IsrcValidator.next("US-RC1-26-00099")).isEqualTo("US-RC1-26-00100");
        assertThat(IsrcValidator.next("US-RC1-26-99998")).isEqualTo("US-RC1-26-99999");
    }

    @Test
    void nextShouldOverflowAt99999() {
        assertThatThrownBy(() -> IsrcValidator.next("US-RC1-26-99999"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overflow");
    }
}

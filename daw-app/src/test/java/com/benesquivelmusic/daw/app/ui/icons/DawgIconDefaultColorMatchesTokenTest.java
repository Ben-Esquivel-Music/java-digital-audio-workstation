package com.benesquivelmusic.daw.app.ui.icons;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the duplication between {@link DawgIcon#DEFAULT_ICON_COLOR}
 * (the pre-CSS fallback used when a {@code DawgIcon} is instantiated
 * outside a styled scene) and the {@code -text-hi} design token in
 * {@code styles.css}.
 *
 * <p>The fallback is documented to stay in sync with {@code -text-hi};
 * this test makes that invariant mechanical so a theme retune in
 * {@code styles.css} cannot silently diverge from the Java constant.</p>
 */
class DawgIconDefaultColorMatchesTokenTest {

    private static final String STYLES_CSS =
            "/com/benesquivelmusic/daw/app/ui/styles.css";

    /** Matches a {@code -text-hi: #RRGGBB;} declaration in styles.css. */
    private static final Pattern TEXT_HI =
            Pattern.compile("-text-hi\\s*:\\s*#([0-9A-Fa-f]{6})\\s*;");

    @Test
    void defaultIconColorMustMatchTextHiToken() throws IOException {
        String css = readStylesCss();
        Matcher m = TEXT_HI.matcher(css);
        assertThat(m.find())
                .as("styles.css must declare the -text-hi design token")
                .isTrue();

        Color tokenColor = Color.web("#" + m.group(1));
        assertThat(DawgIcon.DEFAULT_ICON_COLOR)
                .as("DawgIcon.DEFAULT_ICON_COLOR must stay in sync with the " +
                        "-text-hi token in styles.css")
                .isEqualTo(tokenColor);
    }

    private static String readStylesCss() throws IOException {
        try (InputStream in = DawgIconDefaultColorMatchesTokenTest.class
                .getResourceAsStream(STYLES_CSS)) {
            assertThat(in)
                    .as("styles.css must be on the classpath at " + STYLES_CSS)
                    .isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

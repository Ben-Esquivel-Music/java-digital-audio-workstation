package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.core.persistence.LockStatus;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333 (Persistence Integrity book §7.3; UI Design Book §3.1 "tokens by
 * role, never by hex"; javafx-application-design skill §8 "CSS styling and
 * theming", §9 "Visual design language"): the lock indicator's fills come
 * from the application stylesheet through its state classes — a held lock on
 * the {@code -ok} role, a read-only open on {@code -warn}, a lock another
 * session took on {@code -danger}, its text on {@code -text-on-accent} —
 * resolved in a real scene under the theme ({@code BrowserHoverStyleTest}'s
 * idiom: a sibling probe styled with the token gives the expected colour, so
 * no hex is pinned). The indicator's source writes no inline style and holds
 * no colour.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class LockStatusIndicatorStyleTest {

    @Test
    void eachLockStateResolvesToItsRoleTokenFromTheStylesheet() throws Exception {
        assertResolves(LockStatus.HELD, "-ok");
        assertResolves(LockStatus.READ_ONLY, "-warn");
        assertResolves(LockStatus.STOLEN, "-danger");
    }

    @Test
    void theIndicatorsSourceWritesNoInlineStyleAndNoColour() throws Exception {
        String source = Files.readString(SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui/LockStatusIndicator.java"),
                StandardCharsets.UTF_8);
        String code = SourceScanSupport.stripComments(source);

        assertThat(code).doesNotContain("setStyle(").doesNotContain("-fx-background-color")
                .doesNotContainPattern("#[0-9a-fA-F]{6}");
    }

    private static void assertResolves(LockStatus status, String token) throws Exception {
        Paint[] resolved = onFx(() -> {
            LockStatusIndicator indicator = new LockStatusIndicator();
            indicator.show(status, null);
            Region fillProbe = new Region();
            fillProbe.setStyle("-fx-background-color: " + token + ";");
            Label textProbe = new Label("probe");
            textProbe.setStyle("-fx-text-fill: -text-on-accent;");
            HBox root = new HBox(indicator, fillProbe, textProbe);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, 400, 40);
            ThemeManager.getDefault().applyTo(scene);
            root.applyCss();
            root.layout();
            return new Paint[] {
                    indicator.getBackground().getFills().getFirst().getFill(),
                    fillProbe.getBackground().getFills().getFirst().getFill(),
                    indicator.getTextFill(),
                    textProbe.getTextFill()
            };
        });

        assertThat(resolved[0]).as("%s's fill is the %s role", status, token).isEqualTo(resolved[1])
                .isInstanceOf(Color.class);
        assertThat(resolved[2]).as("%s's text is -text-on-accent", status).isEqualTo(resolved[3]);
    }

    private static <T> T onFx(Supplier<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        javafx.application.Platform.runLater(() -> {
            try {
                result.set(action.get());
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        });
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        if (thrown.get() != null) {
            throw new AssertionError("the FX action threw", thrown.get());
        }
        return result.get();
    }
}

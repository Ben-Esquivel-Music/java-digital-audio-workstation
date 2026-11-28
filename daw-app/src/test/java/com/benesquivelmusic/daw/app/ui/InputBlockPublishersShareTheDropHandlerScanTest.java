package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every publisher of captured input blocks tells the subscriber it dropped a
 * block for (story 338, ruling E208), so a block the engine's capture
 * subscriber missed always reaches the take's dropouts. The files that hold a
 * {@code SubmissionPublisher} of {@code AudioBlock} by its declared type, that
 * build a {@code SubmissionPublisher} and name {@code AudioBlock}, or that
 * call the SDK support's {@code currentInputPublisher()} or name it in a
 * method reference, are a closed set. In each, every offer passes the SDK's
 * shared drop handler, except the support's one generic offer, whose input
 * caller passes it; a file that builds only another kind of publisher builds
 * nothing else. A publisher reached out of a source scan's sight — built
 * through a factory, or held under a type that names no {@code AudioBlock}
 * and reached through no call of that getter — is outside what it can see;
 * none exists in the sources today.
 */
@Tag("slow")
class InputBlockPublishersShareTheDropHandlerScanTest {

    private static final List<String> MODULES =
            List.of("daw-sdk", "daw-core", "daw-fx", "daw-acoustics", "daw-app");

    private static final String SDK_AUDIO = "daw-sdk/src/main/java/com/benesquivelmusic/daw/sdk/audio/";
    private static final String SUPPORT = SDK_AUDIO + "AudioBackendSupport.java";
    private static final String JAVA_SOUND = SDK_AUDIO + "JavaxSoundBackend.java";
    private static final String ASIO_SHIM = SDK_AUDIO + "AsioBufferSwitchShim.java";
    private static final String ASIO_BACKEND = SDK_AUDIO + "AsioBackend.java";
    private static final String MOCK = SDK_AUDIO + "MockAudioBackend.java";
    private static final String ADAPTER =
            "daw-core/src/main/java/com/benesquivelmusic/daw/core/audio/CallbackBackendAdapter.java";
    private static final String SHARED_HANDLER = "DROP_INPUT_BLOCK";
    private static final Pattern BUILDS_A_PUBLISHER =
            Pattern.compile("new(java\\.util\\.concurrent\\.)?SubmissionPublisher<");
    private static final Pattern NAMES_AUDIO_BLOCK = Pattern.compile("(?<![A-Za-z0-9_])AudioBlock(?![A-Za-z0-9_])");
    /** The one publisher AsioBackend and MockAudioBackend build: their clock-lock events'. */
    private static final Pattern CLOCK_LOCK_PUBLISHER = Pattern.compile(
            "SubmissionPublisher<ClockLockEvent>clockLockPublisher=new(java\\.util\\.concurrent\\.)?SubmissionPublisher<>\\(\\)");

    @Test
    void everyInputBlockPublisherOffersWithTheSharedDropHandler() throws IOException {
        Path repository = SourceScanSupport.locateDawAppModule().getParent();
        Map<String, String> holders = new TreeMap<>();
        int scanned = 0;
        for (String module : MODULES) {
            Path sources = repository.resolve(module).resolve("src/main/java");
            assertThat(sources).as("the sources of %s", module).isDirectory();
            try (Stream<Path> files = Files.walk(sources)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    scanned++;
                    String stripped = SourceScanSupport.stripStringLiterals(SourceScanSupport.stripComments(
                            Files.readString(file, StandardCharsets.UTF_8)));
                    // Before the whitespace goes: "AudioBlock block" would read as one word.
                    boolean namesAudioBlock = NAMES_AUDIO_BLOCK.matcher(stripped).find();
                    String code = stripped.replaceAll("\\s+", "");
                    boolean declaresOne = code.contains("SubmissionPublisher<AudioBlock>")
                            || code.contains("SubmissionPublisher<com.benesquivelmusic.daw.sdk.audio.AudioBlock>");
                    boolean buildsOneBesideAudioBlocks = BUILDS_A_PUBLISHER.matcher(code).find() && namesAudioBlock;
                    boolean reachesTheSupportsOne = code.contains("currentInputPublisher(")
                            || code.contains("::currentInputPublisher");
                    if (declaresOne || buildsOneBesideAudioBlocks || reachesTheSupportsOne) {
                        holders.put(repository.relativize(file).toString().replace('\\', '/'), code);
                    }
                }
            }
        }

        assertThat(scanned).as("non-vacuity: the modules' sources were scanned").isGreaterThan(500);
        assertThat(holders.keySet()).as("the files that hold a publisher of captured input blocks, build one"
                        + " beside them, or reach the support's")
                .containsExactlyInAnyOrder(SUPPORT, ADAPTER, JAVA_SOUND, ASIO_SHIM, ASIO_BACKEND, MOCK);

        String support = holders.get(SUPPORT);
        assertThat(occurrences(support, "InputBlockLossListener::reportDrop")).as("the support's one handler").isOne();
        assertThat(offerHandlers(support)).as("the support's one offer, generic over the item")
                .containsExactly("dropHandler");
        assertThat(callArguments(support, "offerUnlessClosed(").stream()
                .filter(arguments -> !arguments.startsWith("SubmissionPublisher<")).toList())
                .as("the generic offer's callers: input blocks with the shared handler, device events with their own")
                .containsExactlyInAnyOrder("stream,block," + SHARED_HANDLER, "current,event,DROP_DEVICE_EVENT");

        String adapter = holders.get(ADAPTER);
        assertThat(occurrences(adapter, "InputBlockLossListener::reportDrop")).as("the adapter's one handler").isOne();
        assertThat(offerHandlers(adapter)).as("the adapter's drain offers once, with the shared handler")
                .containsExactly(SHARED_HANDLER);
        assertThat(adapter).doesNotContain(".submit(");

        for (String publisher : List.of(JAVA_SOUND, ASIO_SHIM)) {
            assertThat(offerHandlers(holders.get(publisher))).as("%s offers nothing itself", publisher).isEmpty();
            assertThat(holders.get(publisher)).as("%s publishes only through the support", publisher)
                    .doesNotContain(".submit(").contains("support.publishInput(");
        }

        for (String clockLocks : List.of(ASIO_BACKEND, MOCK)) {
            String code = holders.get(clockLocks);
            assertThat(BUILDS_A_PUBLISHER.matcher(CLOCK_LOCK_PUBLISHER.matcher(code).replaceAll("")).find())
                    .as("%s builds no publisher but its clock-lock events'", clockLocks).isFalse();
            assertThat(offerHandlers(code)).as("%s offers nothing", clockLocks).isEmpty();
            assertThat(occurrences(code, ".submit(")).as("%s submits only clock-lock events", clockLocks)
                    .isEqualTo(occurrences(code, "clockLockPublisher.submit("));
        }
    }

    private static int occurrences(String code, String token) {
        int count = 0;
        for (int at = code.indexOf(token); at >= 0; at = code.indexOf(token, at + token.length())) {
            count++;
        }
        return count;
    }

    /** The last argument of every {@code .offer(} call in {@code code}, which holds no whitespace. */
    private static List<String> offerHandlers(String code) {
        List<String> handlers = new ArrayList<>();
        for (String arguments : callArguments(code, ".offer(")) {
            handlers.add(arguments.substring(lastTopLevelComma(arguments) + 1));
        }
        return handlers;
    }

    /** The argument text of every call {@code opening} begins, up to its matching parenthesis. */
    private static List<String> callArguments(String code, String opening) {
        List<String> calls = new ArrayList<>();
        for (int at = code.indexOf(opening); at >= 0; at = code.indexOf(opening, at + opening.length())) {
            int start = at + opening.length();
            int depth = 1;
            int end = start;
            while (depth > 0) {
                char c = code.charAt(end);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                }
                end++;
            }
            calls.add(code.substring(start, end - 1));
        }
        return calls;
    }

    private static int lastTopLevelComma(String arguments) {
        int depth = 0;
        int last = -1;
        for (int i = 0; i < arguments.length(); i++) {
            char c = arguments.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                last = i;
            }
        }
        return last;
    }
}

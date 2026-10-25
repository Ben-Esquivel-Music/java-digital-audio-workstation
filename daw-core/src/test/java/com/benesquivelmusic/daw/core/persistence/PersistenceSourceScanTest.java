package com.benesquivelmusic.daw.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The source scan of the write/read parity contract over synthetic sources:
 * each check it makes on the real writer and reader files turns red on the
 * code shape it exists to catch, naming the file and line.
 */
class PersistenceSourceScanTest {

    private static final PersistenceSourceScan.TagParameterBuilder BUILD_CHANNEL =
            new PersistenceSourceScan.TagParameterBuilder("Writer.java", "buildChannel",
                    "one body writes two channel kinds");

    /** A writer whose one declared builder is called with literal tags; line numbers are pinned below. */
    private static final String WRITER = """
            final class Writer {
                private Element buildChannel(Document document, String tagName, Channel channel) {
                    Element elem = document.createElement(tagName);
                    elem.setAttribute("volume", "1.0");
                    return elem;
                }

                void write(Document document, Element mixer, Channel master, Channel bus) {
                    mixer.appendChild(buildChannel(document, "master", master));
                    mixer.appendChild(buildChannel(document, "return-bus", bus));
                }
            }
            """;

    /** {@link #WRITER}'s last builder call, on line 10, after which a test adds a line. */
    private static final String RETURN_BUS_CALL = """
                    mixer.appendChild(buildChannel(document, "return-bus", bus));
            """;

    /** {@link #WRITER}'s builder line that creates the element on its tag parameter, line 3. */
    private static final String BUILDER_CREATE = """
                    Element elem = document.createElement(tagName);
            """;

    @Test
    void aDeclaredBuildersTagsAreReadFromItsCalls() {
        PersistenceSourceScan.WriterLiterals literals = scanWriter(WRITER, List.of(BUILD_CHANNEL));

        assertThat(literals.violations()).isEmpty();
        assertThat(literals.elements()).extracting(PersistenceSourceScan.Literal::name, PersistenceSourceScan.Literal::line)
                .containsExactly(tuple("master", 9),
                        tuple("return-bus", 10));
        assertThat(literals.attributes()).extracting(PersistenceSourceScan.Literal::name).containsExactly("volume");
    }

    @Test
    void aTagParameterCreateElementOutsideADeclaredBuilderNamesItsLine() {
        String writer = WRITER.replace("""
                    void write(""", """
                    private Element appendChild(Document document, Element parent, String tagName) {
                        Element child = document.createElement(tagName);
                        parent.appendChild(child);
                        return child;
                    }

                    void write(""").replace("""
                        mixer.appendChild(buildChannel(document, "return-bus", bus));
                """, """
                        mixer.appendChild(buildChannel(document, "return-bus", bus));
                        appendChild(document, mixer, "take-stack");
                """);

        PersistenceSourceScan.WriterLiterals literals = scanWriter(writer, List.of(BUILD_CHANNEL));

        assertThat(literals.violations()).containsExactly(
                "createElement with a tag that is not a literal, outside a declared tag-parameter builder:"
                        + " Writer.java:9  Element child = document.createElement(tagName);");
        assertThat(literals.elements()).extracting(PersistenceSourceScan.Literal::name)
                .as("the helper's tag is invisible to the guard").doesNotContain("take-stack");
    }

    @Test
    void anAnnotatedTagParameterHelperAfterADeclaredBuilderNamesItsLine() {
        String writer = WRITER.replace("""
                    void write(""", """
                    @SuppressWarnings("unused") private Element tagged(Document document, String tagName) {
                        Element child = document.createElement(tagName);
                        child.setAttribute("tagged", "true");
                        return child;
                    }

                    void write(""").replace(RETURN_BUS_CALL, RETURN_BUS_CALL + """
                        mixer.appendChild(tagged(document, "take-stack"));
                """);

        PersistenceSourceScan.WriterLiterals literals = scanWriter(writer, List.of(BUILD_CHANNEL));

        assertThat(literals.violations()).containsExactly(
                "createElement with a tag that is not a literal, outside a declared tag-parameter builder:"
                        + " Writer.java:9  Element child = document.createElement(tagName);");
    }

    @Test
    void aBoundedGenericTagParameterHelperAfterADeclaredBuilderNamesItsLine() {
        String writer = WRITER.replace("""
                    void write(""", """
                    private <T extends Comparable<T>> Element keyed(Document document, String tagName, T key) {
                        Element child = document.createElement(tagName);
                        child.setAttribute("key", key.toString());
                        return child;
                    }

                    void write(""").replace(RETURN_BUS_CALL, RETURN_BUS_CALL + """
                        mixer.appendChild(keyed(document, "take-stack", 1));
                """);

        PersistenceSourceScan.WriterLiterals literals = scanWriter(writer, List.of(BUILD_CHANNEL));

        assertThat(literals.violations()).containsExactly(
                "createElement with a tag that is not a literal, outside a declared tag-parameter builder:"
                        + " Writer.java:9  Element child = document.createElement(tagName);");
    }

    @Test
    void aFieldInitializerLambdaThatCreatesAnElementOnItsTagNamesItsLine() {
        String writer = WRITER.replace("""
                    void write(""", """
                    private static final BiFunction<Document, String, Element> ELEMENT =
                            (document, tagName) -> document.createElement(tagName);

                    void write(""").replace(RETURN_BUS_CALL, RETURN_BUS_CALL + """
                        mixer.appendChild(ELEMENT.apply(document, "take-stack"));
                """);

        PersistenceSourceScan.WriterLiterals literals = scanWriter(writer, List.of(BUILD_CHANNEL));

        assertThat(literals.violations()).containsExactly(
                "createElement with a tag that is not a literal, in no method the scan recognises: Writer.java:9"
                        + "  (document, tagName) -> document.createElement(tagName);");
    }

    @Test
    void bracesInLiteralsInsideABuilderDoNotStretchItsBodyOverAFieldInitializer() {
        // Counted as code, the three literal braces would leave the builder
        // without a closing brace, or stretch it over the lambda below.
        String writer = WRITER.replace(BUILDER_CREATE, BUILDER_CREATE + """
                        String open = "{";
                        char brace = '{';
                        String block = \"""
                                {
                                \""";
                """).replace("""
                    void write(""", """
                    private static final BiFunction<Document, String, Element> ELEMENT =
                            (document, tagName) -> document.createElement(tagName);

                    void write(""");

        PersistenceSourceScan.WriterLiterals literals = scanWriter(writer, List.of(BUILD_CHANNEL));

        assertThat(literals.violations()).containsExactly(
                "createElement with a tag that is not a literal, in no method the scan recognises: Writer.java:14"
                        + "  (document, tagName) -> document.createElement(tagName);");
        assertThat(literals.elements()).extracting(PersistenceSourceScan.Literal::name)
                .containsExactly("master", "return-bus");
    }

    @Test
    void aOneLineAnonymousClassInsideADeclaredBuilderNamesItsLine() {
        // Its method's own tagName parameter shadows the builder's; its body
        // is not the builder's.
        String anonymousClass = "java.util.function.Function<String, Element> maker ="
                + " new java.util.function.Function<String, Element>() { public Element apply(String tagName)"
                + " { return document.createElement(tagName); } };";
        String writer = WRITER.replace(BUILDER_CREATE, BUILDER_CREATE + "        " + anonymousClass + "\n" + """
                        elem.appendChild(maker.apply("take-stack"));
                """);

        PersistenceSourceScan.WriterLiterals literals = scanWriter(writer, List.of(BUILD_CHANNEL));

        assertThat(literals.violations()).containsExactly(
                "createElement with a tag that is not a literal, in no method the scan recognises: Writer.java:4  "
                        + anonymousClass);
    }

    @Test
    void aOneLineLocalClassInsideADeclaredBuilderNamesItsLine() {
        String localClass = "class Maker { Element make(String tagName) { return document.createElement(tagName); } }";
        String writer = WRITER.replace(BUILDER_CREATE, BUILDER_CREATE + "        " + localClass + "\n" + """
                        elem.appendChild(new Maker().make("take-stack"));
                """);

        PersistenceSourceScan.WriterLiterals literals = scanWriter(writer, List.of(BUILD_CHANNEL));

        assertThat(literals.violations()).containsExactly(
                "createElement with a tag that is not a literal, in no method the scan recognises: Writer.java:4  "
                        + localClass);
    }

    /**
     * Each row: what the class is, the class on one line inside the declared
     * builder — its method's own tagName parameter shadows the builder's —
     * and the line that uses it.
     */
    static Stream<Arguments> oneLineClassesInAnyReadShape() {
        return Stream.of(
                Arguments.of("an anonymous class created on an enclosing instance",
                        "Maker maker = this.new Maker() { Element make(String tagName) {"
                                + " return document.createElement(tagName); } };",
                        "elem.appendChild(maker.make(\"take-stack\"));"),
                Arguments.of("an anonymous class of an annotated type",
                        "Maker maker = new @Shared Maker() { Element make(String tagName) {"
                                + " return document.createElement(tagName); } };",
                        "elem.appendChild(maker.make(\"take-stack\"));"),
                Arguments.of("an anonymous class with explicit constructor type arguments",
                        "Maker maker = new <String>Maker() { Element make(String tagName) {"
                                + " return document.createElement(tagName); } };",
                        "elem.appendChild(maker.make(\"take-stack\"));"),
                Arguments.of("a local record whose component's annotation has an array argument",
                        "record Maker(@SuppressWarnings({\"unused\"}) Document d) { Element make(String tagName) {"
                                + " return d.createElement(tagName); } }",
                        "elem.appendChild(new Maker(document).make(\"take-stack\"));"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("oneLineClassesInAnyReadShape")
    void aOneLineQualifiedAnnotatedOrGenericClassInsideADeclaredBuilderNamesItsLine(String shape, String oneLineClass,
                                                                                    String use) {
        String writer = WRITER.replace(BUILDER_CREATE, BUILDER_CREATE + "        " + oneLineClass + "\n"
                + "        " + use + "\n");

        PersistenceSourceScan.WriterLiterals literals = scanWriter(writer, List.of(BUILD_CHANNEL));

        assertThat(literals.violations()).as(shape).containsExactly(
                "createElement with a tag that is not a literal, in no method the scan recognises: Writer.java:4  "
                        + oneLineClass);
    }

    @Test
    void aConstructorThatCreatesAnElementOnItsTagNamesItsLine() {
        String writer = WRITER.replace("""
                    void write(""", """
                    public Writer(Document document, String tagName) {
                        root = document.createElement(tagName);
                    }

                    void write(""");
        String inNoMethod = "createElement with a tag that is not a literal, in no method the scan recognises:"
                + " Writer.java:9  root = document.createElement(tagName);";
        PersistenceSourceScan.TagParameterBuilder constructor = new PersistenceSourceScan.TagParameterBuilder(
                "Writer.java", "Writer", "the root's tag is the caller's");

        assertThat(scanWriter(writer, List.of(BUILD_CHANNEL)).violations()).containsExactly(inNoMethod);
        assertThat(scanWriter(writer, List.of(BUILD_CHANNEL, constructor)).violations())
                .as("public is never read as a return type, so a constructor is no builder even when declared one")
                .containsExactly(inNoMethod, "stale tag-parameter builder Writer.java#Writer — it no longer"
                        + " creates the element its caller names; delete it");
    }

    @Test
    void aBuilderUsedAsAMethodReferenceNamesItsLine() {
        String writer = WRITER.replace(RETURN_BUS_CALL, RETURN_BUS_CALL + """
                        ChannelBuilder slots = this::buildChannel;
                """);

        PersistenceSourceScan.WriterLiterals literals = scanWriter(writer, List.of(BUILD_CHANNEL));

        assertThat(literals.violations()).containsExactly(
                "tag-parameter builder used as a method reference; call it with a literal tag: Writer.java:11"
                        + "  ChannelBuilder slots = this::buildChannel;");
        assertThat(literals.elements()).extracting(PersistenceSourceScan.Literal::name)
                .containsExactly("master", "return-bus");
    }

    @Test
    void aBuilderCallWhoseTagIsNotALiteralNamesItsLine() {
        String writer = WRITER.replace("buildChannel(document, \"return-bus\", bus)",
                "buildChannel(document, tagFor(bus), bus)");

        PersistenceSourceScan.WriterLiterals literals = scanWriter(writer, List.of(BUILD_CHANNEL));

        assertThat(literals.violations()).containsExactly(
                "call of tag-parameter builder buildChannel with a tag that is not a literal: Writer.java:10"
                        + "  mixer.appendChild(buildChannel(document, tagFor(bus), bus));");
        assertThat(literals.elements()).extracting(PersistenceSourceScan.Literal::name).containsExactly("master");
    }

    @Test
    void aBuilderEntryThatNoLongerCreatesItsTagIsStale() {
        PersistenceSourceScan.TagParameterBuilder gone = new PersistenceSourceScan.TagParameterBuilder(
                "Writer.java", "buildSnapshot", "one body writes the snapshot and both slots");

        PersistenceSourceScan.WriterLiterals literals = scanWriter(WRITER, List.of(BUILD_CHANNEL, gone));

        assertThat(literals.violations()).containsExactly("stale tag-parameter builder Writer.java#buildSnapshot"
                + " — it no longer creates the element its caller names; delete it");
    }

    @Test
    void copyingANodeIntoTheDocumentIsAWriteTheGuardCannotRead() {
        String writer = WRITER.replace("""
                        mixer.appendChild(buildChannel(document, "return-bus", bus));
                """, """
                        mixer.appendChild(buildChannel(document, "return-bus", bus));
                        mixer.appendChild(document.importNode(template, true));
                        mixer.appendChild(document.adoptNode(other));
                        mixer.appendChild(mixer.getFirstChild().cloneNode(true));
                """);

        PersistenceSourceScan.WriterLiterals literals = scanWriter(writer, List.of(BUILD_CHANNEL));

        assertThat(PersistenceSourceScan.writesDocumentNames("x.appendChild(document.importNode(n, true));"))
                .isTrue();
        assertThat(literals.violations()).containsExactly(
                "dynamic name the coverage guard cannot see: Writer.java:11"
                        + "  mixer.appendChild(document.importNode(template, true));",
                "dynamic name the coverage guard cannot see: Writer.java:12"
                        + "  mixer.appendChild(document.adoptNode(other));",
                "dynamic name the coverage guard cannot see: Writer.java:13"
                        + "  mixer.appendChild(mixer.getFirstChild().cloneNode(true));");
    }

    /** A reader that looks children up by node and local name, both comparison orders. */
    private static final String READER = """
            final class Reader {
                void read(Element parent) {
                    NodeList children = parent.getChildNodes();
                    for (int i = 0; i < children.getLength(); i++) {
                        Node n = children.item(i);
                        if (n.getNodeType() == Node.ELEMENT_NODE && "take-folders".equals(n.getNodeName())) {
                            readFolders((Element) n);
                        } else if (n.getLocalName().equals("comp-lane")) {
                            readComp((Element) n);
                        }
                    }
                }
            }
            """;

    @Test
    void aNodeNameOrLocalNameComparedWithALiteralIsAnElementLookup() {
        PersistenceSourceScan.ReaderLookups lookups = PersistenceSourceScan.readerLookups("Reader.java", READER,
                List.of());

        assertThat(lookups.violations()).isEmpty();
        assertThat(lookups.elements()).extracting(PersistenceSourceScan.Literal::name, PersistenceSourceScan.Literal::line)
                .containsExactly(tuple("take-folders", 6),
                        tuple("comp-lane", 8));
    }

    @Test
    void aNodeNameUseTheScanCannotReadIsAViolationUnlessDeclared() {
        String reader = READER.replace("""
                            Node n = children.item(i);
                """, """
                            Node n = children.item(i);
                            String kind = n.getNodeName();
                """);
        PersistenceSourceScan.DeclaredNameRead declared = new PersistenceSourceScan.DeclaredNameRead(
                "Reader.java", "String kind = n.getNodeName();", "logs the node kind; looks nothing up");

        assertThat(PersistenceSourceScan.readerLookups("Reader.java", reader, List.of()).violations())
                .containsExactly("node-name use the scan cannot read a looked-up name from: Reader.java:6"
                        + "  String kind = n.getNodeName();");
        assertThat(PersistenceSourceScan.readerLookups("Reader.java", reader, List.of(declared)).violations())
                .as("declared with its reason").isEmpty();
        assertThat(PersistenceSourceScan.readerLookups("Reader.java", READER, List.of(declared)).violations())
                .as("a declared read the file no longer holds")
                .containsExactly("stale declared name read in Reader.java — 'String kind = n.getNodeName();'"
                        + " is gone; delete it");
    }

    /** A reader with one {@code List<Element>} lookup helper, whose comparison is on line 12. */
    private static final String HELPER_READER = """
            final class Reader {
                void read(Element root) {
                    for (Element take : children(root, "take")) {
                        readTake(take);
                    }
                }

                private static List<Element> children(Element parent, String tagName) {
                    List<Element> result = new ArrayList<>();
                    NodeList nodes = parent.getChildNodes();
                    for (int i = 0; i < nodes.getLength(); i++) {
                        if (nodes.item(i) instanceof Element child && child.getTagName().equals(tagName)) {
                            result.add(child);
                        }
                    }
                    return result;
                }
            }
            """;

    @Test
    void aSecondLookupHelperAfterTheListHelperNamesItsLine() {
        String reader = HELPER_READER.replace("""
                            readTake(take);
                        }
                """, """
                            readTake(take);
                        }
                        Element folders = firstChild(root, "take-folders");
                """).replace("""
                        return result;
                    }
                """, """
                        return result;
                    }

                    @SuppressWarnings("unused") private static Element firstChild(Element parent, String tagName) {
                        NodeList nodes = parent.getChildNodes();
                        for (int i = 0; i < nodes.getLength(); i++) {
                            if (nodes.item(i) instanceof Element child && child.getTagName().equals(tagName)) {
                                return child;
                            }
                        }
                        return null;
                    }
                """);

        PersistenceSourceScan.ReaderLookups clean = PersistenceSourceScan.readerLookups("Reader.java", HELPER_READER,
                List.of());
        PersistenceSourceScan.ReaderLookups lookups = PersistenceSourceScan.readerLookups("Reader.java", reader,
                List.of());

        assertThat(clean.violations()).isEmpty();
        assertThat(clean.elements()).extracting(PersistenceSourceScan.Literal::name, PersistenceSourceScan.Literal::line)
                .containsExactly(tuple("take", 3));
        assertThat(lookups.violations()).containsExactly(
                "2 tagName comparisons for 1 declared lookup helper(s) in Reader.java — a second lookup helper"
                        + " hides behind a declared one: Reader.java:13, Reader.java:23",
                "node-name use the scan cannot read a looked-up name from: Reader.java:23"
                        + "  if (nodes.item(i) instanceof Element child && child.getTagName().equals(tagName)) {");
        assertThat(lookups.elements()).extracting(PersistenceSourceScan.Literal::name)
                .as("the second helper's tag is invisible to the scan").doesNotContain("take-folders");
    }

    @Test
    void theCoreLocatorFindsEachPackagesMainSourcesAndFailsClosedOnAMissingOne() {
        for (String corePackage : List.of("persistence", "session", "snapshot")) {
            Path root = PersistenceSourceScan.locateCoreSource(corePackage);
            assertThat(root).as("core/%s", corePackage).isDirectory()
                    .endsWith(Path.of("com", "benesquivelmusic", "daw", "core", corePackage));
        }
        assertThat(PersistenceSourceScan.locatePersistenceSource())
                .isEqualTo(PersistenceSourceScan.locateCoreSource("persistence"));
        assertThat(PersistenceSourceScan.locateCoreSource("no-such-package"))
                .as("a missing package is returned as is, for its caller's walk to fail").doesNotExist();
    }

    @Test
    void blankingLiteralsKeepsOffsetsAndLinesAndHidesCodeInsideThem() {
        String code = """
                Files.move(a, b);
                String s = "Files.writeString(x, y) {";
                char c = '{';
                String t = \"""
                    new FileWriter(f)
                    \""";
                """;

        String bare = PersistenceSourceScan.withoutLiterals(code);

        assertThat(bare).hasSameSizeAs(code);
        assertThat(bare.lines().count()).isEqualTo(code.lines().count());
        assertThat(bare).contains("Files.move(a, b);")
                .doesNotContain("writeString").doesNotContain("FileWriter").doesNotContain("{");
    }

    private static PersistenceSourceScan.WriterLiterals scanWriter(
            String source, List<PersistenceSourceScan.TagParameterBuilder> builders) {
        return PersistenceSourceScan.writerLiterals("Writer.java", PersistenceSourceScan.stripComments(source),
                List.of(), builders);
    }
}

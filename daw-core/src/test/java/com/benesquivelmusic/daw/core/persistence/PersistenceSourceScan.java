package com.benesquivelmusic.daw.core.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The source side of the write/read parity contract: reads the
 * {@code persistence} package's Java sources (comments stripped, string
 * literals kept) and extracts, at name level, what the project-document
 * writers can emit and which elements the readers look up — so the contract
 * can prove its fixture family exercises every name the writer source holds,
 * and that no writer or reader hides a name from that proof.
 */
final class PersistenceSourceScan {

    /**
     * One Java comment or one string / text-block / char literal; literals are
     * matched first so a comment delimiter inside a string is not a comment.
     * The comment-only part of daw-app's {@code SourceScanSupport}, which
     * daw-core tests cannot reach.
     */
    private static final Pattern COMMENT_OR_STRING = Pattern.compile(
            "\"\"\"[\\s\\S]*?\"\"\""        // text block
            + "|\"(?:\\\\.|[^\"\\\\])*\""   // string literal
            + "|'(?:\\\\.|[^'\\\\])'"       // char literal
            + "|//[^\\n]*"                  // line comment
            + "|/\\*[\\s\\S]*?\\*/");       // block comment

    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern STRING_CONSTANT = Pattern.compile(
            "static\\s+final\\s+String\\s+([A-Z][A-Z0-9_]*)\\s*=\\s*\"([^\"]*)\"\\s*;");
    private static final Pattern CREATE_ELEMENT = Pattern.compile("\\bcreateElement\\(");
    private static final Pattern SET_ATTRIBUTE = Pattern.compile("\\.\\s*setAttribute\\(");
    private static final Pattern TRAILING_IDENTIFIER = Pattern.compile("(\\w+)\\s*$");
    private static final Pattern UNSUPPORTED_WRITE = Pattern.compile(
            "::\\s*(?:setAttribute|createElement)\\b|\\b(?:setAttributeNS|setAttributeNode|setAttributeNodeNS"
            + "|createElementNS|createAttribute|createAttributeNS|renameNode|importNode|adoptNode|cloneNode)\\(");
    /** An annotation, with its arguments if it has any: {@code @SuppressWarnings("unused")}. */
    private static final String ANNOTATION =
            "@[A-Za-z_][\\w.]*(?:\\s*\\((?:[^()\"]|\"(?:\\\\.|[^\"\\\\])*\"|\\([^()]*\\))*\\))?";
    /** A type-parameter list, nested up to three deep: {@code <T extends Comparable<T>>}. */
    private static final String TYPE_PARAMETERS = "<(?:[^<>]|<(?:[^<>]|<[^<>]*>)*>)*>";
    /** The modifier keywords, none of which is ever a method's return type. */
    private static final String MODIFIER = "public|protected|private|static|final|abstract|synchronized|native"
            + "|default|strictfp|sealed|non-sealed";
    /**
     * A method declaration: optional annotations (on its own line or the
     * declaration's, with arguments) and modifiers in any order, optional
     * type parameters, a return type, the name (group 1) and the parameter
     * list (group 2), then the opening brace of its body, where the match
     * ends. A statement ({@code return x(…);}, {@code else if (…)}) is not
     * one: it either has no return type or ends in {@code ;}. Nor is a
     * constructor: it has no return type, and a modifier keyword is never
     * read as one, so {@code public Writer(Document document)} does not
     * match with {@code public} as its return type. Code in a constructor,
     * like code in a lambda in a field initializer, is in no method the scan
     * recognises.
     */
    private static final Pattern METHOD_DECLARATION = Pattern.compile(
            "^[ \\t]*(?:(?:" + ANNOTATION
            + "|public|protected|private|static|final|synchronized|abstract|default|native)\\s+)*"
            + "(?:" + TYPE_PARAMETERS + "\\s+)?(?!(?:return|new|throw|else|case|yield|" + MODIFIER + ")\\b)"
            + "[A-Za-z_][\\w.]*(?:<[^;{}()]*>)?(?:\\[\\])*\\s+([A-Za-z_]\\w*)\\s*\\(([^;{}]*?)\\)"
            + "\\s*(?:throws\\s+[\\w.,\\s]+?)?\\s*\\{",
            Pattern.MULTILINE);
    /**
     * A class, record, enum or interface keyword followed by a name: the
     * code segment ({@link #codeStart}) that runs up to a brace and holds one
     * is read as the header of a type declaration, whatever else the header
     * holds — type parameters, a record's components with their annotations,
     * the {@code extends}, {@code implements} and {@code permits} clauses —
     * so the brace is read as opening that type's body. {@code Foo.class} is
     * not one.
     */
    private static final Pattern TYPE_DECLARATION = Pattern.compile(
            "(?<![\\w.])(?:class|record|enum|interface)\\s+[A-Za-z_]");
    /**
     * An anonymous class's instance creation up to its argument list:
     * {@code new}, optional explicit constructor type arguments
     * ({@code new <String>Object}), then the type — possibly qualified, with
     * type annotations ({@link #ANNOTATION}) before it or before any of its
     * simple names ({@code new @Deprecated java.util.function.Function}) —
     * and optional type arguments or a diamond. {@code new} itself may be
     * qualified by an enclosing instance ({@code this.new Maker},
     * {@code outer . new Maker}). Not recognised: type arguments before the
     * last simple name of a qualified type ({@code new Outer<String>.Inner}),
     * and an annotation whose arguments nest parentheses more than two deep.
     */
    private static final Pattern ANONYMOUS_CLASS_CREATION = Pattern.compile(
            "(?<!\\w)new\\b\\s*(?:" + TYPE_PARAMETERS + "\\s*)?(?:" + ANNOTATION + "\\s*)*[A-Za-z_]\\w*"
            + "(?:\\s*\\.\\s*(?:" + ANNOTATION + "\\s*)*[A-Za-z_]\\w*)*\\s*(?:" + TYPE_PARAMETERS + ")?\\s*$");
    private static final Pattern LOOKUP_HELPER = Pattern.compile(
            "List<Element>\\s+(\\w+)\\(\\s*Element\\s+\\w+\\s*,\\s*String\\s+tagName\\s*\\)");
    private static final Pattern DESCENDANT_LOOKUP = Pattern.compile("\\bgetElementsByTagName\\(");
    /** A node-name read: what a child lookup compares a tag with. */
    private static final String NODE_NAME = "(?:getTagName|getNodeName|getLocalName)\\(\\)";
    private static final Pattern NODE_NAME_USE = Pattern.compile("\\b" + NODE_NAME);
    private static final Pattern LITERAL_EQUALS_NAME = Pattern.compile(
            "\"([^\"]+)\"\\s*\\.\\s*equals\\(\\s*[\\w.]+\\s*\\.\\s*" + NODE_NAME + "\\s*\\)");
    private static final Pattern NAME_EQUALS_LITERAL = Pattern.compile(
            NODE_NAME + "\\s*\\.\\s*equals\\(\\s*\"([^\"]+)\"\\s*\\)");
    private static final Pattern HELPER_COMPARISON = Pattern.compile(
            NODE_NAME + "\\s*\\.\\s*equals\\(\\s*tagName\\s*\\)"
            + "|\\btagName\\s*\\.\\s*equals\\(\\s*[\\w.]+\\s*\\.\\s*" + NODE_NAME + "\\s*\\)");

    private PersistenceSourceScan() {
    }

    /** A name found in a source file, with where. */
    record Literal(String name, String file, int line) {

        /** {@code ProjectSerializer.java:998}. */
        String location() {
            return file.substring(file.lastIndexOf('/') + 1) + ":" + line;
        }
    }

    /**
     * A write whose name the guard cannot read from the source, declared with
     * why it is safe.
     *
     * @param file   the source file, relative to the {@code persistence} package
     * @param code   the exact code of the write, as it appears on its line
     * @param reason why the names it writes need no literal of their own
     */
    record DynamicWriteSite(String file, String code, String reason) {

        DynamicWriteSite {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("a dynamic write site must say why it is safe: " + code);
            }
        }
    }

    /**
     * A writer method allowed to create an element whose tag its caller
     * passes — the only place a {@code createElement} argument may be a
     * parameter, not a literal. The guard reads the tag from every call of
     * the method instead, so each call must pass a literal tag.
     *
     * @param file   the source file, relative to the {@code persistence} package
     * @param method the method's name
     * @param reason why one body writes several tags
     */
    record TagParameterBuilder(String file, String method, String reason) {

        TagParameterBuilder {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("a tag-parameter builder must say why it takes its tag: "
                        + method);
            }
        }
    }

    /**
     * A {@code getTagName()}, {@code getNodeName()} or {@code getLocalName()}
     * use that looks up no element, declared with what it reads instead.
     *
     * @param file   the source file, relative to the {@code persistence} package
     * @param code   the exact code of the use, as it appears on its line
     * @param reason what the name read is for, and why it is not an element lookup
     */
    record DeclaredNameRead(String file, String code, String reason) {

        DeclaredNameRead {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("a declared name read must say what it reads: " + code);
            }
        }
    }

    /** The names a writer file can emit, and the writes the guard cannot see into. */
    record WriterLiterals(List<Literal> elements, List<Literal> attributes, List<String> violations) {
    }

    /** The elements a reader file looks up, and the lookups the scan cannot see into. */
    record ReaderLookups(List<Literal> elements, List<String> violations) {
    }

    /**
     * A method declared in a source: its name, where its declaration starts,
     * its parameters' names, and its body — the offsets of its opening brace
     * and of the brace that closes it ({@code -1} when the source has none,
     * so the method holds no code).
     */
    private record Method(String name, int start, List<String> parameters, int bodyStart, int bodyEnd) {

        /** Whether the code at {@code offset} is inside this method's body. */
        boolean holds(int offset) {
            return offset > bodyStart && offset < bodyEnd;
        }
    }

    /**
     * The body of a class, record, enum or interface — named, local or
     * anonymous — in a source: the offsets of its opening brace and of the
     * brace that closes it.
     */
    private record ClassBody(int start, int end) {

        /** Whether the code at {@code offset} is inside this body. */
        boolean holds(int offset) {
            return offset > start && offset < end;
        }
    }

    /** What the scan reads of a source's structure: its recognised methods and its class bodies. */
    private record Structure(List<Method> methods, List<ClassBody> classBodies) {
    }

    /**
     * Locates the {@code persistence} package's {@code src/main/java}
     * directory. Surefire sets the working directory to the module
     * ({@code daw-core}); the fallbacks cover repo-root invocations. Mirrors
     * {@code ProjectContextNoThreadLocalTest#locateJournalPackageSource}.
     */
    static Path locatePersistenceSource() {
        String rel = "src/main/java/com/benesquivelmusic/daw/core/persistence";
        Path cwd = Paths.get("").toAbsolutePath();
        Path direct = cwd.resolve(rel);
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path viaModule = cwd.resolve("daw-core").resolve(rel);
        if (Files.isDirectory(viaModule)) {
            return viaModule;
        }
        Path candidate = cwd.getParent();
        for (int i = 0; i < 5 && candidate != null; i++) {
            Path c = candidate.resolve("daw-core").resolve(rel);
            if (Files.isDirectory(c)) {
                return c;
            }
            candidate = candidate.getParent();
        }
        return direct;
    }

    /**
     * Every Java source under {@code root}, comments stripped (line breaks
     * kept, so line numbers hold), keyed by its path relative to
     * {@code root} with forward slashes ({@code migration/MigrationRegistry.java}).
     */
    static Map<String, String> sources(Path root) {
        Map<String, String> sources = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                sources.put(relative, stripComments(Files.readString(file)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sources;
    }

    /**
     * Replaces every comment with whitespace of the same line count and keeps
     * every string, text-block and char literal verbatim.
     */
    static String stripComments(String source) {
        Matcher m = COMMENT_OR_STRING.matcher(source);
        StringBuilder out = new StringBuilder(source.length());
        int last = 0;
        while (m.find()) {
            out.append(source, last, m.start());
            String token = m.group();
            char first = token.charAt(0);
            if (first == '"' || first == '\'') {
                out.append(token);
            } else {
                for (int i = 0; i < token.length(); i++) {
                    out.append(token.charAt(i) == '\n' ? '\n' : ' ');
                }
            }
            last = m.end();
        }
        out.append(source, last, source.length());
        return out.toString();
    }

    /**
     * {@code true} when {@code code} writes names into a document: it creates
     * elements, sets attributes (a {@code TransformerFactory}'s attributes
     * excepted — they configure the output, not the document) or copies
     * nodes into it.
     */
    static boolean writesDocumentNames(String code) {
        if (CREATE_ELEMENT.matcher(code).find() || UNSUPPORTED_WRITE.matcher(code).find()) {
            return true;
        }
        Matcher setAttribute = SET_ATTRIBUTE.matcher(code);
        while (setAttribute.find()) {
            if (!configuresTransformer(code, setAttribute.start())) {
                return true;
            }
        }
        return false;
    }

    /** {@code true} when the {@code .setAttribute(} at {@code dot} is called on {@code transformerFactory}. */
    private static boolean configuresTransformer(String code, int dot) {
        Matcher receiver = TRAILING_IDENTIFIER.matcher(code.substring(code.lastIndexOf('\n', dot) + 1, dot));
        return receiver.find() && receiver.group(1).equals("transformerFactory");
    }

    /** {@code true} when {@code code} uses the {@code org.w3c.dom} API. */
    static boolean usesDom(String code) {
        return code.contains("import org.w3c.dom.");
    }

    /**
     * The element and attribute names {@code file} can write: literal
     * {@code createElement("…")} and {@code setAttribute("…", …)} arguments,
     * a {@code setAttribute(CONSTANT, …)} whose {@code static final String}
     * the file declares, and the tag literals every call of a declared
     * {@code builders} method passes ({@code buildMixerChannelElement(document,
     * "master", …)}) — whose {@code createElement(tagName)} on its own tag
     * parameter, inside the builder's own body, is the only non-literal
     * element write allowed. Any other non-literal name is a violation naming
     * its line: a {@code createElement} on a parameter outside a declared
     * builder, or in no method the scan recognises ({@link #enclosingMethod});
     * a builder call whose tag is not a literal; a builder used as a method
     * reference; a stale builder entry (named by file and method); a dynamic
     * {@code setAttribute}; and an attribute or node write the guard cannot
     * read ({@code ::setAttribute}, namespace forms, {@code importNode},
     * {@code adoptNode}, {@code cloneNode}) unless {@code allowed} declares
     * its line — the only kind of site {@code allowed} can declare.
     */
    static WriterLiterals writerLiterals(String file, String code, Collection<DynamicWriteSite> allowed,
                                         Collection<TagParameterBuilder> builders) {
        Map<String, String> constants = constants(code);
        Structure structure = structure(code);
        List<Literal> elements = new ArrayList<>();
        List<Literal> attributes = new ArrayList<>();
        List<String> violations = new ArrayList<>();

        Map<String, Integer> tagParameter = new HashMap<>();
        Matcher createElement = CREATE_ELEMENT.matcher(code);
        while (createElement.find()) {
            String argument = firstArgument(code, createElement.end());
            String literal = literal(argument);
            if (literal != null) {
                elements.add(new Literal(literal, file, lineOf(code, createElement.start())));
                continue;
            }
            Method enclosing = enclosingMethod(structure, createElement.start());
            if (enclosing == null) {
                violations.add("createElement with a tag that is not a literal, in no method the scan"
                        + " recognises: " + location(file, code, createElement.start())
                        + "  " + lineText(code, createElement.start()).trim());
                continue;
            }
            boolean declared = builders.stream().anyMatch(builder ->
                    builder.file().equals(file) && builder.method().equals(enclosing.name()));
            int parameter = enclosing.parameters().indexOf(argument);
            if (declared && parameter >= 0) {
                tagParameter.put(enclosing.name(), parameter);
            } else {
                violations.add("createElement with a tag that is not a literal, outside a declared"
                        + " tag-parameter builder: " + location(file, code, createElement.start())
                        + "  " + lineText(code, createElement.start()).trim());
            }
        }
        for (TagParameterBuilder builder : builders) {
            if (!builder.file().equals(file)) {
                continue;
            }
            Integer parameter = tagParameter.get(builder.method());
            if (parameter == null) {
                violations.add("stale tag-parameter builder " + file + "#" + builder.method()
                        + " — it no longer creates the element its caller names; delete it");
                continue;
            }
            Matcher call = Pattern.compile("\\b" + Pattern.quote(builder.method()) + "\\s*\\(").matcher(code);
            while (call.find()) {
                if (structure.methods().stream().anyMatch(method -> method.name().equals(builder.method())
                        && call.start() >= method.start() && call.start() < method.bodyStart())) {
                    continue;
                }
                List<String> arguments = arguments(code, call.end());
                String tag = parameter < arguments.size() ? literal(arguments.get(parameter)) : null;
                if (tag != null) {
                    elements.add(new Literal(tag, file, lineOf(code, call.start())));
                } else {
                    violations.add("call of tag-parameter builder " + builder.method()
                            + " with a tag that is not a literal: " + location(file, code, call.start())
                            + "  " + lineText(code, call.start()).trim());
                }
            }
            // A method reference passes the tag at run time, out of the guard's sight.
            Matcher reference = Pattern.compile("::\\s*" + Pattern.quote(builder.method()) + "\\b").matcher(code);
            while (reference.find()) {
                violations.add("tag-parameter builder used as a method reference; call it with a literal tag: "
                        + location(file, code, reference.start()) + "  " + lineText(code, reference.start()).trim());
            }
        }
        Matcher setAttribute = SET_ATTRIBUTE.matcher(code);
        while (setAttribute.find()) {
            if (configuresTransformer(code, setAttribute.start())) {
                continue;
            }
            String argument = firstArgument(code, setAttribute.end());
            String name = literal(argument);
            if (name == null && IDENTIFIER.matcher(argument).matches()) {
                name = constants.get(argument);
            }
            if (name != null) {
                attributes.add(new Literal(name, file, lineOf(code, setAttribute.start())));
            } else {
                violations.add(dynamic(file, code, setAttribute.start()));
            }
        }
        Matcher unsupported = UNSUPPORTED_WRITE.matcher(code);
        while (unsupported.find()) {
            String line = lineText(code, unsupported.start());
            boolean declared = allowed.stream()
                    .anyMatch(site -> site.file().equals(file) && line.contains(site.code()));
            if (!declared) {
                violations.add(dynamic(file, code, unsupported.start()));
            }
        }
        return new WriterLiterals(elements, attributes, violations);
    }

    /**
     * The element names {@code file} looks up: the literal tag arguments of
     * its child-lookup helpers (any {@code List<Element> name(Element, String
     * tagName)} it declares), of {@code getElementsByTagName("…")}, and of a
     * {@code getTagName()}, {@code getNodeName()} or {@code getLocalName()}
     * compared with a literal ({@code "x".equals(n.getNodeName())} or
     * {@code n.getNodeName().equals("x")}). A lookup whose tag is not a
     * literal is a violation naming its line — except a helper's own
     * comparison with its {@code tagName} parameter, inside the helper's own
     * body, and a name read {@code allowed} declares (it looks up no
     * element); a declared name read the file no longer holds is a violation
     * too (stale). Independently of which method holds them, the file may
     * hold no more helper-style comparisons (a node name compared with
     * {@code tagName}) than it declares lookup helpers: a second lookup
     * helper cannot hide behind a declared one even where the method parse
     * misreads the source.
     */
    static ReaderLookups readerLookups(String file, String code, Collection<DeclaredNameRead> allowed) {
        List<Literal> elements = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        Structure structure = structure(code);

        List<String> helpers = new ArrayList<>();
        Matcher helper = LOOKUP_HELPER.matcher(code);
        while (helper.find()) {
            helpers.add(helper.group(1));
        }
        for (String name : helpers) {
            Matcher call = Pattern.compile("\\b" + name + "\\(").matcher(code);
            while (call.find()) {
                if (isDeclaration(code, call.start())) {
                    continue;
                }
                List<String> arguments = arguments(code, call.end());
                String tag = arguments.size() == 2 ? literal(arguments.get(1)) : null;
                if (tag != null) {
                    elements.add(new Literal(tag, file, lineOf(code, call.start())));
                } else {
                    violations.add("dynamic child lookup the scan cannot see: " + location(file, code, call.start()));
                }
            }
        }
        Matcher descendants = DESCENDANT_LOOKUP.matcher(code);
        while (descendants.find()) {
            String tag = literal(firstArgument(code, descendants.end()));
            if (tag != null) {
                elements.add(new Literal(tag, file, lineOf(code, descendants.start())));
            } else {
                violations.add("dynamic descendant lookup the scan cannot see: "
                        + location(file, code, descendants.start()));
            }
        }

        // Every node-name read is either a literal comparison (a lookup of
        // that element), a helper's comparison with its tagName, or a
        // declared non-lookup; anything else hides a looked-up name.
        List<int[]> understood = new ArrayList<>();
        for (Pattern comparison : List.of(LITERAL_EQUALS_NAME, NAME_EQUALS_LITERAL)) {
            Matcher matcher = comparison.matcher(code);
            while (matcher.find()) {
                elements.add(new Literal(matcher.group(1), file, lineOf(code, matcher.start())));
                understood.add(new int[] {matcher.start(), matcher.end()});
            }
        }
        List<Integer> helperComparisons = new ArrayList<>();
        Matcher helperComparison = HELPER_COMPARISON.matcher(code);
        while (helperComparison.find()) {
            helperComparisons.add(helperComparison.start());
            Method enclosing = enclosingMethod(structure, helperComparison.start());
            if (enclosing != null && helpers.contains(enclosing.name())) {
                understood.add(new int[] {helperComparison.start(), helperComparison.end()});
            }
        }
        if (helperComparisons.size() > helpers.size()) {
            List<String> where = helperComparisons.stream().map(offset -> location(file, code, offset)).toList();
            violations.add(helperComparisons.size() + " tagName comparisons for " + helpers.size()
                    + " declared lookup helper(s) in " + file + " — a second lookup helper hides behind a"
                    + " declared one: " + String.join(", ", where));
        }
        List<DeclaredNameRead> declared = allowed.stream().filter(read -> read.file().equals(file)).toList();
        for (DeclaredNameRead read : declared) {
            if (!code.contains(read.code())) {
                violations.add("stale declared name read in " + file + " — '" + read.code()
                        + "' is gone; delete it");
            }
        }
        Matcher use = NODE_NAME_USE.matcher(code);
        while (use.find()) {
            int offset = use.start();
            boolean inComparison = understood.stream().anyMatch(span -> offset >= span[0] && offset < span[1]);
            String line = lineText(code, offset);
            boolean isDeclared = declared.stream().anyMatch(read -> line.contains(read.code()));
            if (!inComparison && !isDeclared) {
                violations.add("node-name use the scan cannot read a looked-up name from: "
                        + location(file, code, offset) + "  " + line.trim());
            }
        }
        return new ReaderLookups(elements, violations);
    }

    private static boolean isDeclaration(String code, int nameStart) {
        int lineStart = code.lastIndexOf('\n', nameStart) + 1;
        return code.substring(lineStart, nameStart).matches(".*List<Element>\\s+$");
    }

    /**
     * Every method declaration in {@code code} that
     * {@link #METHOD_DECLARATION} reads, in source order, each with its
     * body's braces, and every class body of {@code code} that
     * {@link #opensClassBody} recognises.
     */
    private static Structure structure(String code) {
        String bare = withoutLiterals(code);
        Map<Integer, Integer> closingBrace = closingBraces(bare);
        List<Method> methods = new ArrayList<>();
        Matcher declaration = METHOD_DECLARATION.matcher(code);
        while (declaration.find()) {
            List<String> parameters = new ArrayList<>();
            for (String parameter : topLevelParts(declaration.group(2))) {
                Matcher name = TRAILING_IDENTIFIER.matcher(parameter.trim());
                if (name.find()) {
                    parameters.add(name.group(1));
                }
            }
            int bodyStart = declaration.end() - 1;
            methods.add(new Method(declaration.group(1), declaration.start(), parameters, bodyStart,
                    closingBrace.getOrDefault(bodyStart, -1)));
        }
        List<ClassBody> classBodies = new ArrayList<>();
        for (Map.Entry<Integer, Integer> brace : closingBrace.entrySet()) {
            if (opensClassBody(bare, brace.getKey())) {
                classBodies.add(new ClassBody(brace.getKey(), brace.getValue()));
            }
        }
        return new Structure(methods, classBodies);
    }

    /**
     * The method the code at {@code offset} is in: the innermost declared
     * method whose body, brace to matching brace, holds it. {@code null}
     * when the code is outside every recognised method's body — in a field
     * initializer, an initializer block, a constructor, or a method whose
     * declaration {@link #METHOD_DECLARATION} cannot read — and when it is in
     * a class body {@link #opensClassBody} recognises (a local class, record,
     * enum or interface, or an anonymous class) inside that innermost
     * method's body but in none of the class's own recognised methods, such
     * as a method declared on the line its class opens on; every caller
     * treats {@code null} as a violation (fail closed). So the code of such a
     * class declared inside a builder is not credited to the builder, even
     * where a method of the class names its parameter like the builder's tag
     * parameter. The body of an anonymous class created in a shape
     * {@link #ANONYMOUS_CLASS_CREATION} does not read is read as the
     * builder's own code.
     */
    private static Method enclosingMethod(Structure structure, int offset) {
        Method enclosing = null;
        for (Method method : structure.methods()) {
            if (method.holds(offset) && (enclosing == null || method.bodyStart() > enclosing.bodyStart())) {
                enclosing = method;
            }
        }
        if (enclosing == null) {
            return null;
        }
        int methodBody = enclosing.bodyStart();
        boolean inNestedClassBody = structure.classBodies().stream()
                .anyMatch(body -> body.holds(offset) && body.start() > methodBody);
        return inNestedClassBody ? null : enclosing;
    }

    /**
     * {@code code} with every string, text-block and char literal blanked —
     * each of its characters but a line break turned into a space — so that
     * offsets and lines hold and no brace, parenthesis or keyword inside a
     * literal is read as code.
     */
    private static String withoutLiterals(String code) {
        StringBuilder bare = new StringBuilder(code);
        int i = 0;
        while (i < code.length()) {
            char c = code.charAt(i);
            if (c == '"' || c == '\'') {
                int end = afterLiteral(code, i);
                for (int j = i; j < end; j++) {
                    if (code.charAt(j) != '\n') {
                        bare.setCharAt(j, ' ');
                    }
                }
                i = end;
                continue;
            }
            i++;
        }
        return bare.toString();
    }

    /**
     * Each opening brace of {@code bare} — a source with comments stripped
     * and literals blanked ({@link #withoutLiterals}), so a brace inside a
     * string, char or text-block literal is not counted — mapped to the brace
     * that closes it; an opening brace that is never closed maps to nothing.
     */
    private static Map<Integer, Integer> closingBraces(String bare) {
        Map<Integer, Integer> closing = new HashMap<>();
        Deque<Integer> open = new ArrayDeque<>();
        for (int i = 0; i < bare.length(); i++) {
            char c = bare.charAt(i);
            if (c == '{') {
                open.push(i);
            } else if (c == '}' && !open.isEmpty()) {
                closing.put(open.pop(), i);
            }
        }
        return closing;
    }

    /**
     * Whether the opening brace at {@code brace} of {@code bare} (literals
     * blanked) opens a class body: the code segment it ends declares a class,
     * record, enum or interface ({@link #TYPE_DECLARATION}), or it follows
     * the argument list of an instance creation
     * {@link #ANONYMOUS_CLASS_CREATION} reads — an anonymous class. It fails
     * closed: the block of a statement that names a variable {@code record}
     * before another word ({@code if (record instanceof Element e) {…}}) is
     * read as a class body too.
     */
    private static boolean opensClassBody(String bare, int brace) {
        if (TYPE_DECLARATION.matcher(bare.substring(codeStart(bare, brace), brace)).find()) {
            return true;
        }
        int close = brace - 1;
        while (close >= 0 && Character.isWhitespace(bare.charAt(close))) {
            close--;
        }
        if (close < 0 || bare.charAt(close) != ')') {
            return false;
        }
        int open = openingParenthesis(bare, close);
        return open >= 0
                && ANONYMOUS_CLASS_CREATION.matcher(bare.substring(codeStart(bare, open), open)).find();
    }

    /**
     * Where the code that runs up to {@code offset} starts: just past the
     * last {@code ;}, opening or closing brace before it that is in no
     * parenthesized group closed before {@code offset} — so the braces of an
     * annotation's array argument ({@code @SuppressWarnings({"unused"})}) or
     * of a lambda passed as an argument do not cut a declaration's header or
     * an instance creation short.
     */
    private static int codeStart(String bare, int offset) {
        int depth = 0;
        for (int i = offset - 1; i >= 0; i--) {
            char c = bare.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && (c == ';' || c == '{' || c == '}')) {
                return i + 1;
            }
        }
        return 0;
    }

    /** The offset of the {@code (} that the {@code )} at {@code close} closes, or {@code -1}. */
    private static int openingParenthesis(String bare, int close) {
        int depth = 0;
        for (int i = close; i >= 0; i--) {
            char c = bare.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** The offset just past the string, text-block or char literal that starts at {@code start}. */
    private static int afterLiteral(String code, int start) {
        String delimiter = code.startsWith("\"\"\"", start) ? "\"\"\"" : String.valueOf(code.charAt(start));
        int i = start + delimiter.length();
        while (i < code.length()) {
            if (code.charAt(i) == '\\') {
                i += 2;
            } else if (code.startsWith(delimiter, i)) {
                return i + delimiter.length();
            } else {
                i++;
            }
        }
        return code.length();
    }

    /** {@code text} split at the commas outside angle brackets, parentheses and brackets. */
    private static List<String> topLevelParts(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<' || c == '(' || c == '[') {
                depth++;
            } else if (c == '>' || c == ')' || c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        if (!text.isBlank()) {
            parts.add(text.substring(start));
        }
        return parts;
    }

    private static Map<String, String> constants(String code) {
        Map<String, String> constants = new HashMap<>();
        Matcher matcher = STRING_CONSTANT.matcher(code);
        while (matcher.find()) {
            constants.put(matcher.group(1), matcher.group(2));
        }
        return constants;
    }

    private static String literal(String argument) {
        Matcher matcher = STRING_LITERAL.matcher(argument);
        return matcher.matches() ? matcher.group(1) : null;
    }

    private static String firstArgument(String code, int afterParen) {
        List<String> arguments = arguments(code, afterParen);
        return arguments.isEmpty() ? "" : arguments.getFirst();
    }

    /** The top-level, trimmed arguments of the call whose opening parenthesis ends at {@code afterParen}. */
    private static List<String> arguments(String code, int afterParen) {
        List<String> arguments = new ArrayList<>();
        int depth = 0;
        int start = afterParen;
        boolean inString = false;
        for (int i = afterParen; i < code.length(); i++) {
            char c = code.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) {
                    String last = code.substring(start, i).trim();
                    if (!last.isEmpty() || !arguments.isEmpty()) {
                        arguments.add(last);
                    }
                    return arguments;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                arguments.add(code.substring(start, i).trim());
                start = i + 1;
            }
        }
        return arguments;
    }

    private static String dynamic(String file, String code, int offset) {
        return "dynamic name the coverage guard cannot see: " + location(file, code, offset)
                + "  " + lineText(code, offset).trim();
    }

    private static String location(String file, String code, int offset) {
        return file.substring(file.lastIndexOf('/') + 1) + ":" + lineOf(code, offset);
    }

    private static int lineOf(String code, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (code.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String lineText(String code, int offset) {
        int start = code.lastIndexOf('\n', offset) + 1;
        int end = code.indexOf('\n', offset);
        return code.substring(start, end < 0 ? code.length() : end);
    }
}

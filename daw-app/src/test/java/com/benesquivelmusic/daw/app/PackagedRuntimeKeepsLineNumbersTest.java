package com.benesquivelmusic.daw.app;

import com.benesquivelmusic.daw.app.diagnostics.Fingerprint;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.reflect.AccessFlag;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The packaged runtime keeps the class files' line numbers, source-file
 * names and local-variable tables (story 336, studio-engineer ruling
 * R992-1): {@link Fingerprint}'s anchor is {@code class.method:line}, and a
 * stripped image reads every frame of its classes as
 * {@code (Unknown Source)}, line -1, so two throw sites of one method would
 * share one fingerprint and the second failure would only be counted.
 *
 * <p>The tests read {@code daw-app/pom.xml} and the compiled classes:
 * the javafx-maven-plugin links the jlink image with {@code <stripDebug>}
 * and {@code <stripJavaDebugAttributes>} explicitly false, since an unset
 * parameter takes {@code -Djavafx.stripDebug} and
 * {@code -Djavafx.stripJavaDebugAttributes} from the command line; the
 * jpackage app-image's runtime is that image, through
 * {@code --runtime-image}, so jpackage runs no jlink of its own, whose
 * default options include {@code --strip-debug}; and {@link Fingerprint}'s
 * compiled class file, the input the image links, carries its
 * {@code SourceFile}, a {@code LineNumberTable} in every method body and a
 * {@code LocalVariableTable} in every instance method's body.
 * Surefire runs before the {@code package} phase
 * builds the image, so the image itself is checked in the story's
 * Verification, not here.</p>
 */
class PackagedRuntimeKeepsLineNumbersTest {

    @Test
    void theJlinkImageIsBuiltWithoutStrippingDebugAttributes() throws IOException {
        String configuration = ChildJvm.javafxPluginConfiguration();

        assertThat(configuration).as("non-vacuity: the jlink image's configuration was read")
                .contains("<jlinkImageName>daw-runtime</jlinkImageName>");
        assertThat(configuration).as("the jlink image strips no debug attributes")
                .doesNotContain("<stripDebug>true</stripDebug>")
                .doesNotContain("<stripJavaDebugAttributes>true</stripJavaDebugAttributes>");
        assertThat(configuration).as("both are false explicitly, so a -D on the command line cannot strip them")
                .contains("<stripDebug>false</stripDebug>")
                .contains("<stripJavaDebugAttributes>false</stripJavaDebugAttributes>");
    }

    @Test
    void theInstallersRuntimeIsTheJlinkImageNotOneJpackageLinksItself() throws IOException {
        List<String> arguments = ChildJvm.jpackageArguments();

        int runtime = arguments.indexOf("--runtime-image");
        assertThat(runtime).as("without --runtime-image jpackage runs jlink with its default --strip-debug")
                .isNotNegative();
        assertThat(arguments.get(runtime + 1)).as("the jlink image")
                .isEqualTo("${project.build.directory}/daw-runtime");
        assertThat(arguments).as("no jlink options of jpackage's own").doesNotContain("--jlink-options");
    }

    @Test
    void theCompiledHostClassesCarryLineNumbersAndTheirSourceFile() throws IOException {
        ClassModel model = fingerprintClassFile();

        assertThat(model.findAttribute(Attributes.sourceFile())).as("its SourceFile").isPresent();
        List<CodeAttribute> bodies = model.methods().stream()
                .flatMap(method -> method.findAttribute(Attributes.code()).stream())
                .toList();
        assertThat(bodies).as("non-vacuity: its method bodies").isNotEmpty()
                .allSatisfy(body -> assertThat(body.findAttribute(Attributes.lineNumberTable()))
                        .as("a LineNumberTable").isPresent());
    }

    /**
     * Every instance method of {@link Fingerprint}'s compiled class file has a
     * {@code LocalVariableTable}, so a helpful {@code NullPointerException} in
     * a toast's reason names its locals, not {@code <local1>} (story 336,
     * studio-engineer ruling R992-1, which declined {@code -g:lines,source}).
     */
    @Test
    void theCompiledHostClassesCarryLocalVariableTables() throws IOException {
        List<CodeAttribute> instanceBodies = fingerprintClassFile().methods().stream()
                .filter(method -> !method.flags().has(AccessFlag.STATIC))
                .flatMap(method -> method.findAttribute(Attributes.code()).stream())
                .toList();

        assertThat(instanceBodies).as("non-vacuity: its instance methods' bodies").isNotEmpty()
                .allSatisfy(body -> assertThat(body.findAttribute(Attributes.localVariableTable()))
                        .as("a LocalVariableTable").isPresent());
    }

    /** {@link Fingerprint}'s compiled class file, the input the jlink image links. */
    private static ClassModel fingerprintClassFile() throws IOException {
        byte[] bytes;
        try (InputStream in = Fingerprint.class.getResourceAsStream("Fingerprint.class")) {
            assertThat(in).as("Fingerprint's class file").isNotNull();
            bytes = in.readAllBytes();
        }
        return ClassFile.of().parse(bytes);
    }
}

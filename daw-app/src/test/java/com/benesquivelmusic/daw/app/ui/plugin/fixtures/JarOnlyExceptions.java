package com.benesquivelmusic.daw.app.ui.plugin.fixtures;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * An exception only its plugin's closed class loader can format (story 336;
 * the story-334 note: "the background-thread test also throws an exception
 * that only its closed class loader's JAR can format, since a host-made one
 * passes while a plugin's is lost").
 *
 * <p>Two classes are generated with the Class-File API (JEP 484, final in
 * Java 24) into a JAR of a test's own directory, never onto the class path:
 * {@value #EXCEPTION_CLASS_NAME}, a public {@code RuntimeException} whose
 * {@code getMessage()} returns {@code LicenceText.text()}, and
 * {@value #TEXT_CLASS_NAME}. {@link #loadThenClose} loads the exception
 * through a class loader of its own, instantiates it without reading its
 * message — loading the exception does not load {@code LicenceText} — and
 * closes the loader. From then on {@code getMessage()}, {@code toString()} and
 * a JDK formatter throw {@link NoClassDefFoundError}, while
 * {@code getClass().getName()}, {@code getStackTrace()} and {@code getCause()}
 * work. A test compares such an exception only by identity and by
 * {@code getClass().getName()}: AssertJ's message assertions call
 * {@code getMessage()}.</p>
 */
public final class JarOnlyExceptions {

    /** The exception's binary name. */
    public static final String EXCEPTION_CLASS_NAME = "com.example.sneaky.LicenceException";

    /** The class its message needs, which only the JAR holds. */
    public static final String TEXT_CLASS_NAME = "com.example.sneaky.LicenceText";

    /** What {@code LicenceText.text()} returns, had it ever been loaded. */
    public static final String MESSAGE = "The licence server could not be reached";

    /** The JAR's file name. */
    public static final String JAR_NAME = "licence-exceptions.jar";

    private JarOnlyExceptions() {
    }

    /**
     * Writes the JAR of the two classes into {@code directory}.
     *
     * @param directory where to write it
     * @return the JAR
     * @throws IOException if it cannot be written
     */
    public static Path buildJar(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path jar = directory.resolve(JAR_NAME);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(EXCEPTION_CLASS_NAME.replace('.', '/') + ".class"));
            out.write(exceptionClass());
            out.closeEntry();
            out.putNextEntry(new JarEntry(TEXT_CLASS_NAME.replace('.', '/') + ".class"));
            out.write(textClass());
            out.closeEntry();
        }
        return jar;
    }

    /**
     * Builds the JAR in {@code directory}, loads the exception from it with a
     * class loader of its own, and closes the loader.
     *
     * @param directory where to write the JAR
     * @return a new instance of the exception, its message no longer readable
     * @throws Exception if the JAR cannot be written or the class loaded
     */
    public static RuntimeException loadThenClose(Path directory) throws Exception {
        Path jar = buildJar(directory);
        URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()},
                JarOnlyExceptions.class.getClassLoader());
        RuntimeException failure;
        try {
            failure = (RuntimeException) loader.loadClass(EXCEPTION_CLASS_NAME).getConstructor().newInstance();
        } finally {
            loader.close();
        }
        return failure;
    }

    /**
     * {@code public final class LicenceException extends RuntimeException},
     * whose public no-argument constructor calls {@code RuntimeException()}
     * and whose {@code getMessage()} returns {@code LicenceText.text()}.
     */
    private static byte[] exceptionClass() {
        ClassDesc runtimeException = RuntimeException.class.describeConstable().orElseThrow();
        MethodTypeDesc noArguments = MethodTypeDesc.of(ConstantDescs.CD_void);
        MethodTypeDesc text = MethodTypeDesc.of(ConstantDescs.CD_String);
        return ClassFile.of().build(ClassDesc.of(EXCEPTION_CLASS_NAME), type -> type
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER)
                .withSuperclass(runtimeException)
                .withMethodBody(ConstantDescs.INIT_NAME, noArguments, ClassFile.ACC_PUBLIC, code -> code
                        .aload(0)
                        .invokespecial(runtimeException, ConstantDescs.INIT_NAME, noArguments)
                        .return_())
                .withMethodBody("getMessage", text, ClassFile.ACC_PUBLIC, code -> code
                        .invokestatic(ClassDesc.of(TEXT_CLASS_NAME), "text", text)
                        .areturn()));
    }

    /** {@code public final class LicenceText}, whose static {@code text()} returns {@link #MESSAGE}. */
    private static byte[] textClass() {
        return ClassFile.of().build(ClassDesc.of(TEXT_CLASS_NAME), type -> type
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER)
                .withSuperclass(ConstantDescs.CD_Object)
                .withMethodBody("text", MethodTypeDesc.of(ConstantDescs.CD_String),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> code
                                .ldc(MESSAGE)
                                .areturn()));
    }
}

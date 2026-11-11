package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.mixer.PassThroughProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.editor.PluginManifest;
import com.benesquivelmusic.daw.sdk.editor.PluginManifestReader;
import com.benesquivelmusic.daw.sdk.editor.PluginManifestWriter;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.attribute.InnerClassesAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * JAR plugins whose own code throws what the host does not expect: one whose
 * {@code initialize} throws an {@link IOException} it does not declare — as a
 * plugin written in Kotlin, or with a "sneaky throw", can (story 334, finding
 * FX2-2) — and one whose constructor throws the {@link Error} a test chooses,
 * or a throwable it chooses with no message, which reaches the loader wrapped
 * in an {@link java.lang.reflect.InvocationTargetException} (story 334, the
 * final review's NIT-3). Two constructor-failing variants throw an exception
 * class only the JAR holds: one whose {@code getMessage()} calls a class only
 * the JAR holds, and a message-less one nested in a class only the JAR holds,
 * so naming either needs the JAR (PR #990 review, ruling R4-1). Each class the
 * builders generate with the class-file API is written only into a JAR — never
 * onto the test class path — so the class loader made for that JAR defines it
 * and keeps the JAR open until that loader is closed: on Windows, a JAR an open
 * loader holds cannot be deleted. The plugin classes extend {@link Base}, which
 * does everything else and counts the instances disposed.
 * {@link OutOfMemoryMessage}, which a constructor-failing variant can throw, is
 * on the test class path as {@code Base} is: reading its message throws the
 * virtual machine's own error (PR #990 review, ruling R5-10).
 */
public final class UndeclaredThrowPlugins {

    /** The generated plugin class whose {@code initialize} throws, which only its JAR holds. */
    public static final String CLASS_NAME = "com.example.sneaky.SneakyComp";
    /** The generated plugin class whose constructor throws, which only its JAR holds. */
    public static final String CONSTRUCTOR_FAILING_CLASS_NAME = "com.example.sneaky.BrokenComp";
    /** The plugin's stable id. */
    public static final String ID = "com.example.sneaky";
    /** The plugin's version. */
    public static final String VERSION = "1.0";
    /** The message of the exception its {@code initialize} throws. */
    public static final String MESSAGE = "the plugin cannot read its own resource";
    /** The message of the error the constructor-failing plugin's constructor throws. */
    public static final String CONSTRUCTION_MESSAGE = "the plugin cannot be constructed";
    /** The message {@link #LICENCE_EXCEPTION_CLASS_NAME} gives, which it reads from {@link #LICENCE_TEXT_CLASS_NAME}. */
    public static final String LICENCE_MESSAGE = "the licence server could not be reached";
    /** The generated exception class whose {@code getMessage()} calls {@link #LICENCE_TEXT_CLASS_NAME}; only its JAR holds it. */
    public static final String LICENCE_EXCEPTION_CLASS_NAME = "com.example.sneaky.LicenceException";
    /** The generated class whose static {@code text()} gives {@link #LICENCE_MESSAGE}; only its JAR holds it. */
    public static final String LICENCE_TEXT_CLASS_NAME = "com.example.sneaky.LicenceText";
    /** The generated message-less exception class nested in {@link #HOLDER_CLASS_NAME}; only its JAR holds it. */
    public static final String BLANK_CLASS_NAME = "com.example.sneaky.Holder$Blank";
    /** The generated class {@link #BLANK_CLASS_NAME} is nested in; only its JAR holds it. */
    public static final String HOLDER_CLASS_NAME = "com.example.sneaky.Holder";
    /** The message of the error {@link OutOfMemoryMessage#getMessage()} throws. */
    public static final String OUT_OF_MEMORY_MESSAGE = "no memory is left to read the message";

    private UndeclaredThrowPlugins() {
    }

    /** The manifest the JAR declares for the plugin. */
    public static PluginManifest manifest() {
        return new PluginManifest(CLASS_NAME, Base.DESCRIPTOR);
    }

    /**
     * Builds {@code directory/jarName}: the manifest and the generated class.
     *
     * @param directory the folder to write the JAR into
     * @param jarName   the JAR's file name
     * @return the JAR
     * @throws IOException if the JAR cannot be written
     */
    public static Path buildJar(Path directory, String jarName) throws IOException {
        return writeJar(directory.resolve(jarName), manifest(), pluginClass(), Map.of());
    }

    /**
     * Builds {@code directory/jarName}: a manifest naming
     * {@link #CONSTRUCTOR_FAILING_CLASS_NAME} and that generated class, whose
     * public no-argument constructor throws
     * {@code new thrown(CONSTRUCTION_MESSAGE)}.
     *
     * @param directory the folder to write the JAR into
     * @param jarName   the JAR's file name
     * @param thrown    the error the constructor throws; it has a public {@code (String)} constructor
     * @return the JAR
     * @throws IOException if the JAR cannot be written
     */
    public static Path buildConstructorFailingJar(Path directory, String jarName, Class<? extends Error> thrown)
            throws IOException {
        return writeJar(directory.resolve(jarName), new PluginManifest(CONSTRUCTOR_FAILING_CLASS_NAME, Base.DESCRIPTOR),
                constructorFailingClass(thrown.describeConstable().orElseThrow(), Optional.of(CONSTRUCTION_MESSAGE)),
                Map.of());
    }

    /**
     * Builds {@code directory/jarName}: a manifest naming
     * {@link #CONSTRUCTOR_FAILING_CLASS_NAME} and that generated class, whose
     * public no-argument constructor throws {@code new thrown()} — an
     * exception with no message.
     *
     * @param directory the folder to write the JAR into
     * @param jarName   the JAR's file name
     * @param thrown    what the constructor throws; it has a public no-argument constructor
     * @return the JAR
     * @throws IOException if the JAR cannot be written
     */
    public static Path buildMessagelessConstructorFailingJar(Path directory, String jarName,
                                                             Class<? extends Throwable> thrown) throws IOException {
        return writeJar(directory.resolve(jarName), new PluginManifest(CONSTRUCTOR_FAILING_CLASS_NAME, Base.DESCRIPTOR),
                constructorFailingClass(thrown.describeConstable().orElseThrow(), Optional.empty()), Map.of());
    }

    /**
     * Builds {@code directory/jarName}: a manifest naming
     * {@link #CONSTRUCTOR_FAILING_CLASS_NAME} and that generated class, whose
     * public no-argument constructor throws a new
     * {@link #LICENCE_EXCEPTION_CLASS_NAME}; that exception class, whose
     * {@code getMessage()} returns what {@link #LICENCE_TEXT_CLASS_NAME}'s
     * static {@code text()} returns; and that class, whose {@code text()}
     * returns {@link #LICENCE_MESSAGE}.
     *
     * @param directory the folder to write the JAR into
     * @param jarName   the JAR's file name
     * @return the JAR
     * @throws IOException if the JAR cannot be written
     */
    public static Path buildConstructorFailingJarWhoseMessageNeedsTheJar(Path directory, String jarName)
            throws IOException {
        ClassDesc licenceException = ClassDesc.of(LICENCE_EXCEPTION_CLASS_NAME);
        return writeJar(directory.resolve(jarName), new PluginManifest(CONSTRUCTOR_FAILING_CLASS_NAME, Base.DESCRIPTOR),
                constructorFailingClass(licenceException, Optional.empty()),
                Map.of(LICENCE_EXCEPTION_CLASS_NAME, licenceExceptionClass(licenceException),
                        LICENCE_TEXT_CLASS_NAME, licenceTextClass()));
    }

    /**
     * Builds {@code directory/jarName}: a manifest naming
     * {@link #CONSTRUCTOR_FAILING_CLASS_NAME} and that generated class, whose
     * public no-argument constructor throws a new {@link #BLANK_CLASS_NAME} —
     * an exception with no message; that exception class; and
     * {@link #HOLDER_CLASS_NAME}, the class it is nested in. Both carry the
     * same {@code InnerClasses} entry, as a compiler writes it.
     *
     * @param directory the folder to write the JAR into
     * @param jarName   the JAR's file name
     * @return the JAR
     * @throws IOException if the JAR cannot be written
     */
    public static Path buildNestedMessagelessConstructorFailingJar(Path directory, String jarName)
            throws IOException {
        ClassDesc blank = ClassDesc.of(BLANK_CLASS_NAME);
        ClassDesc holder = ClassDesc.of(HOLDER_CLASS_NAME);
        InnerClassesAttribute nesting = InnerClassesAttribute.of(InnerClassInfo.of(blank, Optional.of(holder),
                Optional.of("Blank"), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC));
        return writeJar(directory.resolve(jarName), new PluginManifest(CONSTRUCTOR_FAILING_CLASS_NAME, Base.DESCRIPTOR),
                constructorFailingClass(blank, Optional.empty()),
                Map.of(BLANK_CLASS_NAME, blankClass(blank, nesting), HOLDER_CLASS_NAME, holderClass(holder, nesting)));
    }

    private static Path writeJar(Path jar, PluginManifest manifest, byte[] pluginClass,
                                 Map<String, byte[]> otherClasses) throws IOException {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(PluginManifestReader.MANIFEST_ENTRY));
            out.write(new PluginManifestWriter().toJson(manifest).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            writeClass(out, manifest.pluginClass(), pluginClass);
            for (Map.Entry<String, byte[]> other : otherClasses.entrySet()) {
                writeClass(out, other.getKey(), other.getValue());
            }
        }
        return jar;
    }

    private static void writeClass(JarOutputStream out, String className, byte[] classFile) throws IOException {
        out.putNextEntry(new JarEntry(className.replace('.', '/') + ".class"));
        out.write(classFile);
        out.closeEntry();
    }

    /** {@code public final class SneakyComp extends Base}, whose {@code initialize} throws {@code new IOException(MESSAGE)}. */
    private static byte[] pluginClass() {
        ClassDesc base = Base.class.describeConstable().orElseThrow();
        ClassDesc ioException = IOException.class.describeConstable().orElseThrow();
        ClassDesc context = PluginContext.class.describeConstable().orElseThrow();
        MethodTypeDesc noArguments = MethodTypeDesc.of(ConstantDescs.CD_void);
        return ClassFile.of().build(ClassDesc.of(CLASS_NAME), type -> type
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER)
                .withSuperclass(base)
                .withMethodBody(ConstantDescs.INIT_NAME, noArguments, ClassFile.ACC_PUBLIC, code -> code
                        .aload(0)
                        .invokespecial(base, ConstantDescs.INIT_NAME, noArguments)
                        .return_())
                .withMethodBody("initialize", MethodTypeDesc.of(ConstantDescs.CD_void, context), ClassFile.ACC_PUBLIC,
                        code -> code
                                .new_(ioException)
                                .dup()
                                .ldc(MESSAGE)
                                .invokespecial(ioException, ConstantDescs.INIT_NAME,
                                        MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String))
                                .athrow()));
    }

    /**
     * {@code public final class BrokenComp extends Base}, whose constructor
     * throws {@code new thrown(message)} — or {@code new thrown()} when there
     * is no message — once {@code Base} is constructed, and whose
     * {@code initialize} does nothing.
     */
    private static byte[] constructorFailingClass(ClassDesc thrown, Optional<String> message) {
        ClassDesc base = Base.class.describeConstable().orElseThrow();
        ClassDesc context = PluginContext.class.describeConstable().orElseThrow();
        MethodTypeDesc noArguments = MethodTypeDesc.of(ConstantDescs.CD_void);
        return ClassFile.of().build(ClassDesc.of(CONSTRUCTOR_FAILING_CLASS_NAME), type -> type
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER)
                .withSuperclass(base)
                .withMethodBody(ConstantDescs.INIT_NAME, noArguments, ClassFile.ACC_PUBLIC, code -> {
                    code.aload(0)
                            .invokespecial(base, ConstantDescs.INIT_NAME, noArguments)
                            .new_(thrown)
                            .dup();
                    message.ifPresentOrElse(
                            text -> code.ldc(text).invokespecial(thrown, ConstantDescs.INIT_NAME,
                                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String)),
                            () -> code.invokespecial(thrown, ConstantDescs.INIT_NAME, noArguments));
                    code.athrow();
                })
                .withMethodBody("initialize", MethodTypeDesc.of(ConstantDescs.CD_void, context), ClassFile.ACC_PUBLIC,
                        code -> code.return_()));
    }

    /**
     * {@code public final class LicenceException extends RuntimeException},
     * whose public no-argument constructor calls {@code RuntimeException()}
     * and whose {@code getMessage()} returns {@code LicenceText.text()}.
     */
    private static byte[] licenceExceptionClass(ClassDesc licenceException) {
        ClassDesc runtimeException = RuntimeException.class.describeConstable().orElseThrow();
        MethodTypeDesc noArguments = MethodTypeDesc.of(ConstantDescs.CD_void);
        MethodTypeDesc text = MethodTypeDesc.of(ConstantDescs.CD_String);
        return ClassFile.of().build(licenceException, type -> type
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER)
                .withSuperclass(runtimeException)
                .withMethodBody(ConstantDescs.INIT_NAME, noArguments, ClassFile.ACC_PUBLIC, code -> code
                        .aload(0)
                        .invokespecial(runtimeException, ConstantDescs.INIT_NAME, noArguments)
                        .return_())
                .withMethodBody("getMessage", text, ClassFile.ACC_PUBLIC, code -> code
                        .invokestatic(ClassDesc.of(LICENCE_TEXT_CLASS_NAME), "text", text)
                        .areturn()));
    }

    /** {@code public final class LicenceText}, whose static {@code text()} returns {@link #LICENCE_MESSAGE}. */
    private static byte[] licenceTextClass() {
        return ClassFile.of().build(ClassDesc.of(LICENCE_TEXT_CLASS_NAME), type -> type
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER)
                .withSuperclass(ConstantDescs.CD_Object)
                .withMethodBody("text", MethodTypeDesc.of(ConstantDescs.CD_String),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> code
                                .ldc(LICENCE_MESSAGE)
                                .areturn()));
    }

    /**
     * {@code public static class Holder.Blank extends RuntimeException}, whose
     * public no-argument constructor calls {@code RuntimeException()}, with
     * the {@code InnerClasses} entry {@code nesting}.
     */
    private static byte[] blankClass(ClassDesc blank, InnerClassesAttribute nesting) {
        ClassDesc runtimeException = RuntimeException.class.describeConstable().orElseThrow();
        MethodTypeDesc noArguments = MethodTypeDesc.of(ConstantDescs.CD_void);
        return ClassFile.of().build(blank, type -> type
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                .withSuperclass(runtimeException)
                .with(nesting)
                .withMethodBody(ConstantDescs.INIT_NAME, noArguments, ClassFile.ACC_PUBLIC, code -> code
                        .aload(0)
                        .invokespecial(runtimeException, ConstantDescs.INIT_NAME, noArguments)
                        .return_()));
    }

    /** {@code public class Holder}, with the {@code InnerClasses} entry {@code nesting} naming its nested class. */
    private static byte[] holderClass(ClassDesc holder, InnerClassesAttribute nesting) {
        return ClassFile.of().build(holder, type -> type
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                .withSuperclass(ConstantDescs.CD_Object)
                .with(nesting));
    }

    /**
     * An exception with no message of its own whose {@code getMessage()} throws an
     * {@link OutOfMemoryError} with {@link #OUT_OF_MEMORY_MESSAGE}: reading it fails with the
     * virtual machine's own error. Public, with a public no-argument constructor, so a generated
     * constructor throws it from its own class loader, which finds it through its parent.
     */
    public static final class OutOfMemoryMessage extends RuntimeException {

        /** An exception with no message of its own. */
        public OutOfMemoryMessage() {
        }

        @Override
        public String getMessage() {
            throw new OutOfMemoryError(OUT_OF_MEMORY_MESSAGE);
        }
    }

    /**
     * Everything the plugin does but {@code initialize}: it describes itself,
     * passes audio through and counts the instances disposed. Public, with a
     * public constructor, so the generated class extends it from its own
     * class loader.
     */
    public abstract static class Base implements DawPlugin {

        static final PluginDescriptor DESCRIPTOR =
                new PluginDescriptor(ID, "Sneaky Comp", VERSION, "Example Audio", PluginType.EFFECT);

        private static final AtomicInteger DISPOSALS = new AtomicInteger();

        /** A plugin. */
        public Base() {
        }

        /** How many instances were disposed since {@link #resetDisposals()}. */
        public static int disposals() {
            return DISPOSALS.get();
        }

        /** Forgets the disposals counted so far. */
        public static void resetDisposals() {
            DISPOSALS.set(0);
        }

        @Override
        public PluginDescriptor getDescriptor() {
            return DESCRIPTOR;
        }

        @Override
        public void activate() {
        }

        @Override
        public void deactivate() {
        }

        @Override
        public void dispose() {
            DISPOSALS.incrementAndGet();
        }

        @Override
        public Optional<AudioProcessor> asAudioProcessor() {
            return Optional.of(new PassThroughProcessor(2));
        }
    }
}

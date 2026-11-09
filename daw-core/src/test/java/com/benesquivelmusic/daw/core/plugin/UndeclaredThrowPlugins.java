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
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * JAR plugins whose own code throws what the host does not expect: one whose
 * {@code initialize} throws an {@link IOException} it does not declare — as a
 * plugin written in Kotlin, or with a "sneaky throw", can (story 334, finding
 * FX2-2) — and one whose constructor throws the {@link Error} a test chooses,
 * which reaches the loader wrapped in an
 * {@link java.lang.reflect.InvocationTargetException} (story 334, the final
 * review's NIT-3). Each class is generated into a JAR alone, with the
 * class-file API, so the class loader made for it defines it and keeps the
 * JAR open until that loader is closed: on Windows, a JAR an open loader
 * holds cannot be deleted. The classes extend {@link Base}, which does
 * everything else and counts the instances disposed.
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
        return writeJar(directory.resolve(jarName), manifest(), pluginClass());
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
        return writeJar(directory.resolve(jarName),
                new PluginManifest(CONSTRUCTOR_FAILING_CLASS_NAME, Base.DESCRIPTOR), constructorFailingClass(thrown));
    }

    private static Path writeJar(Path jar, PluginManifest manifest, byte[] pluginClass) throws IOException {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(PluginManifestReader.MANIFEST_ENTRY));
            out.write(new PluginManifestWriter().toJson(manifest).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry(manifest.pluginClass().replace('.', '/') + ".class"));
            out.write(pluginClass);
            out.closeEntry();
        }
        return jar;
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
     * throws {@code new thrown(CONSTRUCTION_MESSAGE)} once {@code Base} is
     * constructed, and whose {@code initialize} does nothing.
     */
    private static byte[] constructorFailingClass(Class<? extends Error> thrown) {
        ClassDesc base = Base.class.describeConstable().orElseThrow();
        ClassDesc error = thrown.describeConstable().orElseThrow();
        ClassDesc context = PluginContext.class.describeConstable().orElseThrow();
        MethodTypeDesc noArguments = MethodTypeDesc.of(ConstantDescs.CD_void);
        return ClassFile.of().build(ClassDesc.of(CONSTRUCTOR_FAILING_CLASS_NAME), type -> type
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER)
                .withSuperclass(base)
                .withMethodBody(ConstantDescs.INIT_NAME, noArguments, ClassFile.ACC_PUBLIC, code -> code
                        .aload(0)
                        .invokespecial(base, ConstantDescs.INIT_NAME, noArguments)
                        .new_(error)
                        .dup()
                        .ldc(CONSTRUCTION_MESSAGE)
                        .invokespecial(error, ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String))
                        .athrow())
                .withMethodBody("initialize", MethodTypeDesc.of(ConstantDescs.CD_void, context), ClassFile.ACC_PUBLIC,
                        code -> code.return_()));
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

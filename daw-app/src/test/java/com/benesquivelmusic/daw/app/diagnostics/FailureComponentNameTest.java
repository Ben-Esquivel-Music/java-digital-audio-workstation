package com.benesquivelmusic.daw.app.diagnostics;

import com.benesquivelmusic.daw.core.dsp.ParametricEqProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.ExternalPluginHost;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.attribute.InnerClassesAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Proxy;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The name an audio failure notice gives an effect (story 337, ruling E89):
 * a plugin host by its descriptor, a processor by its declared
 * {@code @InsertEffect} display name as its insert slot shows it, an
 * undeclared one by its class name in words, and an anonymous or hidden
 * class — or one whose name cannot be read once its plugin's class loader is
 * closed — not at all, so the notice uses its unnamed sentence. Reading a
 * name never throws.
 */
class FailureComponentNameTest {

    private static final String HOST_CLASS_NAME = "com.example.echo.TapeEcho$Host";

    @Test
    void aPluginHostIsNamedByItsDescriptor() {
        assertThat(FailureComponentName.processorName(pluginHost("Tape Echo"))).hasValue("Tape Echo");
    }

    @Test
    void aProcessorIsNamedByItsDeclaredDisplayName() {
        assertThat(FailureComponentName.processorName(new ParametricEqProcessor(2, 48_000.0)))
                .as("as its insert slot names it, not by its class name in words").hasValue("Parametric EQ");
    }

    @Test
    void anUndeclaredProcessorIsNamedByItsClassNameInWords() {
        assertThat(FailureComponentName.processorName(new FuzzBoxDsp())).hasValue("Fuzz Box Dsp");
        assertThat(FailureComponentName.processorName(new TapeSaturationProcessor()))
                .as("without its Processor suffix").hasValue("Tape Saturation");
    }

    @Test
    void anAnonymousOrHiddenClassIsUnnamed() {
        AudioProcessor anonymous = new AudioProcessor() {
            @Override
            public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            }

            @Override
            public void reset() {
            }

            @Override
            public int getInputChannelCount() {
                return 2;
            }

            @Override
            public int getOutputChannelCount() {
                return 2;
            }
        };
        Runnable lambda = () -> { };

        assertThat(FailureComponentName.processorName(anonymous)).as("an anonymous class").isEmpty();
        assertThat(FailureComponentName.processorName(lambda)).as("a lambda's hidden class").isEmpty();
    }

    @Test
    void aNameThatCannotBeReadIsUnnamed() throws ReflectiveOperationException {
        ExternalPluginHost host = hostWhoseNameCannotBeRead();
        assertThatThrownBy(host::getDescriptor).as("fixture: its descriptor cannot be read")
                .isInstanceOf(AbstractMethodError.class);
        assertThatThrownBy(host.getClass()::getSimpleName).as("fixture: nor its class's name")
                .isInstanceOf(NoClassDefFoundError.class);

        assertThatCode(() -> FailureComponentName.processorName(host)).as("reading the name never throws")
                .doesNotThrowAnyException();
        assertThat(FailureComponentName.processorName(host)).as("the unnamed sentence").isEmpty();
    }

    /** A plugin host whose descriptor names it {@code name}; nothing else of it is called. */
    private static ExternalPluginHost pluginHost(String name) {
        PluginDescriptor descriptor = new PluginDescriptor("test.host", name, "1.0", "Test", PluginType.EFFECT);
        return (ExternalPluginHost) Proxy.newProxyInstance(ExternalPluginHost.class.getClassLoader(),
                new Class<?>[] {ExternalPluginHost.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getDescriptor" -> descriptor;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /**
     * A plugin host whose descriptor and class name cannot be read, as after
     * its plugin's class loader was closed: a member class generated with the
     * Class-File API (JEP 484, as {@code JarOnlyExceptions} does) that
     * implements none of the host's methods, defined by a loader that cannot
     * find the class it is a member of.
     */
    private static ExternalPluginHost hostWhoseNameCannotBeRead() throws ReflectiveOperationException {
        ClassDesc member = ClassDesc.of(HOST_CLASS_NAME);
        byte[] bytes = ClassFile.of().build(member, type -> type
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                .withSuperclass(ConstantDescs.CD_Object)
                .withInterfaceSymbols(ClassDesc.of(ExternalPluginHost.class.getName()))
                .withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, code -> code
                        .aload(0)
                        .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                        .return_())
                .with(InnerClassesAttribute.of(InnerClassInfo.of(member,
                        Optional.of(ClassDesc.of("com.example.echo.TapeEcho")), Optional.of("Host"),
                        AccessFlag.PUBLIC, AccessFlag.STATIC))));
        Class<?> host = new OneClassLoader(FailureComponentNameTest.class.getClassLoader())
                .define(HOST_CLASS_NAME, bytes);
        return (ExternalPluginHost) host.getConstructor().newInstance();
    }

    /** Defines one class from its bytes and finds no other class of its own. */
    private static final class OneClassLoader extends ClassLoader {
        OneClassLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /** A JAR plugin's processor that declares no name. */
    private static final class FuzzBoxDsp {
    }

    /** A processor that declares no name, named with the {@code Processor} suffix. */
    private static final class TapeSaturationProcessor {
    }
}

package com.benesquivelmusic.daw.app.ui.plugin.fixtures;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A plugin whose own code throws, at the steps a test arms, an exception it does not declare — a
 * checked one included, as a plugin written in Kotlin may throw — and which counts its disposals.
 * Its switches are static, as {@link BlockingInstallFixture}'s are: a JAR's class loader finds this
 * class through its parent, so a test and every instance a load makes share them. A test disarms
 * them before and after it runs ({@link #disarm()}). {@link UnformattableFailure} is an exception
 * a test can arm whose message cannot be read.
 */
public final class UndeclaredThrowFixture implements DawPlugin, AudioProcessor {

    /** The plugin's name, as its descriptor reports it. */
    public static final String NAME = "Undeclared throw";

    /** What the constructor throws; {@code null} throws nothing. */
    public static volatile Throwable onConstruct;
    /** What {@code initialize} throws; {@code null} throws nothing. */
    public static volatile Throwable onInitialize;
    /** What {@code activate} throws; {@code null} throws nothing. */
    public static volatile Throwable onActivate;
    /** What {@code dispose} throws once it has counted the disposal; {@code null} throws nothing. */
    public static volatile Throwable onDispose;
    /** How many times the {@code dispose()} of any instance ran. */
    public static final AtomicInteger disposals = new AtomicInteger();

    /** Throws nothing at any step from now on, and counts disposals from zero. */
    public static void disarm() {
        onConstruct = null;
        onInitialize = null;
        onActivate = null;
        onDispose = null;
        disposals.set(0);
    }

    /**
     * Throws {@code failure} from a method that does not declare it: called where nothing checked
     * may be thrown, {@code T} is inferred as {@link RuntimeException}, so the compiler accepts a
     * checked {@code failure} — as a plugin written in Kotlin throws one, the JVM never checking
     * what a method declares.
     *
     * @param failure what to throw
     * @param <T>     the type the compiler takes {@code failure} for
     * @throws T always: {@code failure} itself
     */
    @SuppressWarnings("unchecked")
    public static <T extends Throwable> void throwUndeclared(Throwable failure) throws T {
        throw (T) failure;
    }

    private static void throwIfArmed(Throwable armed) {
        if (armed != null) {
            throwUndeclared(armed);
        }
    }

    /** A plugin; throws {@link #onConstruct} when it is armed. */
    public UndeclaredThrowFixture() {
        throwIfArmed(onConstruct);
    }

    @Override public PluginDescriptor getDescriptor() {
        return new PluginDescriptor("test.undeclared.throw", NAME, "1", "Test", PluginType.EFFECT);
    }
    @Override public Optional<AudioProcessor> asAudioProcessor() { return Optional.of(this); }
    @Override public void initialize(PluginContext context) { throwIfArmed(onInitialize); }
    @Override public void activate() { throwIfArmed(onActivate); }
    @Override public void deactivate() { }
    @Override public void dispose() {
        disposals.incrementAndGet();
        throwIfArmed(onDispose);
    }
    @Override public void process(float[][] input, float[][] output, int frames) {
        for (int ch = 0; ch < output.length; ch++) System.arraycopy(input[ch], 0, output[ch], 0, frames);
    }
    @Override public void reset() { }
    @Override public int getInputChannelCount() { return 2; }
    @Override public int getOutputChannelCount() { return 2; }

    /**
     * An exception whose message cannot be read: its {@code getMessage()} throws an
     * {@link AssertionError} with {@link #UNREADABLE}, as reading a plugin's exception throws once
     * the plugin's class loader is closed if the exception needs a class only its JAR holds and
     * that loader never loaded. A log handler that formats it therefore throws, and the JDK's
     * handlers let that error out of {@link java.util.logging.Logger#log}.
     */
    public static final class UnformattableFailure extends RuntimeException {

        /** The message of the error that reading this exception throws. */
        public static final String UNREADABLE = "this exception's message cannot be read";

        /** An exception with no message of its own. */
        public UnformattableFailure() {
        }

        @Override
        public String getMessage() {
            throw new AssertionError(UNREADABLE);
        }
    }
}

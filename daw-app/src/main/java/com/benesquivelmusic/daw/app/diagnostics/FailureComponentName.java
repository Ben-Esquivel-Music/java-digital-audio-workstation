package com.benesquivelmusic.daw.app.diagnostics;

import com.benesquivelmusic.daw.core.audio.health.AudioFailureCategory;
import com.benesquivelmusic.daw.core.automation.PluginParameterTarget;
import com.benesquivelmusic.daw.core.mixer.ProcessorRegistry;
import com.benesquivelmusic.daw.sdk.plugin.ExternalPluginHost;

import java.util.Optional;

/**
 * The name an audio failure notice gives the component that failed (story
 * 337, ruling E89; DD-33): the plugin a render failure or a halt is anchored
 * in, a DSP or plugin sample's plugin or effect, an automation sample's
 * parameter. With no name, the notice uses its category's unnamed sentence
 * (story 337, ruling E64).
 *
 * <p>An effect is named in this order (ruling E89):</p>
 * <ol>
 *   <li>a plugin host's descriptor name;</li>
 *   <li>the processor class's {@code @InsertEffect(displayName)}, read through
 *       {@link ProcessorRegistry#declaredDisplayName}, so the notice names the
 *       effect as its insert slot does ("Parametric EQ");</li>
 *   <li>the class name in words without its {@code Processor} suffix, for an
 *       effect that declares no name ("Fuzz Box Dsp");</li>
 *   <li>none — the unnamed sentence — for an anonymous, hidden or synthetic
 *       class, for words that come out blank, and for any throw while a name
 *       is read.</li>
 * </ol>
 *
 * <p>Reading a name never costs the drain's frame: a plugin's descriptor and
 * its class can throw once the plugin's class loader is closed, so each read
 * is guarded. A render failure or a halt is named only when its cause is
 * anchored in a plugin (DD-33). Story 340 inherits this order. FX thread
 * only.</p>
 */
final class FailureComponentName {

    /** The suffix the class-name words leave out. */
    private static final String PROCESSOR_SUFFIX = "Processor";

    private FailureComponentName() {
    }

    /**
     * The component a notice of {@code category} names, when a sample
     * identifies one.
     *
     * @param category the failure's category
     * @param subject  the newest sample's subject, if any
     * @param newest   the newest throwable, if any
     * @return the name, or empty for the category's unnamed sentence
     */
    static Optional<String> of(AudioFailureCategory category, Optional<Object> subject,
                               Optional<Throwable> newest) {
        return switch (category) {
            case RENDER, RENDER_HALTED -> pluginAnchorName(category, newest.map(GuardedThrowables::unwrapCarriers));
            case DSP, PLUGIN -> subject.flatMap(FailureComponentName::processorName);
            case AUTOMATION -> subject.filter(PluginParameterTarget.class::isInstance)
                    .map(target -> ((PluginParameterTarget) target).displayName());
            default -> Optional.empty();
        };
    }

    /**
     * The simple class name of the plugin frame a failure is anchored in, if
     * it is anchored in a plugin.
     *
     * @param category  the failure's category
     * @param unwrapped the failure, its carriers unwrapped
     * @return the plugin class's simple name, or empty
     */
    static Optional<String> pluginAnchorName(AudioFailureCategory category, Optional<Throwable> unwrapped) {
        return unwrapped.map(failure -> Fingerprint.of(category.slug(), Optional.empty(), failure))
                .filter(Fingerprint::anchoredInPlugin)
                .map(fingerprint -> simpleClassName(fingerprint.anchor()));
    }

    /**
     * An anchor's simple class name: {@code com.example.Fuzz$Voice.render:42}
     * names {@code Fuzz}.
     *
     * @param anchor the anchor
     * @return its class's simple name, without a nested class
     */
    static String simpleClassName(String anchor) {
        int line = anchor.lastIndexOf(':');
        String frame = line < 0 ? anchor : anchor.substring(0, line);
        int method = frame.lastIndexOf('.');
        String className = method < 0 ? frame : frame.substring(0, method);
        String simple = className.substring(className.lastIndexOf('.') + 1);
        int nested = simple.indexOf('$');
        return nested > 0 ? simple.substring(0, nested) : simple;
    }

    /**
     * An effect's name, in ruling E89's order: a plugin host's descriptor
     * name, else the declared display name, else the class name in words —
     * {@code FuzzBoxDspProcessor} is {@code Fuzz Box Dsp} — else none.
     *
     * @param subject the sample's subject
     * @return the name, or empty for the unnamed sentence
     */
    static Optional<String> processorName(Object subject) {
        if (subject instanceof ExternalPluginHost host) {
            Optional<String> described = descriptorName(host);
            if (described.isPresent()) {
                return described;
            }
        }
        try {
            Class<?> type = subject.getClass();
            if (type.isAnonymousClass() || type.isHidden() || type.isSynthetic()) {
                return Optional.empty();
            }
            Optional<String> declared = ProcessorRegistry.declaredDisplayName(type);
            if (declared.isPresent()) {
                return declared;
            }
            return inWords(type.getSimpleName());
        } catch (Throwable unreadable) {
            // A class that cannot be read — its plugin's class loader is closed — is not named.
            return Optional.empty();
        }
    }

    /** The host's descriptor name; empty when it is blank or cannot be read. */
    private static Optional<String> descriptorName(ExternalPluginHost host) {
        try {
            String name = host.getDescriptor().name();
            return name == null || name.isBlank() ? Optional.empty() : Optional.of(name);
        } catch (Throwable unreadable) {
            // The declaration or the class name names it instead.
            return Optional.empty();
        }
    }

    /** A simple class name in words without its {@code Processor} suffix; empty when nothing is left. */
    private static Optional<String> inWords(String simpleName) {
        String simple = simpleName;
        if (simple.endsWith(PROCESSOR_SUFFIX) && simple.length() > PROCESSOR_SUFFIX.length()) {
            simple = simple.substring(0, simple.length() - PROCESSOR_SUFFIX.length());
        }
        String words = simple.replaceAll("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])", " ").strip();
        return words.isEmpty() ? Optional.empty() : Optional.of(words);
    }
}

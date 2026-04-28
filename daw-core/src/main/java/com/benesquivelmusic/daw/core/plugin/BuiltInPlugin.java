package com.benesquivelmusic.daw.core.plugin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares class-level metadata for a first-party {@link BuiltInDawPlugin}.
 *
 * <p>Every permitted implementation of {@link BuiltInDawPlugin} carries this
 * annotation so the menu layer can populate plugin entries by pure reflection
 * — without instantiating the plugin (which may allocate buffers or load
 * resources).</p>
 *
 * <p>The default implementations of {@link BuiltInDawPlugin#getMenuLabel()},
 * {@link BuiltInDawPlugin#getMenuIcon()}, and {@link BuiltInDawPlugin#getCategory()}
 * also resolve their values from this annotation, eliminating the per-class
 * boilerplate of overriding three trivial methods that return compile-time
 * constants.</p>
 *
 * <pre>{@code
 * @BuiltInPlugin(
 *     label    = "Virtual Keyboard",
 *     icon     = "keyboard",
 *     category = BuiltInPluginCategory.INSTRUMENT)
 * public final class VirtualKeyboardPlugin implements BuiltInDawPlugin { ... }
 * }</pre>
 *
 * @see BuiltInDawPlugin
 * @see BuiltInPluginCategory
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface BuiltInPlugin {

    /**
     * Human-readable label to display in the Plugins menu.
     *
     * @return the menu label, must be non-blank
     */
    String label();

    /**
     * Icon identifier for the Plugins menu item.
     *
     * <p>The identifier can be a resource path, an icon-font name, or any
     * string that the UI layer can resolve to a visual icon.</p>
     *
     * @return the icon identifier, must be non-blank
     */
    String icon();

    /**
     * Category used to group this plugin in the Plugins menu.
     *
     * @return the plugin category
     */
    BuiltInPluginCategory category();

    /**
     * Whether this plugin is a <em>terminal</em> stage in its host signal chain.
     *
     * <p>Terminal plugins (such as {@code DitherPlugin}, the dithered bit-depth
     * reducer) must be the last node of the chain they are inserted into —
     * the host (e.g. {@link com.benesquivelmusic.daw.core.mastering.MasteringChain})
     * forbids inserting any non-terminal stage <em>after</em> a terminal stage.
     * The default is {@code false}: most plugins can be freely re-ordered.</p>
     *
     * @return {@code true} if this plugin must always be the last stage of its
     *         host chain, {@code false} otherwise
     */
    boolean terminal() default false;
}

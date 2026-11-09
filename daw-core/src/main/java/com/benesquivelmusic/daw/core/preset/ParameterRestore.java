package com.benesquivelmusic.daw.core.preset;

import java.util.Objects;

/**
 * What {@link ReflectivePresetSerializer#restoreEach} did with one saved,
 * name-keyed parameter value: applied it as saved, applied it clamped to its
 * declared range, had it rejected by the processor's setter, found no
 * parameter of that name, or skipped it as not a finite number (story 334:
 * the project reader reports every outcome but {@link Applied}).
 */
public sealed interface ParameterRestore permits ParameterRestore.Applied, ParameterRestore.Clamped,
        ParameterRestore.Rejected, ParameterRestore.Unknown, ParameterRestore.NotFinite {

    /** The parameter's name, as the {@code @ProcessorParam} annotation and the saved values key it. */
    String name();

    /**
     * The value was inside the parameter's declared range and was applied as saved.
     *
     * @param name  the parameter's name
     * @param value the value applied
     */
    record Applied(String name, double value) implements ParameterRestore {
        public Applied {
            Objects.requireNonNull(name, "name must not be null");
        }
    }

    /**
     * The value was outside the parameter's declared range; the nearest bound
     * was applied.
     *
     * @param name    the parameter's name
     * @param saved   the value as saved
     * @param applied the bound applied
     * @param min     the declared minimum
     * @param max     the declared maximum
     */
    record Clamped(String name, double saved, double applied, double min, double max) implements ParameterRestore {
        public Clamped {
            Objects.requireNonNull(name, "name must not be null");
        }
    }

    /**
     * The processor's setter refused the value it was given — the saved
     * value, or, for one outside the declared range, the nearest bound — with
     * an {@link IllegalArgumentException}; the parameter keeps the value it
     * had.
     *
     * @param name    the parameter's name
     * @param saved   the value as saved, which lies outside the declared range when the setter refused its
     *                nearest bound
     * @param inForce the parameter's value after the refusal, read through its getter
     */
    record Rejected(String name, double saved, double inForce) implements ParameterRestore {
        public Rejected {
            Objects.requireNonNull(name, "name must not be null");
        }
    }

    /**
     * The processor declares no parameter of that name; nothing was applied.
     *
     * @param name  the name as saved
     * @param saved the value as saved
     */
    record Unknown(String name, double saved) implements ParameterRestore {
        public Unknown {
            Objects.requireNonNull(name, "name must not be null");
        }
    }

    /**
     * The value was missing ({@code null}), NaN or infinite; nothing was applied.
     *
     * @param name  the parameter's name
     * @param saved the value as saved — NaN for a missing one
     */
    record NotFinite(String name, double saved) implements ParameterRestore {
        public NotFinite {
            Objects.requireNonNull(name, "name must not be null");
        }
    }
}

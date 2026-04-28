package com.benesquivelmusic.daw.core.project;

import com.benesquivelmusic.daw.core.plugin.builtin.midi.ArpeggiatorPlugin;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Undoable action that sets a single parameter on an {@link ArpeggiatorPlugin}.
 *
 * <p>The parameter is identified by its bean-style name (e.g.&nbsp;{@code "rate"},
 * {@code "pattern"}, {@code "octaveRange"}, {@code "gate"}, {@code "swing"},
 * {@code "latch"}). The action snapshots the current value at execute time
 * by invoking the matching getter, applies the new value via the matching
 * setter, and restores the snapshot on undo.</p>
 *
 * <p>This is the reflective parameter-binder pattern from story 113 — the
 * same mechanism used elsewhere in the codebase to keep undo records
 * declarative without hand-coding a per-parameter action subclass.</p>
 */
public final class SetArpeggiatorParamAction implements UndoableAction {

    private final ArpeggiatorPlugin plugin;
    private final String paramName;
    private final Object newValue;
    private Object previousValue;
    private boolean snapshotTaken;

    /**
     * Creates a new set-parameter action.
     *
     * @param plugin    the arpeggiator plugin instance (must not be {@code null})
     * @param paramName the bean-style parameter name (e.g.&nbsp;{@code "gate"})
     * @param newValue  the new value to apply (must match the setter's type)
     */
    public SetArpeggiatorParamAction(ArpeggiatorPlugin plugin, String paramName, Object newValue) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.paramName = Objects.requireNonNull(paramName, "paramName must not be null");
        this.newValue = newValue;
    }

    @Override
    public String description() {
        return "Set Arpeggiator " + paramName;
    }

    @Override
    public void execute() {
        if (!snapshotTaken) {
            previousValue = invokeGetter(paramName);
            snapshotTaken = true;
        }
        invokeSetter(paramName, newValue);
    }

    @Override
    public void undo() {
        if (snapshotTaken) {
            invokeSetter(paramName, previousValue);
        }
    }

    /** Reflectively reads the value of {@code paramName} via its getter. */
    private Object invokeGetter(String name) {
        String getter = "get" + capitalize(name);
        String boolGetter = "is" + capitalize(name);
        for (Method m : plugin.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            if (m.getName().equals(getter) || m.getName().equals(boolGetter)) {
                try {
                    return m.invoke(plugin);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(
                            "Failed to read arpeggiator parameter '%s'".formatted(name), e);
                }
            }
        }
        throw new IllegalArgumentException("No getter for arpeggiator parameter: " + name);
    }

    /** Reflectively writes {@code value} to {@code paramName} via its setter. */
    private void invokeSetter(String name, Object value) {
        String setter = "set" + capitalize(name);
        for (Method m : plugin.getClass().getMethods()) {
            if (m.getParameterCount() != 1) continue;
            if (!m.getName().equals(setter)) continue;
            Class<?> p = m.getParameterTypes()[0];
            try {
                m.invoke(plugin, coerce(value, p));
                return;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "Failed to set arpeggiator parameter '%s'".formatted(name), e);
            }
        }
        throw new IllegalArgumentException("No setter for arpeggiator parameter: " + name);
    }

    /**
     * Coerces {@code value} to the setter parameter type, supporting the small
     * set of types arpeggiator parameters use today: enums (via
     * {@link Enum#valueOf(Class, String)} when the value is a String),
     * boolean, int, double.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object coerce(Object value, Class<?> target) {
        if (value == null) return null;
        if (target.isInstance(value)) return value;
        if (target.isEnum() && value instanceof String s) {
            return Enum.valueOf((Class<Enum>) target, s);
        }
        if ((target == boolean.class || target == Boolean.class) && value instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        if ((target == int.class || target == Integer.class) && value instanceof Number n) {
            return n.intValue();
        }
        if ((target == double.class || target == Double.class) && value instanceof Number n) {
            return n.doubleValue();
        }
        return value;
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Returns the parameter name this action targets — exposed for tests and
     * UI history-panel summaries.
     */
    public String paramName() {
        return paramName;
    }
}

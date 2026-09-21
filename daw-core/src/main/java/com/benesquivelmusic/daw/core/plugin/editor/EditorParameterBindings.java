package com.benesquivelmusic.daw.core.plugin.editor;

import com.benesquivelmusic.daw.sdk.editor.PluginParameterStore;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.Property;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.ComboBox;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.ToDoubleFunction;

/** FX-owned control bindings; host echoes never re-enter the audio write path. */
final class EditorParameterBindings implements AutoCloseable {
    private record Binding(DoubleConsumer echo, Runnable detach) { }

    private final PluginParameterStore store;
    private final Map<Integer, Binding> bindings = new LinkedHashMap<>();
    private boolean echoing;
    private final List<Runnable> cleanup = new ArrayList<>();

    EditorParameterBindings(PluginParameterStore store) {
        this.store = store;
    }

    void bindNumber(int id, Property<Number> property) {
        bind(id, property, value -> value, Number::doubleValue);
    }

    void bindToggle(int id, BooleanProperty property) {
        bind(id, property, value -> value >= 0.5, value -> value ? 1.0 : 0.0);
    }

    <T> void bindSelection(int id, ComboBox<T> combo,
            DoubleFunction<T> fromValue, ToDoubleFunction<T> toValue) {
        bind(id, combo.valueProperty(), fromValue, toValue);
    }

    <T> void bind(int id, Property<T> property,
            DoubleFunction<T> fromValue, ToDoubleFunction<T> toValue) {
        remove(id);
        property.setValue(fromValue.apply(store.valueById(id)));
        ChangeListener<T> listener = (_, _, value) -> {
            if (!echoing && value != null) store.writeFromUiById(id, toValue.applyAsDouble(value));
        };
        property.addListener(listener);
        bindings.put(id, new Binding(value -> property.setValue(fromValue.apply(value)),
                () -> property.removeListener(listener)));
    }

    void parameterChanged(int id, double value) {
        Binding binding = bindings.get(id);
        if (binding == null) return;
        echoing = true;
        try {
            binding.echo().accept(value);
        } finally {
            echoing = false;
        }
    }

    void remove(int id) {
        Binding previous = bindings.remove(id);
        if (previous != null) previous.detach().run();
    }

    <T> void observe(ObservableValue<T> value, ChangeListener<T> listener) {
        value.addListener(listener);
        onDetach(() -> value.removeListener(listener));
    }

    void onDetach(Runnable action) {
        cleanup.add(action);
    }

    @Override public void close() {
        bindings.values().forEach(binding -> binding.detach().run());
        bindings.clear();
        cleanup.forEach(Runnable::run);
        cleanup.clear();
    }
}

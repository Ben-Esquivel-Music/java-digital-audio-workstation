package com.benesquivelmusic.daw.app.ui.settings;

import javafx.beans.value.ChangeListener;
import javafx.css.PseudoClass;
import javafx.event.EventHandler;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.SkinBase;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Skin for {@link SettingRow} (story 306, Settings View Design Book §5.4,
 * {@code javafx-application-design} skill §3). A {@code GridPane}:
 *
 * <pre>
 *   col 0 (fixed 180, right-aligned)  col 1 (grows)   col 2        col 3
 *   label / highlight TextFlow        kind editor     apply badge  reset ↺
 *                                     description (row 1, muted, wraps)
 * </pre>
 *
 * <h2>Two-way value sync (listener + setter, never {@code bind()})</h2>
 *
 * <p>Per the two-way-control rule ({@code feedback_bind_two_way_control_property}),
 * the editor's own property is never bound. Model→control: a listener on
 * {@link SettingRow#valueProperty()} sets the editor, guarded by a reentrancy
 * flag. Control→model: an editor listener sets {@code valueProperty} through
 * the same flag with an {@code Objects.equals} short-circuit. Editor value
 * types: {@code Boolean} (toggle), the option object (choice), {@code Double}
 * (slider), {@code Integer} (stepper), {@code String} (text/path),
 * {@code KeyCombination}-or-null (key capture).</p>
 *
 * <h2>Key-capture arming (§6.5, rejection #7)</h2>
 *
 * <p>The capture filter is installed on the capture field only — never the
 * scene, window or any parent. Disarmed, it consumes <em>nothing</em> except
 * the ENTER that arms; armed, it consumes modifier-only presses (ignored),
 * ESCAPE (disarms, binding kept), DELETE/BACK_SPACE (clears + disarms) and any
 * other key (captured as a {@link KeyCodeCombination} with SHORTCUT/SHIFT/ALT
 * modifiers, mirroring the old {@code SettingsDialog.handleKeyBindingCapture},
 * then disarms). Focus loss disarms. The field carries pseudo-class
 * {@code armed} while armed.</p>
 *
 * <p>{@link #dispose()} detaches every listener/filter from the subject it was
 * attached to (the remembered subject, not a re-resolved one).</p>
 *
 * @see SettingRow
 */
public final class SettingRowSkin extends SkinBase<SettingRow> {

    /**
     * Label-column width (§5.4: fixed-width, right-aligned to the control
     * gutter). A layout constant, not a style literal.
     */
    static final double LABEL_COLUMN_WIDTH = 180;

    private static final PseudoClass ARMED = PseudoClass.getPseudoClass("armed");

    private final GridPane grid = new GridPane();
    private final Label label = new Label();
    private final Button resetButton = new Button();

    /** Reentrancy flag for the two-way value sync. */
    private boolean syncing;

    /**
     * Detach actions, one per attached listener/filter/handler, each closing
     * over the exact subject it was attached to. Run and cleared by
     * {@link #dispose()}.
     */
    private final List<Runnable> detachers = new ArrayList<>();

    /**
     * Builds the row layout and wires the kind-specific editor chosen once
     * from {@link SettingDescriptor#controlKind()}.
     *
     * @param row the owning {@link SettingRow}
     */
    public SettingRowSkin(SettingRow row) {
        super(row);

        grid.getStyleClass().add("setting-row-grid");
        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(LABEL_COLUMN_WIDTH);
        labelColumn.setPrefWidth(LABEL_COLUMN_WIDTH);
        labelColumn.setMaxWidth(LABEL_COLUMN_WIDTH);
        ColumnConstraints editorColumn = new ColumnConstraints();
        editorColumn.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelColumn, editorColumn);

        label.getStyleClass().add("setting-row-label");
        label.setAlignment(Pos.CENTER_RIGHT);
        // Fill the fixed column so the right-alignment is visible.
        label.setMaxWidth(Double.MAX_VALUE);
        updateLabelContent(row.highlightQueryProperty().get());
        ChangeListener<String> highlightListener = (_, _, q) -> updateLabelContent(q);
        row.highlightQueryProperty().addListener(highlightListener);
        detachers.add(() -> row.highlightQueryProperty().removeListener(highlightListener));

        grid.add(label, 0, 0);
        grid.add(buildEditor(row), 1, 0);

        Label badge = buildBadge(row);
        if (badge != null) {
            grid.add(badge, 2, 0);
        }

        buildResetButton(row);
        grid.add(resetButton, 3, 0);

        String description = row.descriptor().description();
        if (description != null && !description.isBlank()) {
            Label descriptionLabel = new Label(description);
            descriptionLabel.getStyleClass().add("setting-row-description");
            descriptionLabel.setWrapText(true);
            grid.add(descriptionLabel, 1, 1, 3, 1);
        }

        getChildren().add(grid);
    }

    // ── Two-way sync plumbing ─────────────────────────────────────────────────

    /** Control→model leg: guarded, {@code Objects.equals}-short-circuited. */
    private void setRowValueFromControl(Object newValue) {
        if (syncing) {
            return;
        }
        SettingRow row = getSkinnable();
        if (row == null || Objects.equals(row.getValue(), newValue)) {
            return;
        }
        syncing = true;
        try {
            row.setValue(newValue);
        } finally {
            syncing = false;
        }
    }

    /** Model→control leg: runs {@code push} unless a sync is already in flight. */
    private void runGuarded(Runnable push) {
        if (syncing) {
            return;
        }
        syncing = true;
        try {
            push.run();
        } finally {
            syncing = false;
        }
    }

    /** Registers the model→control listener on the row's value property. */
    private void attachRowValueListener(SettingRow row, ChangeListener<Object> listener) {
        row.valueProperty().addListener(listener);
        detachers.add(() -> row.valueProperty().removeListener(listener));
    }

    // ── Editor construction (one kind, chosen once) ───────────────────────────

    private Node buildEditor(SettingRow row) {
        return switch (row.descriptor().controlKind()) {
            case TOGGLE -> buildToggle(row);
            case CHOICE -> buildChoice(row);
            case SLIDER -> buildSlider(row);
            case STEPPER -> buildStepper(row);
            case TEXT -> buildTextField(row);
            case PATH -> buildPath(row);
            case KEY_CAPTURE -> buildKeyCapture(row);
        };
    }

    /** TOGGLE — a text-less {@link CheckBox}; the row label is the text. */
    private Node buildToggle(SettingRow row) {
        CheckBox box = new CheckBox();
        box.setSelected(Boolean.TRUE.equals(row.getValue()));
        ChangeListener<Boolean> boxListener = (_, _, selected) -> setRowValueFromControl(selected);
        box.selectedProperty().addListener(boxListener);
        detachers.add(() -> box.selectedProperty().removeListener(boxListener));
        attachRowValueListener(row,
                (_, _, v) -> runGuarded(() -> box.setSelected(Boolean.TRUE.equals(v))));
        return box;
    }

    /**
     * CHOICE — a {@link ComboBox} over {@code hints.choiceOptions()}. With no
     * options, items are just the initial value plus the default (no
     * enumeration/I-O here — device/backend combos show the current value only
     * until story 307). A value missing from the options is added at the front
     * rather than lost.
     */
    private Node buildChoice(SettingRow row) {
        ComboBox<Object> combo = new ComboBox<>();
        List<Object> options = new ArrayList<>();
        if (row.hints().choiceOptions() != null) {
            options.addAll(row.hints().choiceOptions());
        }
        Object current = row.getValue();
        if (options.isEmpty()) {
            if (current != null) {
                options.add(current);
            }
            Object defaultValue = row.descriptor().defaultValue();
            if (defaultValue != null && !Objects.equals(defaultValue, current)) {
                options.add(defaultValue);
            }
        } else if (current != null && !options.contains(current)) {
            options.add(0, current);
        }
        combo.getItems().setAll(options);
        if (row.hints().converter() != null) {
            combo.setConverter(row.hints().converter());
        }
        combo.setValue(current);
        ChangeListener<Object> comboListener = (_, _, v) -> setRowValueFromControl(v);
        combo.valueProperty().addListener(comboListener);
        detachers.add(() -> combo.valueProperty().removeListener(comboListener));
        attachRowValueListener(row, (_, _, v) -> runGuarded(() -> {
            if (v != null && !combo.getItems().contains(v)) {
                combo.getItems().add(0, v);
            }
            combo.setValue(v);
        }));
        return combo;
    }

    /** SLIDER — a {@link Slider} over the hint bounds plus a numeric value label. */
    private Node buildSlider(SettingRow row) {
        double current = row.getValue() instanceof Number n
                ? n.doubleValue() : row.hints().sliderMin();
        Slider slider = new Slider(row.hints().sliderMin(), row.hints().sliderMax(), current);
        Label valueLabel = new Label(formatSliderValue(slider.getValue()));
        valueLabel.getStyleClass().add("numeric-value");
        ChangeListener<Number> sliderListener = (_, _, v) -> {
            valueLabel.setText(formatSliderValue(v.doubleValue()));
            setRowValueFromControl(v.doubleValue()); // boxes to Double
        };
        slider.valueProperty().addListener(sliderListener);
        detachers.add(() -> slider.valueProperty().removeListener(sliderListener));
        attachRowValueListener(row, (_, _, v) -> runGuarded(() -> {
            if (v instanceof Number n) {
                slider.setValue(n.doubleValue());
            }
            valueLabel.setText(formatSliderValue(slider.getValue()));
        }));
        slider.setMaxWidth(Double.MAX_VALUE);
        HBox box = new HBox(slider, valueLabel);
        box.getStyleClass().add("setting-row-slider-box");
        box.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(slider, Priority.ALWAYS);
        return box;
    }

    /**
     * Deterministic slider text: {@code %.2f} in {@code Locale.ROOT} with
     * trailing zeros (and a then-dangling dot) trimmed — 1.50 → "1.5",
     * 1.00 → "1".
     */
    private static String formatSliderValue(double v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * STEPPER — a minimal integer {@link Spinner} (no descriptor uses it
     * today, §3.4 canon). Bounds come from the slider hints when explicitly
     * set; the {@link SettingRow.ControlHints#none()} sentinel pair (0, 1) —
     * or an empty range — means "unset" and falls back to
     * {@code 0..Integer.MAX_VALUE}.
     */
    private Node buildStepper(SettingRow row) {
        double hintMin = row.hints().sliderMin();
        double hintMax = row.hints().sliderMax();
        int min = 0;
        int max = Integer.MAX_VALUE;
        boolean boundsSet = hintMax > hintMin && !(hintMin == 0 && hintMax == 1);
        if (boundsSet) {
            min = (int) hintMin;
            max = (int) hintMax;
        }
        int current = row.getValue() instanceof Number n ? n.intValue() : min;
        Spinner<Integer> spinner = new Spinner<>(min, max, current);
        ChangeListener<Integer> spinnerListener = (_, _, v) -> setRowValueFromControl(v);
        spinner.valueProperty().addListener(spinnerListener);
        detachers.add(() -> spinner.valueProperty().removeListener(spinnerListener));
        attachRowValueListener(row, (_, _, v) -> runGuarded(() -> {
            if (v instanceof Number n) {
                spinner.getValueFactory().setValue(n.intValue());
            }
        }));
        return spinner;
    }

    /** TEXT — a free-form {@link TextField}; the row value is the raw text. */
    private Node buildTextField(SettingRow row) {
        TextField field = new TextField(row.getValue() == null ? "" : String.valueOf(row.getValue()));
        attachTextSync(row, field);
        return field;
    }

    /** Shared TEXT/PATH text↔value sync (String values). */
    private void attachTextSync(SettingRow row, TextField field) {
        ChangeListener<String> textListener = (_, _, text) -> setRowValueFromControl(text);
        field.textProperty().addListener(textListener);
        detachers.add(() -> field.textProperty().removeListener(textListener));
        attachRowValueListener(row,
                (_, _, v) -> runGuarded(() -> field.setText(v == null ? "" : String.valueOf(v))));
    }

    /**
     * PATH — a {@link TextField} plus a Browse… button that appends a chosen
     * directory {@code ;}-separated (§5.4's plugin-scan-paths idiom). The
     * chooser is skipped when the field has no scene/window (headless safety).
     */
    private Node buildPath(SettingRow row) {
        TextField field = new TextField(row.getValue() == null ? "" : String.valueOf(row.getValue()));
        attachTextSync(row, field);
        Button browse = new Button("Browse…");
        browse.getStyleClass().addAll("dawg-button", "setting-row-browse");
        browse.setOnAction(_ -> {
            if (field.getScene() == null || field.getScene().getWindow() == null) {
                return; // headless safety — no chooser without a window
            }
            File dir = new DirectoryChooser().showDialog(field.getScene().getWindow());
            if (dir == null) {
                return;
            }
            String existing = field.getText();
            field.setText(existing == null || existing.isBlank()
                    ? dir.getAbsolutePath()
                    : existing + ";" + dir.getAbsolutePath());
        });
        detachers.add(() -> browse.setOnAction(null));
        field.setMaxWidth(Double.MAX_VALUE);
        HBox box = new HBox(field, browse);
        box.getStyleClass().add("setting-row-path-box");
        box.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(field, Priority.ALWAYS);
        return box;
    }

    /**
     * KEY_CAPTURE — a non-editable field showing the bound
     * {@link KeyCombination#getDisplayText() display text} ({@code ""} for
     * null). Capture is gated by {@link SettingRow#armedProperty()} (§6.5,
     * rejection #7): click or ENTER-on-focused-field arms; the field-local
     * filter consumes nothing while disarmed.
     */
    private Node buildKeyCapture(SettingRow row) {
        TextField field = new TextField(keyDisplayText(row.getValue()));
        field.setEditable(false);
        field.getStyleClass().add("key-capture");

        ChangeListener<Boolean> armedListener =
                (_, _, armed) -> field.pseudoClassStateChanged(ARMED, armed);
        row.armedProperty().addListener(armedListener);
        detachers.add(() -> row.armedProperty().removeListener(armedListener));
        field.pseudoClassStateChanged(ARMED, row.armedProperty().get());

        // Model→control is the single text writer — the capture filter below
        // routes through row.setValue() unguarded so this listener repaints.
        attachRowValueListener(row,
                (_, _, v) -> field.setText(keyDisplayText(v)));

        EventHandler<MouseEvent> clickArms = _ -> row.armedProperty().set(true);
        field.addEventHandler(MouseEvent.MOUSE_CLICKED, clickArms);
        detachers.add(() -> field.removeEventHandler(MouseEvent.MOUSE_CLICKED, clickArms));

        ChangeListener<Boolean> focusListener = (_, _, focused) -> {
            if (!focused) {
                row.armedProperty().set(false);
            }
        };
        field.focusedProperty().addListener(focusListener);
        detachers.add(() -> field.focusedProperty().removeListener(focusListener));

        // The filter lives on the FIELD only — nothing global, nothing on
        // parents (§6.5, rejection #7).
        EventHandler<KeyEvent> captureFilter = event -> handleCaptureKey(row, event);
        field.addEventFilter(KeyEvent.KEY_PRESSED, captureFilter);
        detachers.add(() -> field.removeEventFilter(KeyEvent.KEY_PRESSED, captureFilter));

        return field;
    }

    /**
     * The armed key-capture state machine (§6.5). Disarmed: consumes nothing
     * (Up/Down/Tab must propagate — rejection #7) except the ENTER that arms,
     * which is consumed only because it arms. Armed: modifier-only presses are
     * consumed and ignored; ESCAPE disarms without clearing; DELETE/BACK_SPACE
     * clear the binding and disarm; any other key is captured as a
     * {@link KeyCodeCombination} with SHORTCUT/SHIFT/ALT modifiers (mirroring
     * the retired {@code SettingsDialog.handleKeyBindingCapture}) and disarms.
     */
    private void handleCaptureKey(SettingRow row, KeyEvent event) {
        if (!row.armedProperty().get()) {
            if (event.getCode() == KeyCode.ENTER) {
                row.armedProperty().set(true);
                event.consume();
            }
            return;
        }
        switch (event.getCode()) {
            case SHIFT, CONTROL, ALT, META, COMMAND, WINDOWS, UNDEFINED ->
                    event.consume(); // modifier-only: ignore, stay armed
            case ESCAPE -> {
                row.armedProperty().set(false); // binding kept
                event.consume();
            }
            case DELETE, BACK_SPACE -> {
                row.setValue(null); // clears the binding; value listener repaints
                row.armedProperty().set(false);
                event.consume();
            }
            default -> {
                List<KeyCombination.Modifier> modifiers = new ArrayList<>(3);
                if (event.isShortcutDown()) {
                    modifiers.add(KeyCombination.SHORTCUT_DOWN);
                }
                if (event.isShiftDown()) {
                    modifiers.add(KeyCombination.SHIFT_DOWN);
                }
                if (event.isAltDown()) {
                    modifiers.add(KeyCombination.ALT_DOWN);
                }
                row.setValue(new KeyCodeCombination(event.getCode(),
                        modifiers.toArray(KeyCombination.Modifier[]::new)));
                row.armedProperty().set(false); // one combination per arming
                event.consume();
            }
        }
    }

    private static String keyDisplayText(Object value) {
        return value instanceof KeyCombination kc ? kc.getDisplayText() : "";
    }

    // ── Badge + reset ─────────────────────────────────────────────────────────

    /**
     * The trailing apply-class badge — omitted entirely for
     * {@link ApplyClass#LIVE} (§6.4). Text arrives pre-localized via
     * {@link SettingRow.ControlHints#badgeText()}; the enum name is the
     * defensive fallback only.
     */
    private Label buildBadge(SettingRow row) {
        ApplyClass applyClass = row.descriptor().applyClass();
        if (applyClass == ApplyClass.LIVE) {
            return null;
        }
        String text = row.hints().badgeText() != null
                ? row.hints().badgeText() : applyClass.name();
        Label badge = new Label(text);
        badge.getStyleClass().addAll("setting-row-badge",
                applyClass == ApplyClass.RESTART_REQUIRED ? "badge-restart" : "badge-engine");
        return badge;
    }

    /**
     * The §5.7 tier-1 reset ↺ — visible AND managed only while the row is
     * modified, so an unmodified row reserves no space for it.
     */
    private void buildResetButton(SettingRow row) {
        resetButton.getStyleClass().addAll("dawg-button", "icon-only", "setting-row-reset");
        Supplier<Node> factory = row.hints().resetGraphicFactory();
        if (factory != null) {
            resetButton.setGraphic(factory.get());
        } else {
            resetButton.setText("↺");
        }
        resetButton.visibleProperty().bind(row.modifiedProperty());
        resetButton.managedProperty().bind(row.modifiedProperty());
        resetButton.setOnAction(_ -> row.resetToDefault());
    }

    // ── Label highlight (§6.1 / §5.2) ─────────────────────────────────────────

    /**
     * Renders the label plain, or — when {@code query} matches it case- and
     * diacritic-insensitively — as a {@link TextFlow} of {@link Text} spans in
     * the label's graphic slot, the matching span carrying style class
     * {@code setting-row-highlight}.
     */
    private void updateLabelContent(String query) {
        String text = getSkinnable().descriptor().label();
        int[] range = query == null || query.isBlank() ? null : matchRange(text, query);
        if (range == null) {
            label.setGraphic(null);
            label.setContentDisplay(ContentDisplay.LEFT);
            label.setText(text);
            return;
        }
        TextFlow flow = new TextFlow();
        addSpan(flow, text.substring(0, range[0]), false);
        addSpan(flow, text.substring(range[0], range[1]), true);
        addSpan(flow, text.substring(range[1]), false);
        label.setText("");
        label.setGraphic(flow);
        label.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
    }

    private static void addSpan(TextFlow flow, String s, boolean highlight) {
        if (s.isEmpty()) {
            return;
        }
        Text span = new Text(s);
        span.getStyleClass().add("setting-row-label-text");
        if (highlight) {
            span.getStyleClass().add("setting-row-highlight");
        }
        flow.getChildren().add(span);
    }

    /**
     * Finds {@code query} in {@code label} under NFD + {@code \p{M}}-stripped
     * + {@code Locale.ROOT}-lowercased normalization (the §6.1 rule), mapping
     * the normalized hit back to <em>original</em> label indices — NFD can
     * change string length, so each original code point's normalized length is
     * tracked.
     *
     * @return {@code [startInclusive, endExclusive]} in the original label,
     *         or {@code null} when the query does not match
     */
    private static int[] matchRange(String label, String query) {
        String normQuery = normalize(query.strip());
        if (normQuery.isEmpty()) {
            return null;
        }
        StringBuilder normLabel = new StringBuilder(label.length());
        List<Integer> originIndex = new ArrayList<>(label.length());
        int i = 0;
        while (i < label.length()) {
            int len = Character.charCount(label.codePointAt(i));
            String piece = normalize(label.substring(i, i + len));
            for (int k = 0; k < piece.length(); k++) {
                originIndex.add(i);
            }
            normLabel.append(piece);
            i += len;
        }
        int at = normLabel.indexOf(normQuery);
        if (at < 0) {
            return null;
        }
        int endNorm = at + normQuery.length();
        int start = originIndex.get(at);
        int end = endNorm < originIndex.size() ? originIndex.get(endNorm) : label.length();
        return new int[] {start, end};
    }

    /** NFD + strip {@code \p{M}} + lowercase(ROOT) — mirrors {@code SettingsSearchIndex}. */
    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    @Override
    public void dispose() {
        detachers.forEach(Runnable::run);
        detachers.clear();
        resetButton.visibleProperty().unbind();
        resetButton.managedProperty().unbind();
        resetButton.setOnAction(null);
        super.dispose();
    }
}

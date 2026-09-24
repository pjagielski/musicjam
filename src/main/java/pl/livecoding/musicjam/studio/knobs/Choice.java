package pl.livecoding.musicjam.studio.knobs;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One of a few values, all on show: a segmented bar (the delay's mode) or a row of chips (the
 * delay's sync). A combo box hides the other options behind a click; for four modes or six
 * divisions it is quicker to see them all and touch the one you want.
 *
 * <p>One is always chosen — clicking the chosen one again leaves it chosen rather than none.
 */
final class Choice<T> extends HBox {

    enum Look { SEGMENTS, CHIPS }

    private final Look look;
    private final Theme.Accent accent;
    private final Map<T, ToggleButton> buttons = new LinkedHashMap<>();
    private final ToggleGroup group = new ToggleGroup();

    private Theme theme;
    private T value;
    private Consumer<T> onChange = chosen -> { };

    Choice(Look look, List<T> values, T initial, Theme.Accent accent, Theme theme) {
        super(look == Look.SEGMENTS ? 3 : 6);
        this.look = look;
        this.accent = accent;
        this.theme = theme;
        this.value = initial;
        setAlignment(Pos.CENTER_LEFT);
        if (look == Look.SEGMENTS) {
            setPadding(new Insets(3));
        }
        for (T option : values) {
            ToggleButton button = new ToggleButton(option.toString());
            button.setToggleGroup(group);
            button.setFocusTraversable(false);
            if (look == Look.SEGMENTS) {
                button.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(button, Priority.ALWAYS);
            } else {
                // a chip is never squeezed into "..."; a row too narrow for them shows that plainly
                button.setMinWidth(Region.USE_PREF_SIZE);
            }
            buttons.put(option, button);
            getChildren().add(button);
        }
        buttons.get(initial).setSelected(true);
        group.selectedToggleProperty().addListener((property, before, after) -> {
            if (after == null) {
                // a second click on the chosen one would leave nothing chosen: put it back
                before.setSelected(true);
                return;
            }
            buttons.forEach((option, button) -> {
                if (button == after) {
                    value = option;
                }
            });
            paint();
            onChange.accept(value);
        });
        paint();
    }

    T value() {
        return value;
    }

    /** Chooses {@code option} as a click on it would, telling whoever listens. */
    void select(T option) {
        ToggleButton button = buttons.get(option);
        if (button != null) {
            button.setSelected(true);
        }
    }

    void setOnChange(Consumer<T> listener) {
        this.onChange = listener;
    }

    void setTheme(Theme next) {
        theme = next;
        paint();
    }

    private void paint() {
        boolean light = theme.background().getBrightness() > 0.5;
        Color chosenFill = light ? Color.WHITE : Color.web("#343a40");
        if (look == Look.SEGMENTS) {
            setStyle("-fx-background-color: " + (light ? "#e9ecef" : "#212529") + "; -fx-background-radius: 10;");
        }
        buttons.forEach((option, button) -> {
            boolean chosen = option.equals(value);
            if (look == Look.SEGMENTS) {
                button.setStyle("-fx-background-color: " + (chosen ? Theme.web(chosenFill) : "transparent")
                        + "; -fx-background-radius: 8; -fx-text-fill: "
                        + Theme.web(chosen ? theme.text() : theme.mutedText())
                        + "; -fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 6 8 6 8;");
            } else {
                Color edge = chosen ? theme.accent(accent) : theme.panelBorder();
                String fill = chosen ? rgba(theme.accent(accent), light ? 0.12 : 0.22) : Theme.web(theme.panel());
                button.setStyle("-fx-background-color: " + fill + "; -fx-background-radius: 14;"
                        + " -fx-border-color: " + Theme.web(edge) + "; -fx-border-radius: 14;"
                        + " -fx-text-fill: " + Theme.web(chosen ? theme.text() : theme.mutedText())
                        + "; -fx-font-family: 'Consolas', 'Menlo', monospace; -fx-font-size: 11px;"
                        + " -fx-padding: 4 7 4 7;");
            }
        });
    }

    private static String rgba(Color color, double alpha) {
        return String.format(Locale.ROOT, "rgba(%d,%d,%d,%.2f)", Math.round(color.getRed() * 255),
                Math.round(color.getGreen() * 255), Math.round(color.getBlue() * 255), alpha);
    }
}

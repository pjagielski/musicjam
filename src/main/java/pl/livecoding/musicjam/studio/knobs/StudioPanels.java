package pl.livecoding.musicjam.studio.knobs;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * The look of {@link SynthControls} lent to the rest of the studio: the same framed group with a
 * small coloured heading, and the same knob, so the melody and MIDI controls sit next to the synth
 * panel without looking like they came from a different program.
 */
public final class StudioPanels {

    private StudioPanels() {
    }

    /** A framed group on a light background, with {@code title} above its rows. */
    public static VBox frame(String title, Node... rows) {
        Label header = new Label(title.toUpperCase());
        header.setStyle("-fx-text-fill: " + Theme.web(Theme.LIGHT.mutedText())
                + "; -fx-font-size: 10px; -fx-font-weight: bold;");
        VBox box = new VBox(10, header);
        box.getChildren().addAll(rows);
        box.setPadding(new Insets(12, 14, 14, 14));
        box.setStyle("-fx-background-color: " + Theme.web(Theme.LIGHT.panel())
                + "; -fx-background-radius: 10; -fx-border-radius: 10; -fx-border-color: "
                + Theme.web(Theme.LIGHT.panelBorder()) + ";");
        return box;
    }

    /** A row of controls, sitting on the knobs' baseline so their readouts line up. */
    public static HBox row(Node... children) {
        HBox row = new HBox(10, children);
        row.setAlignment(Pos.BOTTOM_LEFT);
        row.setPadding(new Insets(0, 0, 4, 0));
        return row;
    }

    /** A knob on a linear scale, drawn and handled exactly like the synth panel's. */
    public static PanelKnob knob(String label, double min, double max, String unit, int decimals,
                                 double initial) {
        return new PanelKnob(new Knob(new Param(label, min, max, unit, false, false, decimals, initial),
                Theme.Accent.FILTER, 62, Theme.LIGHT));
    }
}

package pl.livecoding.musicjam.studio.knobs;

import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

/**
 * One performance effect as a sampler's FX screen shows it: a tall strip played by holding a
 * finger on it. Press it and the effect comes in at that setting; slide up or down and it follows;
 * let go and it stops. Lock keeps it going after you let go — press again to change it, unlock to
 * stop it.
 *
 * <p>A strip comes in two kinds. {@link #zones} cuts it into a few settings, one per zone — the
 * Stutter's slice lengths. {@link #continuous} plays any value from the bottom (0) to the top (1),
 * and can mark its centre, for the effects that go two ways from there, as Filter does.
 *
 * <p>With a mouse, the left button plays and the right button locks: right-click while holding (or
 * anywhere on the strip) to lock it there; right-click again to unlock and stop — for zones, a
 * right-click on a different zone moves the lock instead. The Lock button below does the same by hand.
 */
public final class FxStrip extends VBox {

    private static final double WIDTH = 78;
    private static final double HEIGHT = 216;
    private static final double OFF = -1;

    private final List<String> zones;
    private final String top;
    private final String bottom;
    private final boolean centred;
    private final Canvas pad = new Canvas(WIDTH, HEIGHT);
    private final ToggleButton lock = new ToggleButton("Lock");
    private final DoubleConsumer onChange;
    private final Theme theme = Theme.LIGHT;

    // the zone's index, or the value from 0 to 1; OFF when the effect is not playing
    private double active = OFF;
    private boolean pressing;

    /**
     * A strip of {@code zones}, top to bottom. The listener hears the zone's index, top one first,
     * or -1 when the effect should stop.
     */
    public static FxStrip zones(String name, List<String> zones, IntConsumer onChange) {
        return new FxStrip(name, List.copyOf(zones), "", "", false, value -> onChange.accept((int) value));
    }

    /**
     * A strip played anywhere from the bottom to the top, labelled {@code top} and {@code bottom};
     * {@code centred} marks the middle. The listener hears a value from 0 (bottom) to 1 (top), or
     * -1 when the effect should stop.
     */
    public static FxStrip continuous(String name, String top, String bottom, boolean centred,
                                     DoubleConsumer onChange) {
        return new FxStrip(name, List.of(), top, bottom, centred, onChange);
    }

    private FxStrip(String name, List<String> zones, String top, String bottom, boolean centred,
                    DoubleConsumer onChange) {
        super(8);
        this.zones = zones;
        this.top = top;
        this.bottom = bottom;
        this.centred = centred;
        this.onChange = onChange;
        setAlignment(Pos.TOP_CENTER);

        Label header = new Label(name.toUpperCase());
        header.setStyle("-fx-text-fill: " + Theme.web(theme.accent(Theme.Accent.FX))
                + "; -fx-font-size: 10px; -fx-font-weight: bold;");

        // consumed so that playing the strip never pans the view underneath it
        pad.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() == MouseButton.SECONDARY) {
                toggleLock(valueAt(event.getY()));
            } else if (event.getButton() == MouseButton.PRIMARY) {
                pressing = true;
                choose(valueAt(event.getY()));
            }
            event.consume();
        });
        pad.addEventHandler(MouseEvent.MOUSE_DRAGGED, event -> {
            if (pressing) {
                choose(valueAt(event.getY()));
            }
            event.consume();
        });
        pad.addEventHandler(MouseEvent.MOUSE_RELEASED, event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                pressing = false;
                if (!lock.isSelected()) {
                    choose(OFF);
                }
            }
            event.consume();
        });

        lock.setFocusTraversable(false);
        lock.setPrefWidth(WIDTH);
        lock.selectedProperty().addListener((property, before, locked) -> {
            if (!locked && !pressing) {
                choose(OFF);
            }
            styleLock();
            draw();
        });
        styleLock();

        getChildren().addAll(header, pad, lock);
        draw();
    }

    /**
     * A right-click: locks what is playing (or where it was clicked, if nothing is), unlocks when it
     * lands on the locked zone again — or anywhere, on a continuous strip — and otherwise moves the
     * lock to the zone clicked.
     */
    private void toggleLock(double clicked) {
        if (!lock.isSelected()) {
            choose(pressing && active >= 0 ? active : clicked);
            lock.setSelected(true);
        } else if (!pressing && (zones.isEmpty() || clicked == active)) {
            lock.setSelected(false);
        } else {
            choose(pressing ? active : clicked);
        }
    }

    /** Unlocks and stops, as if the finger had been lifted — what Stop does to every strip. */
    public void release() {
        pressing = false;
        lock.setSelected(false);
        choose(OFF);
    }

    private double valueAt(double y) {
        if (zones.isEmpty()) {
            return Math.max(0, Math.min(1, 1 - y / HEIGHT));
        }
        int zone = (int) (y / HEIGHT * zones.size());
        return Math.max(0, Math.min(zones.size() - 1, zone));
    }

    private void choose(double value) {
        if (value == active) {
            return;
        }
        active = value;
        draw();
        onChange.accept(value);
    }

    private void styleLock() {
        boolean locked = lock.isSelected();
        lock.setText(locked ? "Locked" : "Lock");
        lock.setStyle("-fx-background-color: " + (locked ? Theme.web(theme.accent(Theme.Accent.FX)) : "#e9ecef")
                + "; -fx-text-fill: " + (locked ? "white" : Theme.web(theme.mutedText()))
                + "; -fx-background-radius: 6; -fx-font-size: 11px; -fx-font-weight: bold;");
    }

    private void draw() {
        GraphicsContext g = pad.getGraphicsContext2D();
        g.clearRect(0, 0, WIDTH, HEIGHT);
        g.setFill(theme.canvas());
        g.fillRoundRect(0, 0, WIDTH, HEIGHT, 12, 12);
        Color fill = theme.accent(Theme.Accent.FX).deriveColor(0, 1, 1, lock.isSelected() ? 0.9 : 0.75);
        if (zones.isEmpty()) {
            drawContinuous(g, fill);
        } else {
            drawZones(g, fill);
        }
        g.setStroke(theme.panelBorder());
        g.setLineWidth(1);
        g.strokeRoundRect(0.5, 0.5, WIDTH - 1, HEIGHT - 1, 12, 12);
    }

    private void drawZones(GraphicsContext g, Color fill) {
        double zoneHeight = HEIGHT / zones.size();
        if (active >= 0) {
            g.setFill(fill);
            g.fillRoundRect(2, active * zoneHeight + 2, WIDTH - 4, zoneHeight - 4, 10, 10);
        }
        g.setStroke(theme.grid());
        g.setLineWidth(1);
        for (int zone = 1; zone < zones.size(); zone++) {
            double y = Math.round(zone * zoneHeight) + 0.5;
            g.strokeLine(8, y, WIDTH - 8, y);
        }
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.CENTER);
        g.setFont(Font.font("Consolas", FontWeight.BOLD, 13));
        for (int zone = 0; zone < zones.size(); zone++) {
            g.setFill(zone == active ? Color.WHITE : theme.mutedText());
            g.fillText(zones.get(zone), WIDTH / 2, (zone + 0.5) * zoneHeight);
        }
    }

    /** The value as a bar: up from the bottom, or out from the centre on a centred strip. */
    private void drawContinuous(GraphicsContext g, Color fill) {
        double centre = HEIGHT / 2;
        if (centred) {
            g.setStroke(theme.grid());
            g.setLineWidth(1);
            g.strokeLine(8, centre + 0.5, WIDTH - 8, centre + 0.5);
        }
        if (active >= 0) {
            double y = (1 - active) * HEIGHT;
            double from = centred ? Math.min(y, centre) : y;
            double to = centred ? Math.max(y, centre) : HEIGHT;
            g.setFill(fill.deriveColor(0, 1, 1, 0.35));
            g.fillRoundRect(2, Math.max(2, from), WIDTH - 4, Math.max(2, Math.min(HEIGHT - 2, to) - Math.max(2, from)), 10, 10);
            g.setStroke(fill);
            g.setLineWidth(3);
            double line = Math.max(3, Math.min(HEIGHT - 3, y));
            g.strokeLine(6, line, WIDTH - 6, line);
        }
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.CENTER);
        g.setFont(Font.font("Consolas", FontWeight.BOLD, 12));
        g.setFill(theme.mutedText());
        g.fillText(top, WIDTH / 2, 16);
        g.fillText(bottom, WIDTH / 2, HEIGHT - 16);
    }
}

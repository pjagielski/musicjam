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
import java.util.function.IntConsumer;

/**
 * One performance effect as a sampler's FX screen shows it: a tall strip cut into zones, played by
 * holding a finger on it. Press a zone and the effect comes in at that setting; slide up or down
 * and it follows; let go and it stops. Lock keeps it going after you let go — press another zone
 * to change it, unlock to stop it.
 *
 * <p>With a mouse, the left button plays and the right button locks: right-click while holding a
 * zone (or on any zone) to lock it there, right-click the locked zone to unlock and stop, or a
 * different zone to move the lock to it. The Lock button below does the same by hand.
 *
 * <p>The listener hears the zone's index, top one first, or -1 when the effect should stop.
 */
public final class FxStrip extends VBox {

    private static final double WIDTH = 78;
    private static final double HEIGHT = 216;

    private final List<String> zones;
    private final Canvas pad = new Canvas(WIDTH, HEIGHT);
    private final ToggleButton lock = new ToggleButton("Lock");
    private final IntConsumer onChange;
    private final Theme theme = Theme.LIGHT;

    private int active = -1;
    private boolean pressing;

    /** {@code zones} top to bottom; the strip starts idle and unlocked. */
    public FxStrip(String name, List<String> zones, IntConsumer onChange) {
        super(8);
        this.zones = List.copyOf(zones);
        this.onChange = onChange;
        setAlignment(Pos.TOP_CENTER);

        Label header = new Label(name.toUpperCase());
        header.setStyle("-fx-text-fill: " + Theme.web(theme.accent(Theme.Accent.FX))
                + "; -fx-font-size: 10px; -fx-font-weight: bold;");

        // consumed so that playing the strip never pans the view underneath it
        pad.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() == MouseButton.SECONDARY) {
                toggleLock(zoneAt(event.getY()));
            } else if (event.getButton() == MouseButton.PRIMARY) {
                pressing = true;
                choose(zoneAt(event.getY()));
            }
            event.consume();
        });
        pad.addEventHandler(MouseEvent.MOUSE_DRAGGED, event -> {
            if (pressing) {
                choose(zoneAt(event.getY()));
            }
            event.consume();
        });
        pad.addEventHandler(MouseEvent.MOUSE_RELEASED, event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                pressing = false;
                if (!lock.isSelected()) {
                    choose(-1);
                }
            }
            event.consume();
        });

        lock.setFocusTraversable(false);
        lock.setPrefWidth(WIDTH);
        lock.selectedProperty().addListener((property, before, locked) -> {
            if (!locked && !pressing) {
                choose(-1);
            }
            styleLock();
            draw();
        });
        styleLock();

        getChildren().addAll(header, pad, lock);
        draw();
    }

    /**
     * A right-click: locks the zone that is playing (or the one clicked, if none is), unlocks when
     * it lands on the locked zone again, and moves the lock when it lands on another.
     */
    private void toggleLock(int clicked) {
        if (!lock.isSelected()) {
            choose(pressing && active >= 0 ? active : clicked);
            lock.setSelected(true);
        } else if (clicked == active && !pressing) {
            lock.setSelected(false);
        } else {
            choose(pressing ? active : clicked);
        }
    }

    /** Unlocks and stops, as if the finger had been lifted — what Stop does to every strip. */
    public void release() {
        pressing = false;
        lock.setSelected(false);
        choose(-1);
    }

    /** The zone playing now, or -1. */
    public int active() {
        return active;
    }

    private int zoneAt(double y) {
        int zone = (int) (y / HEIGHT * zones.size());
        return Math.max(0, Math.min(zones.size() - 1, zone));
    }

    private void choose(int zone) {
        if (zone == active) {
            return;
        }
        active = zone;
        draw();
        onChange.accept(zone);
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

        double zoneHeight = HEIGHT / zones.size();
        if (active >= 0) {
            double alpha = lock.isSelected() ? 0.9 : 0.75;
            g.setFill(theme.accent(Theme.Accent.FX).deriveColor(0, 1, 1, alpha));
            g.fillRoundRect(2, active * zoneHeight + 2, WIDTH - 4, zoneHeight - 4, 10, 10);
        }
        g.setStroke(theme.grid());
        g.setLineWidth(1);
        for (int zone = 1; zone < zones.size(); zone++) {
            double y = Math.round(zone * zoneHeight) + 0.5;
            g.strokeLine(8, y, WIDTH - 8, y);
        }
        g.setStroke(theme.panelBorder());
        g.strokeRoundRect(0.5, 0.5, WIDTH - 1, HEIGHT - 1, 12, 12);

        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.CENTER);
        g.setFont(Font.font("Consolas", FontWeight.BOLD, 13));
        for (int zone = 0; zone < zones.size(); zone++) {
            g.setFill(zone == active ? Color.WHITE : theme.mutedText());
            g.fillText(zones.get(zone), WIDTH / 2, (zone + 0.5) * zoneHeight);
        }
    }
}

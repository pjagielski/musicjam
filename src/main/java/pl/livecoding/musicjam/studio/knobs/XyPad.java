package pl.livecoding.musicjam.studio.knobs;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;

/**
 * Two parameters under one finger: the cutoff across, the resonance up. The same drag that a knob
 * reads in one direction is read here in both, which is what makes a filter sweep playable.
 */
final class XyPad extends Canvas {

    private final DoubleProperty across = new SimpleDoubleProperty();
    private final DoubleProperty up = new SimpleDoubleProperty();
    private final Param horizontal;
    private final Param vertical;
    private final Theme.Accent accent;

    private Theme theme;

    XyPad(Param horizontal, Param vertical, Theme.Accent accent, double width, double height, Theme theme) {
        super(width, height);
        this.horizontal = horizontal;
        this.vertical = vertical;
        this.accent = accent;
        this.theme = theme;
        across.addListener((property, before, after) -> draw());
        up.addListener((property, before, after) -> draw());
        setOnMousePressed(this::moveTo);
        setOnMouseDragged(this::moveTo);
        across.set(horizontal.positionOf(horizontal.initial()));
        up.set(vertical.positionOf(vertical.initial()));
        draw();
    }

    DoubleProperty across() {
        return across;
    }

    DoubleProperty up() {
        return up;
    }

    void setTheme(Theme theme) {
        this.theme = theme;
        draw();
    }

    private void moveTo(MouseEvent event) {
        across.set(clamp(event.getX() / getWidth()));
        up.set(clamp(1 - event.getY() / getHeight()));
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private void draw() {
        GraphicsContext g = getGraphicsContext2D();
        double width = getWidth();
        double height = getHeight();
        Color accentColour = theme.accent(accent);
        g.setFill(theme.canvas());
        g.fillRect(0, 0, width, height);

        g.setStroke(theme.grid());
        g.setLineWidth(1);
        for (int line = 1; line < 4; line++) {
            g.strokeLine(width * line / 4, 0, width * line / 4, height);
            g.strokeLine(0, height * line / 4, width, height * line / 4);
        }
        g.setStroke(theme.panelBorder());
        g.strokeRect(0.5, 0.5, width - 1, height - 1);

        double x = across.get() * width;
        double y = (1 - up.get()) * height;
        g.setStroke(accentColour.deriveColor(0, 1, 1, 0.45));
        g.strokeLine(x, 0, x, height);
        g.strokeLine(0, y, width, y);
        g.setFill(accentColour);
        g.fillOval(x - 7, y - 7, 14, 14);
        g.setFill(theme.canvas());
        g.fillOval(x - 3, y - 3, 6, 6);

        g.setFill(theme.mutedText());
        g.fillText(horizontal.label() + "  " + horizontal.format(horizontal.valueOf(across.get())), 8, height - 8);
        g.fillText(vertical.label() + "  " + vertical.format(vertical.valueOf(up.get())), 8, 16);
    }
}

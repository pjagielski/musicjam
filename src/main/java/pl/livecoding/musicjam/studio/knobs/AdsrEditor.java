package pl.livecoding.musicjam.studio.knobs;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;

/**
 * The amplitude envelope drawn as the shape you hear, with a handle on each corner: the peak sets
 * the attack, the corner after it the decay and the sustain level, and the last one the release.
 * Dragging the picture beats turning four knobs, and on a projector it says what an envelope is
 * without a word of explanation.
 */
final class AdsrEditor extends Canvas {

    private static final double PADDING = 18;
    private static final double SEGMENT_SHARE = 0.28;
    private static final double HOLD_SHARE = 0.16;
    private static final double GRAB_RADIUS = 16;

    private final Param attack;
    private final Param decay;
    private final Param sustain;
    private final Param release;
    private final Theme.Accent accent;

    private Theme theme;

    private final DoubleProperty attackAt = new SimpleDoubleProperty();
    private final DoubleProperty decayAt = new SimpleDoubleProperty();
    private final DoubleProperty sustainAt = new SimpleDoubleProperty();
    private final DoubleProperty releaseAt = new SimpleDoubleProperty();

    private Handle dragging;

    private enum Handle { PEAK, SUSTAIN, END }

    AdsrEditor(Param attack, Param decay, Param sustain, Param release, Theme.Accent accent,
               double width, double height, Theme theme) {
        super(width, height);
        this.attack = attack;
        this.decay = decay;
        this.sustain = sustain;
        this.release = release;
        this.accent = accent;
        this.theme = theme;

        attackAt.set(attack.positionOf(attack.initial()));
        decayAt.set(decay.positionOf(decay.initial()));
        sustainAt.set(sustain.positionOf(sustain.initial()));
        releaseAt.set(release.positionOf(release.initial()));
        for (DoubleProperty property : new DoubleProperty[] {attackAt, decayAt, sustainAt, releaseAt}) {
            property.addListener((ignored, before, after) -> draw());
        }

        // consumed so that dragging a handle shapes the envelope instead of panning the view
        NoPanning.on(this);
        setOnMousePressed(event -> {
            dragging = nearestHandle(event);
            event.consume();
        });
        setOnMouseDragged(event -> {
            drag(event);
            event.consume();
        });
        setOnMouseReleased(event -> {
            dragging = null;
            event.consume();
        });
        draw();
    }

    void setTheme(Theme theme) {
        this.theme = theme;
        draw();
    }

    DoubleProperty attackAt() {
        return attackAt;
    }

    DoubleProperty decayAt() {
        return decayAt;
    }

    DoubleProperty sustainAt() {
        return sustainAt;
    }

    DoubleProperty releaseAt() {
        return releaseAt;
    }

    private double segmentWidth() {
        return (getWidth() - 2 * PADDING) * SEGMENT_SHARE;
    }

    private double baseline() {
        return getHeight() - PADDING;
    }

    private double peakX() {
        return PADDING + attackAt.get() * segmentWidth();
    }

    private double sustainX() {
        return peakX() + decayAt.get() * segmentWidth();
    }

    private double sustainY() {
        return baseline() - sustainAt.get() * (baseline() - PADDING);
    }

    private double endX() {
        return sustainX() + (getWidth() - 2 * PADDING) * HOLD_SHARE + releaseAt.get() * segmentWidth();
    }

    private Handle nearestHandle(MouseEvent event) {
        Handle closest = null;
        double best = GRAB_RADIUS;
        for (Handle handle : Handle.values()) {
            double distance = switch (handle) {
                case PEAK -> distance(event, peakX(), PADDING);
                case SUSTAIN -> distance(event, sustainX(), sustainY());
                case END -> distance(event, endX(), baseline());
            };
            if (distance < best) {
                best = distance;
                closest = handle;
            }
        }
        return closest;
    }

    private static double distance(MouseEvent event, double x, double y) {
        return Math.hypot(event.getX() - x, event.getY() - y);
    }

    private void drag(MouseEvent event) {
        if (dragging == null) {
            return;
        }
        double segment = segmentWidth();
        switch (dragging) {
            case PEAK -> attackAt.set(clamp((event.getX() - PADDING) / segment));
            case SUSTAIN -> {
                decayAt.set(clamp((event.getX() - peakX()) / segment));
                sustainAt.set(clamp((baseline() - event.getY()) / (baseline() - PADDING)));
            }
            case END -> releaseAt.set(clamp(
                    (event.getX() - sustainX() - (getWidth() - 2 * PADDING) * HOLD_SHARE) / segment));
        }
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
        g.strokeLine(PADDING, baseline(), width - PADDING, baseline());
        g.strokeLine(PADDING, PADDING, width - PADDING, PADDING);

        double holdX = sustainX() + (width - 2 * PADDING) * HOLD_SHARE;
        double[] xs = {PADDING, peakX(), sustainX(), holdX, endX()};
        double[] ys = {baseline(), PADDING, sustainY(), sustainY(), baseline()};

        g.setFill(accentColour.deriveColor(0, 1, 1, 0.18));
        g.fillPolygon(xs, ys, xs.length);
        g.setStroke(accentColour);
        g.setLineWidth(2);
        g.setLineCap(StrokeLineCap.ROUND);
        g.strokePolyline(xs, ys, xs.length);

        handle(g, peakX(), PADDING);
        handle(g, sustainX(), sustainY());
        handle(g, endX(), baseline());

        g.setFill(theme.mutedText());
        g.fillText("A " + attack.format(attack.valueOf(attackAt.get()))
                + "   D " + decay.format(decay.valueOf(decayAt.get()))
                + "   S " + sustain.format(sustain.valueOf(sustainAt.get()))
                + "   R " + release.format(release.valueOf(releaseAt.get())), PADDING, height - 4);
    }

    private void handle(GraphicsContext g, double x, double y) {
        g.setFill(theme.accent(accent));
        g.fillOval(x - 6, y - 6, 12, 12);
        g.setFill(theme.canvas());
        g.fillOval(x - 2.5, y - 2.5, 5, 5);
    }
}

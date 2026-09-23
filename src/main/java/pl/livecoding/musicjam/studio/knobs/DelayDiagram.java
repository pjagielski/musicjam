package pl.livecoding.musicjam.studio.knobs;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import pl.livecoding.musicjam.synth.DelayMode;
import pl.livecoding.musicjam.synth.EffectParams;

/**
 * Where the delay's repeats land, ear by ear: a dot per repeat on a left and a right lane, each one
 * smaller than the last by the feedback. The mode is the picture — mono stacks the lanes, ping-pong
 * alternates them, stereo runs the right one at two thirds of the left, tape lets them wander.
 *
 * <p>A sketch of the behaviour, not a meter: it redraws from the knobs, not from the sound.
 */
final class DelayDiagram extends Canvas {

    private static final double WINDOW_MILLIS = 1500;
    private static final double LEFT_LANE = 12;
    private static final double RIGHT_LANE = 36;
    private static final double START = 30;
    private static final double LANES_HEIGHT = 48;

    private final Theme.Accent accent;
    private Theme theme;
    private EffectParams effects = EffectParams.DEFAULT;

    DelayDiagram(double width, Theme.Accent accent, Theme theme) {
        super(width, LANES_HEIGHT);
        this.accent = accent;
        this.theme = theme;
        // made taller to line up with a neighbour, it draws its two lanes in the middle of the room
        heightProperty().addListener((property, before, after) -> draw());
        draw();
    }

    void update(EffectParams next) {
        effects = next;
        draw();
    }

    void setTheme(Theme next) {
        theme = next;
        draw();
    }

    private void draw() {
        GraphicsContext g = getGraphicsContext2D();
        double width = getWidth();
        g.clearRect(0, 0, width, getHeight());
        g.save();
        g.translate(0, Math.max(0, (getHeight() - LANES_HEIGHT) / 2));

        g.setStroke(theme.grid());
        g.setLineWidth(1);
        g.strokeLine(18, LEFT_LANE, width, LEFT_LANE);
        g.strokeLine(18, RIGHT_LANE, width, RIGHT_LANE);
        g.setFill(theme.mutedText());
        g.fillText("L", 2, LEFT_LANE + 4);
        g.fillText("R", 2, RIGHT_LANE + 4);

        DelayMode mode = effects.delayMode();
        double usable = width - START - 8;
        double spacing = Math.max(16, Math.min(usable / 3, usable * effects.delayMillis() / WINDOW_MILLIS));
        double faded = effects.delayMix() > 0 ? 1.0 : 0.35;

        // the dry note: into the left line only for ping-pong, both sides otherwise
        dot(g, START, LEFT_LANE, 1.0, 1.0, true);
        if (mode != DelayMode.PING_PONG) {
            dot(g, START, RIGHT_LANE, 1.0, 1.0, true);
        }

        double level = 1.0;
        for (int repeat = 1; level > 0.06; repeat++) {
            double x = START + repeat * spacing;
            if (x > width - 6) {
                break;
            }
            switch (mode) {
                case MONO -> {
                    dot(g, x, LEFT_LANE, level, faded, false);
                    dot(g, x, RIGHT_LANE, level, faded, false);
                }
                case PING_PONG -> dot(g, x, repeat % 2 == 1 ? LEFT_LANE : RIGHT_LANE, level, faded, false);
                case STEREO -> {
                    dot(g, x, LEFT_LANE, level, faded, false);
                    double right = START + repeat * spacing * DelayMode.STEREO_RATIO;
                    dot(g, right, RIGHT_LANE, level, faded, false);
                }
                case TAPE -> {
                    double wander = spacing * 0.08 * Math.sin(repeat * 1.7);
                    dot(g, x + wander, LEFT_LANE, level, faded, false);
                    dot(g, x - wander, RIGHT_LANE, level, faded, false);
                }
            }
            level *= effects.delayFeedback();
        }
        g.restore();
    }

    private void dot(GraphicsContext g, double x, double y, double level, double faded, boolean dry) {
        double radius = 2.5 + 5.0 * level;
        g.setFill(dry ? theme.text() : theme.accent(accent).deriveColor(0, 1, 1, (0.25 + 0.75 * level) * faded));
        g.fillOval(x - radius, y - radius, 2 * radius, 2 * radius);
    }
}

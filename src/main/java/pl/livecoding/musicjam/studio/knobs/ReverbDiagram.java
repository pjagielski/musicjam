package pl.livecoding.musicjam.studio.knobs;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import pl.livecoding.musicjam.synth.EffectParams;

/**
 * The reverb's tail as a row of bars dying away after the note that set it off: a bigger room makes
 * them fall more slowly, more damping fades them sooner, and the mix decides how tall the tail
 * stands next to the note. Like {@link DelayDiagram}, drawn from the knobs.
 */
final class ReverbDiagram extends Canvas {

    private static final double BAR = 5;
    private static final double STEP = 12;

    private final Theme.Accent accent;
    private Theme theme;
    private EffectParams effects = EffectParams.DEFAULT;

    ReverbDiagram(double width, double height, Theme.Accent accent, Theme theme) {
        super(width, height);
        heightProperty().addListener((property, before, after) -> draw());
        this.accent = accent;
        this.theme = theme;
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
        double floor = getHeight() - 6;
        double tallest = floor - 6;
        g.clearRect(0, 0, width, getHeight());

        g.setStroke(theme.grid());
        g.setLineWidth(1);
        g.strokeLine(0, floor, width, floor);

        // the note that set the room off, a little shorter than the frame so the tail can be read
        double note = tallest * 0.8;
        g.setFill(theme.text());
        g.fillRoundRect(4, floor - note, BAR + 1, note, 3, 3);

        double decayPerBar = 0.05 + 0.35 * (1 - effects.reverbSize());
        double stands = 0.45 + 0.55 * effects.reverbMix();
        for (int bar = 1; ; bar++) {
            double x = 4 + bar * STEP;
            double level = Math.exp(-bar * decayPerBar);
            if (x + BAR > width || level < 0.03) {
                break;
            }
            double height = note * 0.9 * level * stands;
            double opacity = Math.max(0.08, level * (1 - 0.5 * effects.reverbDamping()));
            g.setFill(theme.accent(accent).deriveColor(0, 1, 1, opacity));
            g.fillRoundRect(x, floor - height, BAR, height, 3, 3);
        }
    }
}

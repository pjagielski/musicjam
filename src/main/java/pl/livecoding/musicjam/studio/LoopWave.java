package pl.livecoding.musicjam.studio;

import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import pl.livecoding.musicjam.audio.Sample;

/**
 * A loop's audio drawn as a waveform, with the bars it is taken to fill laid over it and the
 * playhead moving across while the jam plays. It is to a loop track what the roll is to a melody:
 * the way to see that the bars given to it are the right ones, since a break whose bars are wrong
 * has its hits visibly off the lines rather than merely sounding wrong.
 *
 * <p>The waveform is drawn once, on its own canvas, and the playhead on a second over it, so the
 * line moving sixty times a second does not redraw a hundred thousand frames of audio with it.
 */
final class LoopWave {

    private static final Color BACKGROUND = Color.web("#ffffff");
    private static final Color EDGE = Color.web("#dee2e6");
    private static final Color WAVE = Color.web("#4dabf7");
    private static final Color MIDDLE = Color.web("#ced4da");
    private static final Color BAR_LINE = Color.web("#adb5bd");
    private static final Color BEAT_LINE = Color.web("#e9ecef");
    private static final Color BAR_TEXT = Color.web("#868e96");
    private static final Color PAST_THE_LOOP = Color.web("#e9ecef");
    private static final Color PLAYHEAD = Color.web("#e03131");

    private final Canvas wave;
    private final Canvas playhead;
    private final Pane node = new Pane();
    // the last column the playhead was drawn in, so it is only redrawn when it has moved one
    private int shownX = -1;

    LoopWave(double width, double height) {
        wave = new Canvas(width, height);
        playhead = new Canvas(width, height);
        playhead.setMouseTransparent(true);
        node.getChildren().addAll(wave, playhead);
        node.setMinSize(width, height);
        node.setPrefSize(width, height);
        node.setMaxSize(width, height);
    }

    Node node() {
        return node;
    }

    /**
     * The audio over {@code bars} bars of the jam. {@code playedBeats} is how much of it is reached
     * before the jam's loop comes round and starts it again: the rest is shaded, since it is there
     * in the file but never heard.
     */
    void show(Sample audio, double bars, int beatsPerBar, double playedBeats) {
        double width = wave.getWidth();
        double height = wave.getHeight();
        double middle = height / 2;
        GraphicsContext g = wave.getGraphicsContext2D();
        g.setFill(BACKGROUND);
        g.fillRect(0, 0, width, height);

        float[] mono = audio.copyMono();
        int columns = (int) width;
        g.setStroke(WAVE);
        g.setLineWidth(1);
        for (int x = 0; x < columns; x++) {
            int from = (int) ((long) x * mono.length / columns);
            int to = (int) ((long) (x + 1) * mono.length / columns);
            float low = 0;
            float high = 0;
            for (int frame = from; frame < to && frame < mono.length; frame++) {
                low = Math.min(low, mono[frame]);
                high = Math.max(high, mono[frame]);
            }
            // half a pixel across, so a line lands on the column rather than between two
            g.strokeLine(x + 0.5, middle - high * middle, x + 0.5, middle - low * middle);
        }
        g.setStroke(MIDDLE);
        g.strokeLine(0, middle, width, middle);

        double beats = bars * beatsPerBar;
        for (double beat = 0; beat < beats; beat++) {
            double x = Math.round(beat / beats * width) + 0.5;
            boolean bar = beat % beatsPerBar == 0;
            g.setStroke(bar ? BAR_LINE : BEAT_LINE);
            g.strokeLine(x, 0, x, height);
            if (bar) {
                g.setFill(BAR_TEXT);
                g.setFont(Font.font(10));
                g.fillText(String.valueOf((int) (beat / beatsPerBar) + 1), x + 4, 12);
            }
        }
        if (playedBeats > 0 && playedBeats < beats) {
            g.setFill(PAST_THE_LOOP.deriveColor(0, 1, 1, 0.65));
            double from = playedBeats / beats * width;
            g.fillRect(from, 0, width - from, height);
        }
        g.setStroke(EDGE);
        g.strokeRect(0.5, 0.5, width - 1, height - 1);
        setPlayhead(-1);
    }

    /** Where in the pass the jam is, from 0 at its start to 1 at its end; below 0 for nowhere. */
    void setPlayhead(double fraction) {
        int x = fraction < 0 ? -1 : (int) Math.round(fraction * wave.getWidth());
        if (x == shownX) {
            return;
        }
        shownX = x;
        GraphicsContext g = playhead.getGraphicsContext2D();
        g.clearRect(0, 0, playhead.getWidth(), playhead.getHeight());
        if (x >= 0) {
            g.setStroke(PLAYHEAD);
            g.setLineWidth(2);
            g.strokeLine(x, 0, x, playhead.getHeight());
        }
    }
}

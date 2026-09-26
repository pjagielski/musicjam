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
 * <p>Where the loop is cut into slices, they are drawn too: a faint band behind every other one,
 * so the blocks can be counted, and a mark at each boundary. The marks are the loop's own, not the
 * jam's grid, which is the point of moving them to the hits - a break's slices land where its hits
 * do, and the eye can tell at once whether they did.
 *
 * <p>A click asks for the slice under it, or for the whole loop when it is not being cut, so a cut
 * can be judged by ear as well as by eye without setting the jam going.
 *
 * <p>The waveform is drawn once, on its own canvas, and the playhead on a second over it, so the
 * line moving sixty times a second does not redraw a hundred thousand frames of audio with it.
 */
final class LoopWave {

    /** A piece of the loop asked for by hand, in frames: from {@code from} up to {@code until}. */
    @FunctionalInterface
    interface Region {
        void tried(int from, int until);
    }

    private static final Color BACKGROUND = Color.web("#ffffff");
    private static final Color EDGE = Color.web("#dee2e6");
    private static final Color WAVE = Color.web("#4dabf7");
    private static final Color MIDDLE = Color.web("#ced4da");
    private static final Color BAR_LINE = Color.web("#adb5bd");
    private static final Color BEAT_LINE = Color.web("#e9ecef");
    private static final Color BAR_TEXT = Color.web("#868e96");
    private static final Color PAST_THE_LOOP = Color.web("#e9ecef");
    private static final Color PLAYHEAD = Color.web("#e03131");
    private static final Color SLICE_BAND = Color.web("#f1f3f5");
    private static final Color SLICE_MARK = Color.web("#f76707");

    private final Canvas wave;
    private final Canvas playhead;
    private final Pane node = new Pane();
    // the last column the playhead was drawn in, so it is only redrawn when it has moved one
    private int shownX = -1;
    // what is drawn now, so a click can say which slice it landed in
    private int[] slices = new int[0];
    private int frameCount;
    private Region onTry = (from, until) -> { };

    LoopWave(double width, double height) {
        wave = new Canvas(width, height);
        playhead = new Canvas(width, height);
        playhead.setMouseTransparent(true);
        wave.setCursor(javafx.scene.Cursor.HAND);
        wave.setOnMousePressed(event -> {
            if (frameCount <= 0) {
                return;
            }
            int frame = (int) Math.max(0, Math.min(frameCount - 1, event.getX() / width * frameCount));
            int at = 0;
            while (at + 1 < slices.length && slices[at + 1] <= frame) {
                at++;
            }
            int from = slices.length == 0 ? 0 : slices[at];
            int until = slices.length == 0 ? frameCount
                    : at + 1 < slices.length ? slices[at + 1] : frameCount;
            onTry.tried(from, until);
        });
        node.getChildren().addAll(wave, playhead);
        node.setMinSize(width, height);
        node.setPrefSize(width, height);
        node.setMaxSize(width, height);
    }

    Node node() {
        return node;
    }

    /** What a click on the waveform asks to hear. */
    void setOnTry(Region region) {
        onTry = region;
    }

    /**
     * The audio over {@code bars} bars of the jam. {@code playedBeats} is how much of it is reached
     * before the jam's loop comes round and starts it again: the rest is shaded, since it is there
     * in the file but never heard.
     */
    void show(Sample audio, double bars, int beatsPerBar, double playedBeats, int[] slices) {
        this.slices = slices.clone();
        this.frameCount = audio.frameCount();
        double width = wave.getWidth();
        double height = wave.getHeight();
        double middle = height / 2;
        GraphicsContext g = wave.getGraphicsContext2D();
        g.setFill(BACKGROUND);
        g.fillRect(0, 0, width, height);

        // behind the waveform, so a slice reads as a block rather than as a pair of lines
        g.setFill(SLICE_BAND);
        for (int slice = 1; slice < slices.length; slice += 2) {
            double from = slices[slice] / (double) audio.frameCount() * width;
            double to = (slice + 1 < slices.length ? slices[slice + 1] : audio.frameCount())
                    / (double) audio.frameCount() * width;
            g.fillRect(from, 0, to - from, height);
        }

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
        // over the grid, since where a slice begins is the thing being judged against it
        g.setStroke(SLICE_MARK);
        g.setLineWidth(1);
        for (int start : slices) {
            double x = Math.round(start / (double) audio.frameCount() * width) + 0.5;
            g.strokeLine(x, 0, x, 10);
            g.strokeLine(x, height - 10, x, height);
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

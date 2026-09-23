package pl.livecoding.musicjam.studio;

import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import pl.livecoding.musicjam.model.Note;
import pl.livecoding.musicjam.model.Voice;

import java.util.List;

/**
 * A melody track's notes laid out as a piano roll: time across, pitch up, a key per row with the
 * black keys shaded and every C named, bar lines, and the playhead moving over it while the jam
 * plays. It only shows, for now: what it draws is the loop as the engine hears it, so a window of
 * a MIDI file starts at the left edge from whichever bar of the file it opens on.
 */
final class PianoRoll {
    static final double KEYS_WIDTH = 34;
    private static final double MIN_ROW = 3;
    private static final double MAX_ROW = 12;
    private static final Color WHITE_ROW = Color.web("#ffffff");
    private static final Color BLACK_ROW = Color.web("#f1f3f5");
    private static final Color OCTAVE_LINE = Color.web("#ced4da");
    private static final Color BAR_LINE = Color.web("#adb5bd");
    private static final Color BEAT_LINE = Color.web("#e9ecef");
    private static final Color NOTE = Color.web("#1c7ed6");
    private static final Color NOTE_EDGE = Color.web("#1864ab");
    private static final Color KEY_TEXT = Color.web("#868e96");
    private static final Color WHITE_KEY = Color.web("#fdfdfd");
    private static final Color BLACK_KEY = Color.web("#495057");
    private static final Color KEY_SEAM = Color.web("#dee2e6");
    private static final Color PLAYHEAD = Color.web("#e03131");

    /** The pitches the roll spans: the notes' own, with room around them and never under an octave. */
    record Keys(int low, int high) {
        static final int SMALLEST = 12;
        static final int ROOM = 2;

        static Keys of(List<Note> notes) {
            int low = Integer.MAX_VALUE;
            int high = Integer.MIN_VALUE;
            for (Note note : notes) {
                if (note.voice() instanceof Voice.Pitch pitch) {
                    low = Math.min(low, pitch.midiNote());
                    high = Math.max(high, pitch.midiNote());
                }
            }
            if (low > high) {
                // nothing to show: the octave around middle C
                return new Keys(54, 54 + SMALLEST);
            }
            low -= ROOM;
            high += ROOM;
            int missing = SMALLEST - (high - low);
            if (missing > 0) {
                low -= missing / 2;
                high += missing - missing / 2;
            }
            return new Keys(Math.max(0, low), Math.min(127, high));
        }

        int rows() {
            return high - low + 1;
        }
    }

    private final Canvas notes = new Canvas();
    private final Canvas playhead = new Canvas();
    private final Pane node = new Pane(notes, playhead);
    private final double width;
    private final double tallest;
    private double lengthBeats = 1;
    private int shownX = -1;

    /** A roll {@code width} wide, and at most {@code tallest} high however many keys it spans. */
    PianoRoll(double width, double tallest) {
        this.width = width;
        this.tallest = tallest;
        node.setStyle("-fx-border-color: #dee2e6;");
        playhead.setMouseTransparent(true);
    }

    Node node() {
        return node;
    }

    /** Draws {@code shown} over a loop of {@code lengthBeats}, with a line at every bar. */
    void show(List<Note> shown, double lengthBeats, int beatsPerBar) {
        this.lengthBeats = lengthBeats;
        Keys keys = Keys.of(shown);
        double row = Math.max(MIN_ROW, Math.min(MAX_ROW, Math.floor(tallest / keys.rows())));
        double height = row * keys.rows();
        for (Canvas canvas : List.of(notes, playhead)) {
            canvas.setWidth(width);
            canvas.setHeight(height);
        }
        node.setPrefSize(width, height);
        node.setMinSize(width, height);
        node.setMaxSize(width, height);

        GraphicsContext g = notes.getGraphicsContext2D();
        g.clearRect(0, 0, width, height);
        double rollWidth = width - KEYS_WIDTH;
        double perBeat = rollWidth / lengthBeats;
        g.setFont(Font.font(Math.min(10, row + 1)));
        g.setTextBaseline(VPos.CENTER);
        g.setTextAlign(TextAlignment.RIGHT);
        g.setFill(WHITE_KEY);
        g.fillRect(0, 0, KEYS_WIDTH, height);
        for (int pitch = keys.low(); pitch <= keys.high(); pitch++) {
            double y = height - (pitch - keys.low() + 1) * row;
            g.setFill(isBlack(pitch) ? BLACK_ROW : WHITE_ROW);
            g.fillRect(KEYS_WIDTH, y, rollWidth, row);
            if (isBlack(pitch)) {
                g.setFill(BLACK_KEY);
                g.fillRect(0, y, KEYS_WIDTH - 14, row);
            } else if (pitch % 12 == 0 || pitch % 12 == 5) {
                // two white keys side by side, B and C or E and F: the seam between them
                g.setStroke(KEY_SEAM);
                g.setLineWidth(1);
                g.strokeLine(0, y + row - 0.5, KEYS_WIDTH, y + row - 0.5);
            }
            if (pitch % 12 == 0) {
                g.setStroke(OCTAVE_LINE);
                g.setLineWidth(1);
                g.strokeLine(KEYS_WIDTH, y + row - 0.5, width, y + row - 0.5);
                g.setFill(KEY_TEXT);
                g.fillText(name(pitch), KEYS_WIDTH - 2, y + row / 2);
            }
        }
        g.setStroke(OCTAVE_LINE);
        g.strokeLine(KEYS_WIDTH - 0.5, 0, KEYS_WIDTH - 0.5, height);
        // a line at every beat, where there is room for one, and a darker one at every bar
        for (int beat = 0; beat <= Math.ceil(lengthBeats); beat++) {
            boolean bar = beat % beatsPerBar == 0;
            if (!bar && perBeat < 6) {
                continue;
            }
            double x = Math.min(width - 0.5, Math.round(KEYS_WIDTH + beat * perBeat) + 0.5);
            g.setStroke(bar ? BAR_LINE : BEAT_LINE);
            g.strokeLine(x, 0, x, height);
        }
        for (Note note : shown) {
            if (!(note.voice() instanceof Voice.Pitch pitch) || note.beat() >= lengthBeats) {
                continue;
            }
            double x = KEYS_WIDTH + note.beat() * perBeat;
            double end = KEYS_WIDTH + Math.min(lengthBeats, note.beat() + note.durationBeats()) * perBeat;
            double y = height - (pitch.midiNote() - keys.low() + 1) * row;
            double w = Math.max(2, end - x - 1);
            g.setGlobalAlpha(0.45 + 0.55 * note.velocity());
            g.setFill(NOTE);
            g.fillRoundRect(x, y + 0.5, w, row - 1, 3, 3);
            g.setGlobalAlpha(1);
            if (row >= 6) {
                g.setStroke(NOTE_EDGE);
                g.strokeRoundRect(x + 0.5, y + 1, w - 1, row - 2, 3, 3);
            }
        }
        shownX = -1;
        playhead.getGraphicsContext2D().clearRect(0, 0, width, height);
    }

    /** The playhead at {@code beat} into the loop, or none for a negative beat. */
    void setPlayhead(double beat) {
        int x = beat < 0 ? -1 : (int) Math.round(KEYS_WIDTH + (beat % lengthBeats) / lengthBeats * (width - KEYS_WIDTH));
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

    private static boolean isBlack(int pitch) {
        return switch (pitch % 12) {
            case 1, 3, 6, 8, 10 -> true;
            default -> false;
        };
    }

    /** The name of a C: C4 is middle C, MIDI note 60. */
    static String name(int pitch) {
        return "C" + (pitch / 12 - 1);
    }
}

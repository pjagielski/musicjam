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
import java.util.function.IntConsumer;

/**
 * A melody track's notes laid out as a piano roll: time across, pitch up, a key per row with the
 * black keys shaded and every C named, bar lines, and the playhead moving over it while the jam
 * plays. It shows at most four bars at a time, so the notes of a long loop stay wide enough to
 * read, and turns the page when the playhead runs off the one it is on. It only shows, for now:
 * what it draws is the loop as the engine hears it, so a window of a MIDI file starts at the left
 * edge from whichever bar of the file it opens on.
 */
final class PianoRoll {
    static final double KEYS_WIDTH = 34;
    static final int BARS_PER_PAGE = 4;
    private static final double MIN_ROW = 3;
    private static final double MAX_ROW = 12;
    private static final Color WHITE_ROW = Color.web("#ffffff");
    private static final Color BLACK_ROW = Color.web("#f1f3f5");
    private static final Color PAST_THE_LOOP = Color.web("#e9ecef");
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

    /**
     * A loop of {@code lengthBeats} cut into pages of {@code pageBeats}: as long as the loop when it
     * is short, four bars when it is longer. The last page can be cut short by the loop's end.
     */
    record Pages(double lengthBeats, double pageBeats) {

        static Pages of(double lengthBeats, int beatsPerBar) {
            return new Pages(lengthBeats, Math.min(lengthBeats, BARS_PER_PAGE * beatsPerBar));
        }

        int count() {
            // a hair's allowance, so eight bars in pages of four is two pages and not a sliver of a third
            return Math.max(1, (int) Math.ceil(lengthBeats / pageBeats - 1e-9));
        }

        /** The page {@code beat} of the loop falls on. */
        int of(double beat) {
            return Math.max(0, Math.min(count() - 1, (int) Math.floor(beat / pageBeats)));
        }

        double start(int page) {
            return page * pageBeats;
        }
    }

    private final Canvas notes = new Canvas();
    private final Canvas playhead = new Canvas();
    private final Pane node = new Pane(notes, playhead);
    private final double width;
    private final double tallest;
    private List<Note> shown = List.of();
    private Keys keys = new Keys(54, 66);
    private double row = MAX_ROW;
    private int beatsPerBar = 4;
    private Pages pages = new Pages(1, 1);
    private int page;
    // the page the playhead was last on, so a page turned by hand stays until the playhead moves on
    private int playheadPage = -1;
    private int shownX = -1;
    private IntConsumer onPage = page -> { };

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

    /** Hears of every page shown, by the playhead or by hand. */
    void setOnPage(IntConsumer listener) {
        onPage = listener;
    }

    int page() {
        return page;
    }

    Pages pages() {
        return pages;
    }

    /**
     * Draws {@code shown} over a loop of {@code lengthBeats}, with a line at every bar: the page
     * it was on when the loop still has it, the first when it does not. The keys are the whole
     * loop's, so turning the page never moves the notes up or down.
     */
    void show(List<Note> shown, double lengthBeats, int beatsPerBar) {
        this.shown = List.copyOf(shown);
        this.beatsPerBar = beatsPerBar;
        pages = Pages.of(lengthBeats, beatsPerBar);
        keys = Keys.of(shown);
        row = Math.max(MIN_ROW, Math.min(MAX_ROW, Math.floor(tallest / keys.rows())));
        double height = row * keys.rows();
        for (Canvas canvas : List.of(notes, playhead)) {
            canvas.setWidth(width);
            canvas.setHeight(height);
        }
        node.setPrefSize(width, height);
        node.setMinSize(width, height);
        node.setMaxSize(width, height);
        playheadPage = -1;
        turnTo(page < pages.count() ? page : 0);
    }

    /** Shows another page, as the page buttons ask. */
    void showPage(int next) {
        turnTo(Math.max(0, Math.min(pages.count() - 1, next)));
    }

    /**
     * The playhead at {@code beat} into the loop, or none for a negative beat. Running onto another
     * page turns to it.
     */
    void setPlayhead(double beat) {
        if (beat < 0) {
            playheadPage = -1;
            drawPlayhead(-1);
            return;
        }
        double into = beat % pages.lengthBeats();
        int on = pages.of(into);
        if (on != playheadPage) {
            playheadPage = on;
            if (on != page) {
                turnTo(on);
            }
        }
        int x = on != page ? -1 : (int) Math.round(KEYS_WIDTH + (into - pages.start(page)) * perBeat());
        drawPlayhead(x);
    }

    private void turnTo(int next) {
        page = next;
        drawNotes();
        shownX = -2;
        drawPlayhead(-1);
        onPage.accept(page);
    }

    private double perBeat() {
        return (width - KEYS_WIDTH) / pages.pageBeats();
    }

    private void drawNotes() {
        double height = notes.getHeight();
        GraphicsContext g = notes.getGraphicsContext2D();
        g.clearRect(0, 0, width, height);
        double rollWidth = width - KEYS_WIDTH;
        double perBeat = perBeat();
        double from = pages.start(page);
        double to = Math.min(pages.lengthBeats(), from + pages.pageBeats());
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
        // a last page the loop ends partway through: what lies past its end is not played
        double loopEnd = KEYS_WIDTH + (to - from) * perBeat;
        if (loopEnd < width) {
            g.setFill(PAST_THE_LOOP);
            g.fillRect(loopEnd, 0, width - loopEnd, height);
        }
        g.setStroke(OCTAVE_LINE);
        g.strokeLine(KEYS_WIDTH - 0.5, 0, KEYS_WIDTH - 0.5, height);
        // a line at every beat, where there is room for one, and a darker one at every bar
        for (int beat = (int) Math.ceil(from); beat <= Math.ceil(to); beat++) {
            boolean bar = beat % beatsPerBar == 0;
            if (!bar && perBeat < 6) {
                continue;
            }
            double x = Math.min(width - 0.5, Math.round(KEYS_WIDTH + (beat - from) * perBeat) + 0.5);
            g.setStroke(bar ? BAR_LINE : BEAT_LINE);
            g.strokeLine(x, 0, x, height);
        }
        for (Note note : shown) {
            double end = Math.min(to, note.beat() + note.durationBeats());
            if (!(note.voice() instanceof Voice.Pitch pitch) || note.beat() >= to || end <= from) {
                continue;
            }
            // a note begun on the page before starts at the left edge
            double x = KEYS_WIDTH + (Math.max(from, note.beat()) - from) * perBeat;
            double right = KEYS_WIDTH + (end - from) * perBeat;
            double y = height - (pitch.midiNote() - keys.low() + 1) * row;
            double w = Math.max(2, right - x - 1);
            g.setGlobalAlpha(0.45 + 0.55 * note.velocity());
            g.setFill(NOTE);
            g.fillRoundRect(x, y + 0.5, w, row - 1, 3, 3);
            g.setGlobalAlpha(1);
            if (row >= 6) {
                g.setStroke(NOTE_EDGE);
                g.strokeRoundRect(x + 0.5, y + 1, w - 1, row - 2, 3, 3);
            }
        }
    }

    private void drawPlayhead(int x) {
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

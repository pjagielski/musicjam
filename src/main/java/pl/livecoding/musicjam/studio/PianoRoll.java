package pl.livecoding.musicjam.studio;

import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import pl.livecoding.musicjam.model.Chord;
import pl.livecoding.musicjam.model.Note;
import pl.livecoding.musicjam.model.Progression;
import pl.livecoding.musicjam.model.Scale;
import pl.livecoding.musicjam.studio.knobs.NoPanning;
import pl.livecoding.musicjam.model.Voice;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * A melody track's notes laid out as a piano roll: time across, pitch up, a key per row with the
 * black keys shaded and every C named, bar lines, and the playhead moving over it while the jam
 * plays. It shows at most four bars at a time, so the notes of a long loop stay wide enough to
 * read, and turns the page when the playhead runs off the one it is on. What it draws is the loop
 * as the engine hears it, so a window of a MIDI file starts at the left edge from whichever bar of
 * the file it opens on.
 *
 * <p>The roll opens on the keys its notes need and as many more as it is tall enough to draw; the
 * wheel moves that window up and down the keyboard, a couple of keys at a time or an octave with
 * shift, which is how a line an octave below what is written is reached. Once the wheel has been
 * used the window stays where it was put.
 *
 * <p>With chords named for the jam, each bar marks the rows its own chord holds, more strongly
 * than the key shades the rest, and carries the chord's name above it — so the notes that will
 * sound consonant under the playhead are the obvious ones to reach for.
 *
 * <p>The keys down the left are played by clicking them, whether the jam is running or stopped,
 * and light while a note of the loop is sounding on them. With a key chosen for the jam, the rows
 * and keys that do not belong to it are shaded, so what fits is what stays pale; nothing is
 * forbidden, a note outside the key simply looks like the choice it is.
 *
 * <p>Made editable, it takes a hand as a DAW's roll does: click an empty row to put a note there,
 * drag one to move it, drag its right edge to change its length, right-click it to take it away.
 * A drag over empty rows draws a band round every note it touches, shift adds one to what is held,
 * and what is held moves, is turned up or is taken away as one. Under the roll a lane of bars
 * carries the notes' velocities, each bar dragged up or down to set it. Everything snaps to a
 * sixteenth; the edits themselves are {@link NoteEdits}.
 */
final class PianoRoll {
    static final double KEYS_WIDTH = 34;
    static final int BARS_PER_PAGE = 4;
    private static final double MIN_ROW = 3;
    private static final double MAX_ROW = 12;
    // the row height a roll opens at when the notes leave it the choice
    private static final double PREFERRED_ROW = 8;
    private static final Color WHITE_ROW = Color.web("#ffffff");
    private static final Color BLACK_ROW = Color.web("#f5f7f9");
    // rows and keys the jam's key does not hold, and the row its root sits on
    private static final Color OUT_ROW = Color.web("#e3e6ea");
    private static final Color OUT_KEY = Color.web("#c9ced4");
    private static final Color OUT_BLACK_KEY = Color.web("#868e96");
    private static final Color ROOT_ROW = Color.web("#eef6fc");
    private static final Color ROOT_TEXT = Color.web("#1c7ed6");
    // the rows of the bar's own chord, and its name written above the bar
    private static final Color CHORD_ROW = Color.web("#d0ebff");
    private static final Color CHORD_TEXT = Color.web("#1971c2");
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
    private static final Color LIT_WHITE = Color.web("#a5d8ff");
    private static final Color LIT_BLACK = Color.web("#1c7ed6");
    private static final Color HELD = Color.web("#f76707");
    private static final Color HELD_EDGE = Color.web("#d9480f");
    private static final Color BAND = Color.web("#f76707");
    private static final Color LANE = Color.web("#f8f9fa");
    private static final Color BAR = Color.web("#74c0fc");
    // how near a note's right edge a press has to be to drag its length rather than the note
    private static final double EDGE = 5;
    // how far the wheel moves the window: a couple of keys, or an octave with shift
    private static final int WHEEL_KEYS = 2;
    private static final int WHEEL_OCTAVE = 12;
    // the lane of velocity bars under the roll, and the gap above it
    private static final double LANE_HEIGHT = 46;
    private static final double LANE_GAP = 6;
    private static final double BAR_WIDTH = 7;

    /**
     * The pitches the roll spans: the notes' own with room around them, and then as many more as
     * the roll is tall enough to draw, so a line of two notes still opens on octaves to write in
     * rather than on a band of five keys.
     */
    record Keys(int low, int high) {
        static final int SMALLEST = 12;
        static final int ROOM = 2;

        /** The keys for {@code notes} in a roll with room for {@code rows} of them. */
        static Keys of(List<Note> notes, int rows) {
            int low = Integer.MAX_VALUE;
            int high = Integer.MIN_VALUE;
            for (Note note : notes) {
                if (note.voice() instanceof Voice.Pitch pitch) {
                    low = Math.min(low, pitch.midiNote());
                    high = Math.max(high, pitch.midiNote());
                }
            }
            if (low > high) {
                // nothing written yet: the rows around middle C
                low = 60;
                high = 60;
            }
            low -= ROOM;
            high += ROOM;
            int missing = Math.max(SMALLEST, rows) - (high - low + 1);
            if (missing > 0) {
                low -= missing / 2;
                high += missing - missing / 2;
            }
            // held inside the keyboard, and what one end gives up the other takes
            if (low < 0) {
                high = Math.min(127, high - low);
                low = 0;
            }
            if (high > 127) {
                low = Math.max(0, low - (high - 127));
                high = 127;
            }
            return new Keys(low, high);
        }

        /** How many rows of {@code notes} there must be, whatever the roll's height allows. */
        static int rowsFor(List<Note> notes) {
            return of(notes, 0).rows();
        }

        int rows() {
            return high - low + 1;
        }

        /** Whether every one of {@code notes} has a row here: if it has, the roll need not move. */
        boolean hold(List<Note> notes) {
            return notes.stream().allMatch(note -> !(note.voice() instanceof Voice.Pitch pitch)
                    || (pitch.midiNote() >= low && pitch.midiNote() <= high));
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
    private final Canvas velocities = new Canvas();
    private final Pane node = new Pane(notes, playhead, velocities);
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
    private boolean drawn;
    // the wheel has moved the window: from then on it stays where the hand put it
    private boolean chosenView;
    private IntConsumer onPage = page -> { };
    private Consumer<List<Note>> onEdit;
    private IntConsumer onKey = pitch -> { };
    // the keys lit now: those a note of the loop is sounding on, and one pressed by hand
    private Set<Integer> lit = Set.of();
    private Scale scale = Scale.ANY;
    private Progression chords = Progression.NONE;
    private int pressedKey = -1;
    // a drag in progress: the notes as they were when it started, and the one under the hand
    private List<Note> dragFrom;
    private Note dragged;
    private boolean resizing;
    private double grabbedBeat;
    private int grabbedPitch;
    // the notes the hand is holding: what a move, a turn of velocity or a delete works on
    private List<Note> held = List.of();
    // the same notes as they stood when the drag began. An edit makes new notes, so held is a list
    // of values that dragFrom no longer contains; every step of a drag has to work from these two
    // together, or the second step finds nothing to move and the first is undone.
    private List<Note> dragHeld = List.of();
    // a press on empty rows, which becomes a band if the hand moves and a new note if it does not
    private boolean pressedOnEmpty;
    private boolean banding;
    private double bandFromX;
    private double bandFromY;
    private double bandToX;
    private double bandToY;
    // a velocity bar being dragged: where it was taken, and the notes that go with it
    private double velocityFromY;
    // the length the last note added or resized had, which the next one added takes
    private double lastLength = 1.0;

    /** A roll {@code width} wide, and at most {@code tallest} high however many keys it spans. */
    PianoRoll(double width, double tallest) {
        this.width = width;
        this.tallest = tallest;
        node.setStyle("-fx-border-color: #dee2e6;");
        playhead.setMouseTransparent(true);
        NoPanning.on(notes);
        NoPanning.on(velocities);
        notes.setFocusTraversable(true);
        notes.setOnMousePressed(this::pressed);
        notes.setOnMouseDragged(this::dragged);
        notes.setOnMouseReleased(this::released);
        notes.setOnMouseMoved(this::hovered);
        notes.setOnMouseExited(event -> notes.setCursor(Cursor.DEFAULT));
        notes.setOnScroll(this::scrolled);
        notes.setOnKeyPressed(this::keyed);
        velocities.setOnMousePressed(this::velocityPressed);
        velocities.setOnMouseDragged(this::velocityDragged);
        velocities.setOnMouseReleased(event -> finish());
    }

    /** The key the jam is in, which the rows and the keyboard are shaded by. */
    void setScale(Scale next) {
        if (!next.equals(scale)) {
            scale = next;
            if (drawn) {
                drawNotes();
            }
        }
    }

    /**
     * The wheel over the roll: the window of keys moves rather than the window behind it, so the
     * scroll pane the studio sits in stays where it is.
     */
    private void scrolled(ScrollEvent event) {
        int step = event.isShiftDown() ? WHEEL_OCTAVE : WHEEL_KEYS;
        int by = event.getDeltaY() > 0 ? step : event.getDeltaY() < 0 ? -step : 0;
        event.consume();
        if (by == 0) {
            return;
        }
        int rows = keys.rows();
        int low = Math.max(0, Math.min(127 - rows + 1, keys.low() + by));
        Keys next = new Keys(low, low + rows - 1);
        if (!next.equals(keys)) {
            keys = next;
            chosenView = true;
            drawNotes();
        }
    }

    /** The chords the jam goes round, one to a bar, which each bar marks its own rows by. */
    void setChords(Progression next) {
        if (!next.equals(chords)) {
            chords = next;
            if (drawn) {
                drawNotes();
            }
        }
    }

    /** Hears the pitch of every key pressed on the keyboard down the left. */
    void setOnKey(IntConsumer listener) {
        onKey = listener;
    }

    Node node() {
        return node;
    }

    /**
     * Lets a hand change the notes: {@code onEdit} hears the whole line back after every edit.
     * Called once, before the first {@link #show}.
     */
    void setOnEdit(Consumer<List<Note>> listener) {
        onEdit = listener;
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
        if (dragged != null) {
            // a hand is on a note; what it is doing is newer than what the studio is handing back
            return;
        }
        this.shown = List.copyOf(shown);
        this.beatsPerBar = beatsPerBar;
        pages = Pages.of(lengthBeats, beatsPerBar);
        // the keys stay where they are while the notes fit them, so an edit never slides the rest
        // of the line up or down under the hand that made it
        if (!drawn || (!chosenView && !keys.hold(this.shown))) {
            // as many rows as the roll can draw at a comfortable size, and more when the notes need them
            double room = tallest - LANE_GAP - LANE_HEIGHT;
            keys = Keys.of(this.shown, Math.max(Keys.rowsFor(this.shown), (int) (room / PREFERRED_ROW)));
            drawn = true;
        }
        row = Math.max(MIN_ROW, Math.min(MAX_ROW,
                Math.floor((tallest - LANE_GAP - LANE_HEIGHT) / keys.rows())));
        // the keys the roll settled on may leave room over; it is the notes that get it, not the gap
        double height = row * keys.rows();
        for (Canvas canvas : List.of(notes, playhead)) {
            canvas.setWidth(width);
            canvas.setHeight(height);
        }
        velocities.setWidth(width);
        velocities.setHeight(LANE_HEIGHT);
        velocities.setLayoutY(height + LANE_GAP);
        double whole = height + LANE_GAP + LANE_HEIGHT;
        node.setPrefSize(width, whole);
        node.setMinSize(width, whole);
        node.setMaxSize(width, whole);
        // what the hand was holding, as far as the new line still has it
        hold(this.shown.stream().filter(held::contains).toList());
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
            lit = Set.of();
            drawPlayhead(-1, true);
            return;
        }
        light(beat % pages.lengthBeats());
        double into = beat % pages.lengthBeats();
        int on = pages.of(into);
        if (on != playheadPage) {
            playheadPage = on;
            if (on != page) {
                turnTo(on);
            }
        }
        int x = on != page ? -1 : (int) Math.round(KEYS_WIDTH + (into - pages.start(page)) * perBeat());
        drawPlayhead(x, false);
    }

    /** The keys of every note sounding at {@code beat}, whichever page they are on. */
    private void light(double beat) {
        Set<Integer> sounding = new HashSet<>();
        for (Note note : shown) {
            if (note.voice() instanceof Voice.Pitch pitch
                    && beat >= note.beat() && beat < note.beat() + note.durationBeats()) {
                sounding.add(pitch.midiNote());
            }
        }
        if (!sounding.equals(lit)) {
            lit = sounding;
            drawPlayhead(shownX, true);
        }
    }

    private void turnTo(int next) {
        page = next;
        drawNotes();
        shownX = -2;
        drawPlayhead(-1, true);
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
            boolean held = scale.holds(pitch);
            Color plain = !held ? OUT_ROW : scale.isRoot(pitch) ? ROOT_ROW : isBlack(pitch) ? BLACK_ROW : WHITE_ROW;
            if (chords.isEmpty()) {
                g.setFill(plain);
                g.fillRect(KEYS_WIDTH, y, rollWidth, row);
            } else {
                for (int bar = (int) Math.floor(from / beatsPerBar); bar * beatsPerBar < to; bar++) {
                    double barFrom = Math.max(from, bar * beatsPerBar);
                    double barTo = Math.min(to, (bar + 1.0) * beatsPerBar);
                    Chord chord = chords.at(bar * (double) beatsPerBar, beatsPerBar);
                    g.setFill(chord != null && chord.holds(pitch) && held ? CHORD_ROW : plain);
                    g.fillRect(KEYS_WIDTH + (barFrom - from) * perBeat, y,
                            (barTo - barFrom) * perBeat, row);
                }
            }
            if (isBlack(pitch)) {
                g.setFill(held ? BLACK_KEY : OUT_BLACK_KEY);
                g.fillRect(0, y, KEYS_WIDTH - 14, row);
            } else if (!held) {
                g.setFill(OUT_KEY);
                g.fillRect(0, y, KEYS_WIDTH - 1, row);
            }
            if (!isBlack(pitch) && (pitch % 12 == 0 || pitch % 12 == 5)) {
                // two white keys side by side, B and C or E and F: the seam between them
                g.setStroke(KEY_SEAM);
                g.setLineWidth(1);
                g.strokeLine(0, y + row - 0.5, KEYS_WIDTH, y + row - 0.5);
            }
            if (pitch % 12 == 0) {
                g.setStroke(OCTAVE_LINE);
                g.setLineWidth(1);
                g.strokeLine(KEYS_WIDTH, y + row - 0.5, width, y + row - 0.5);
            }
            if (scale.isRoot(pitch)) {
                g.setFill(ROOT_TEXT);
                g.fillText(scale.rootName(), KEYS_WIDTH - 2, y + row / 2);
            } else if (pitch % 12 == 0) {
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
        if (!chords.isEmpty()) {
            g.setFont(Font.font(10));
            g.setTextAlign(TextAlignment.LEFT);
            g.setTextBaseline(VPos.TOP);
            g.setFill(CHORD_TEXT);
            for (int bar = (int) Math.floor(from / beatsPerBar); bar * beatsPerBar < to; bar++) {
                Chord chord = chords.at(bar * (double) beatsPerBar, beatsPerBar);
                if (chord != null) {
                    g.fillText(chord.name(), KEYS_WIDTH + 3 + (bar * beatsPerBar - from) * perBeat, 2);
                }
            }
            g.setTextAlign(TextAlignment.RIGHT);
            g.setTextBaseline(VPos.CENTER);
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
            boolean holding = held.contains(note);
            g.setGlobalAlpha(0.45 + 0.55 * note.velocity());
            g.setFill(holding ? HELD : NOTE);
            g.fillRoundRect(x, y + 0.5, w, row - 1, 3, 3);
            g.setGlobalAlpha(1);
            if (row >= 6 || holding) {
                g.setStroke(holding ? HELD_EDGE : NOTE_EDGE);
                g.strokeRoundRect(x + 0.5, y + 1, w - 1, row - 2, 3, 3);
            }
        }
        if (banding) {
            g.setStroke(BAND);
            g.setLineWidth(1);
            g.strokeRect(Math.min(bandFromX, bandToX) + 0.5, Math.min(bandFromY, bandToY) + 0.5,
                    Math.abs(bandToX - bandFromX), Math.abs(bandToY - bandFromY));
        }
        drawVelocities();
    }

    /** A bar per note under the roll, as tall as the note is loud: the line's dynamics at a glance. */
    private void drawVelocities() {
        GraphicsContext g = velocities.getGraphicsContext2D();
        g.clearRect(0, 0, width, LANE_HEIGHT);
        g.setFill(LANE);
        g.fillRect(KEYS_WIDTH, 0, width - KEYS_WIDTH, LANE_HEIGHT);
        g.setStroke(OCTAVE_LINE);
        g.strokeLine(KEYS_WIDTH - 0.5, 0, KEYS_WIDTH - 0.5, LANE_HEIGHT);
        g.strokeLine(KEYS_WIDTH, LANE_HEIGHT - 0.5, width, LANE_HEIGHT - 0.5);
        g.setFill(KEY_TEXT);
        g.setFont(Font.font(9));
        g.setTextAlign(TextAlignment.RIGHT);
        g.setTextBaseline(VPos.CENTER);
        g.fillText("vel", KEYS_WIDTH - 3, LANE_HEIGHT / 2);
        double from = pages.start(page);
        double to = from + pages.pageBeats();
        for (Note note : shown) {
            if (!(note.voice() instanceof Voice.Pitch) || note.beat() < from || note.beat() >= to) {
                continue;
            }
            double x = KEYS_WIDTH + (note.beat() - from) * perBeat();
            double high = Math.max(2, (LANE_HEIGHT - 4) * note.velocity());
            g.setFill(held.contains(note) ? HELD : BAR);
            g.fillRoundRect(x, LANE_HEIGHT - 1 - high, Math.min(BAR_WIDTH, Math.max(2, perBeat() / 4)), high, 2, 2);
        }
    }

    /** A press takes hold of a note, takes one away, or begins a band or a new note. */
    private void pressed(MouseEvent event) {
        notes.requestFocus();
        if (event.getX() < KEYS_WIDTH) {
            // the keyboard: pressed to hear that pitch, lit while the finger is on it
            pressedKey = pitchAt(event.getY());
            drawPlayhead(shownX, true);
            onKey.accept(pressedKey);
            return;
        }
        if (onEdit == null) {
            return;
        }
        Note under = noteAt(event.getX(), event.getY());
        if (event.isSecondaryButtonDown()) {
            // a note of what is held takes the rest with it; one outside goes alone
            if (under != null) {
                change(NoteEdits.remove(shown, held.contains(under) ? held : List.of(under)));
                hold(List.of());
                finish();
            }
            return;
        }
        if (under == null) {
            pressedOnEmpty = true;
            bandFromX = event.getX();
            bandFromY = event.getY();
            if (!event.isShiftDown()) {
                hold(List.of());
                drawNotes();
            }
            return;
        }
        if (event.isShiftDown()) {
            // shift gathers: a note not held joins what is, one already held leaves it
            List<Note> next = new ArrayList<>(held);
            if (!next.remove(under)) {
                next.add(under);
            }
            hold(next);
            drawNotes();
            return;
        }
        if (!held.contains(under)) {
            hold(List.of(under));
        }
        dragFrom = shown;
        dragHeld = held;
        dragged = under;
        resizing = event.getX() > rightEdgeOf(under) - EDGE;
        grabbedBeat = beatAt(event.getX());
        grabbedPitch = pitchAt(event.getY());
        drawNotes();
    }

    private void dragged(MouseEvent event) {
        if (onEdit == null || pressedKey >= 0) {
            return;
        }
        if (pressedOnEmpty) {
            banding = true;
            bandToX = event.getX();
            bandToY = event.getY();
            hold(NoteEdits.within(shown, beatAt(Math.min(bandFromX, bandToX)), beatAt(Math.max(bandFromX, bandToX)),
                    pitchAt(Math.max(bandFromY, bandToY)), pitchAt(Math.min(bandFromY, bandToY))));
            drawNotes();
            return;
        }
        if (dragged == null) {
            return;
        }
        if (resizing) {
            change(NoteEdits.resize(dragFrom, dragged, beatAt(event.getX()), pages.lengthBeats()));
            // the length this one ends up with is the length the next note added starts at
            lastLength = Math.max(NoteEdits.SHORTEST, NoteEdits.snap(beatAt(event.getX())) - dragged.beat());
        } else {
            NoteEdits.Edit edit = NoteEdits.move(dragFrom, dragHeld, beatAt(event.getX()) - grabbedBeat,
                    pitchAt(event.getY()) - grabbedPitch, pages.lengthBeats());
            held = edit.touched();
            change(edit.notes());
        }
    }

    /** A band ends with what it gathered; a press on empty rows that never moved adds a note. */
    private void released(MouseEvent event) {
        if (pressedKey >= 0) {
            pressedKey = -1;
            drawPlayhead(shownX, true);
            return;
        }
        if (onEdit == null) {
            return;
        }
        if (pressedOnEmpty && !banding) {
            change(NoteEdits.add(shown, beatAt(bandFromX), pitchAt(bandFromY), lastLength, 0.8f,
                    pages.lengthBeats()));
            pressedOnEmpty = false;
            finish();
            return;
        }
        if (banding) {
            pressedOnEmpty = false;
            banding = false;
            drawNotes();
            return;
        }
        finish();
    }

    /** Delete takes away everything the hand is holding. */
    private void keyed(KeyEvent event) {
        if (onEdit == null) {
            return;
        }
        if ((event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) && !held.isEmpty()) {
            change(NoteEdits.remove(shown, held));
            hold(List.of());
            finish();
            event.consume();
        }
    }

    /** A press on a velocity bar takes hold of its note, or of everything held when it is one of them. */
    private void velocityPressed(MouseEvent event) {
        notes.requestFocus();
        if (onEdit == null) {
            return;
        }
        Note under = barAt(event.getX());
        if (under == null) {
            return;
        }
        if (!held.contains(under)) {
            hold(List.of(under));
        }
        dragFrom = shown;
        dragHeld = held;
        dragged = under;
        velocityFromY = event.getY();
        drawNotes();
    }

    private void velocityDragged(MouseEvent event) {
        if (dragged == null) {
            return;
        }
        NoteEdits.Edit edit = NoteEdits.velocity(dragFrom, dragHeld,
                (float) ((velocityFromY - event.getY()) / LANE_HEIGHT));
        held = edit.touched();
        change(edit.notes());
    }

    /** The end of a drag, or of an edit that took only a click: the line goes out as it now stands. */
    private void finish() {
        dragFrom = null;
        dragHeld = List.of();
        dragged = null;
        if (onEdit != null) {
            onEdit.accept(shown);
        }
    }

    /** What the hand is holding now: kept as the notes stand, so an edit can carry them over. */
    private void hold(List<Note> notes) {
        held = List.copyOf(notes);
    }

    /** The note whose velocity bar stands at {@code x}, or null. */
    private Note barAt(double x) {
        double from = pages.start(page);
        double to = from + pages.pageBeats();
        Note nearest = null;
        double best = BAR_WIDTH;
        for (Note note : shown) {
            if (note.beat() < from || note.beat() >= to) {
                continue;
            }
            double distance = Math.abs(KEYS_WIDTH + (note.beat() - from) * perBeat() + BAR_WIDTH / 2 - x);
            if (distance <= best) {
                best = distance;
                nearest = note;
            }
        }
        return nearest;
    }

    private void hovered(MouseEvent event) {
        if (event.getX() < KEYS_WIDTH) {
            notes.setCursor(Cursor.HAND);
            return;
        }
        if (onEdit == null) {
            notes.setCursor(Cursor.DEFAULT);
            return;
        }
        Note under = noteAt(event.getX(), event.getY());
        notes.setCursor(under == null ? Cursor.DEFAULT
                : event.getX() > rightEdgeOf(under) - EDGE ? Cursor.H_RESIZE : Cursor.OPEN_HAND);
    }

    /** The notes as an edit leaves them, drawn at once so the hand sees what it is doing. */
    private void change(List<Note> next) {
        shown = List.copyOf(next);
        drawNotes();
    }

    /** The note under the point, or null; the topmost when two lie over each other. */
    private Note noteAt(double x, double y) {
        double beat = pages.start(page) + (x - KEYS_WIDTH) / perBeat();
        int pitch = pitchAt(y);
        Note found = null;
        for (Note note : shown) {
            if (note.voice() instanceof Voice.Pitch sounded && sounded.midiNote() == pitch
                    && beat >= note.beat() && beat <= note.beat() + note.durationBeats()) {
                found = note;
            }
        }
        return found;
    }

    private double beatAt(double x) {
        return pages.start(page) + (x - KEYS_WIDTH) / perBeat();
    }

    private int pitchAt(double y) {
        return keys.low() + (int) Math.floor((notes.getHeight() - y) / row);
    }

    private double rightEdgeOf(Note note) {
        return KEYS_WIDTH + (note.beat() + note.durationBeats() - pages.start(page)) * perBeat();
    }

    private void drawPlayhead(int x, boolean anyway) {
        if (x == shownX && !anyway) {
            return;
        }
        shownX = x;
        double height = playhead.getHeight();
        GraphicsContext g = playhead.getGraphicsContext2D();
        g.clearRect(0, 0, playhead.getWidth(), height);
        for (int pitch : lit) {
            paintKey(g, pitch, height);
        }
        if (pressedKey >= 0) {
            paintKey(g, pressedKey, height);
        }
        if (x >= 0) {
            g.setStroke(PLAYHEAD);
            g.setLineWidth(2);
            g.strokeLine(x, 0, x, height);
        }
    }

    private void paintKey(GraphicsContext g, int pitch, double height) {
        if (pitch < keys.low() || pitch > keys.high()) {
            return;
        }
        double y = height - (pitch - keys.low() + 1) * row;
        g.setFill(isBlack(pitch) ? LIT_BLACK : LIT_WHITE);
        g.fillRect(0, y + 0.5, isBlack(pitch) ? KEYS_WIDTH - 14 : KEYS_WIDTH - 1, Math.max(1, row - 1));
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

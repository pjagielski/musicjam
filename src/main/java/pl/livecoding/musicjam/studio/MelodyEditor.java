package pl.livecoding.musicjam.studio;

import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Spinner;
import javafx.scene.Node;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tooltip;
import javafx.geometry.Pos;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;
import pl.livecoding.musicjam.midi.MidiFileReader;
import pl.livecoding.musicjam.model.Note;
import pl.livecoding.musicjam.studio.knobs.StudioPanels;

import javax.sound.midi.Sequence;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The editor of one melody track: its notes in a {@link PianoRoll}, and above them where they come
 * from. A track reading a window of a MIDI file shows which file, which of its tracks and the bar
 * the window opens on; how many bars it takes is the loop's length, set above for the whole jam. A
 * track whose notes are its own says where they came from, with a button to read the file again.
 *
 * <p>Either way the roll can be edited, and the first edit of a window makes its notes the track's
 * own, so a hand never changes what a file is read as.
 */
final class MelodyEditor {

    /** A MIDI file read once and kept, so picking it again does not read it again. */
    @FunctionalInterface
    interface Sequences {
        Sequence of(Path file) throws Exception;
    }

    private final StudioTrack.Melody track;
    private final List<Path> files;
    private final Sequences sequences;
    private final Consumer<MelodySource> onChange;
    private final Consumer<Exception> onError;
    private final Function<MelodySource, List<Note>> notes;
    private final double lengthBeats;
    private final int beatsPerBar;
    private final PianoRoll roll;
    private final ComboBox<Path> file = new ComboBox<>();
    private final ComboBox<Integer> trackIndex = new ComboBox<>();
    private final Spinner<Integer> startBar;
    private final Label summary = new Label();
    // the notes the track plays, as a file's window or as its own
    private MelodySource source;
    // the window it reads, or the one its own notes came from; null when they came from nowhere
    private MidiWindow window;
    private Sequence sequence;
    private boolean filling;
    // the row above the roll, whose controls change when the notes stop being a file's and become
    // the track's own; the roll itself stays, with the page it is on and the hand that is on it
    private final HBox controls = new HBox(8);
    private final Region push = new Region();
    private final HBox paging;
    private final VBox frame;
    private final Label heading;

    /**
     * {@code onChange} hears of every new source, {@code notes} gives one's notes over a loop of
     * {@code lengthBeats}, and the roll that shows them is {@code width} wide.
     */
    MelodyEditor(StudioTrack.Melody track, List<Path> files, Sequences sequences, Consumer<MelodySource> onChange,
                 Consumer<Exception> onError, Function<MelodySource, List<Note>> notes, double lengthBeats,
                 int beatsPerBar, double width) throws Exception {
        this.track = track;
        this.files = List.copyOf(files);
        this.sequences = sequences;
        this.onChange = onChange;
        this.onError = onError;
        this.notes = notes;
        this.lengthBeats = lengthBeats;
        this.beatsPerBar = beatsPerBar;
        this.roll = new PianoRoll(width, 260);
        this.source = track.source();
        this.window = track.window();
        this.sequence = window == null ? null : sequences.of(window.file());
        List<Path> choices = new ArrayList<>(files);
        if (window != null && !choices.contains(window.file())) {
            choices.add(window.file());
        }
        file.getItems().setAll(choices);
        file.setConverter(new StringConverter<>() {
            @Override
            public String toString(Path path) {
                return path == null ? "" : path.getFileName().toString();
            }

            @Override
            public Path fromString(String text) {
                return null;
            }
        });
        file.setValue(window == null ? null : window.file());
        startBar = new Spinner<>(1, window == null ? 1 : Math.max(1, barsIn(sequence)),
                window == null ? 1 : window.startBar() + 1);
        startBar.setPrefWidth(80);
        trackIndex.setCellFactory(list -> new TrackCell());
        trackIndex.setButtonCell(new TrackCell());
        if (window != null) {
            fillTracks();
        }
        roll.setOnEdit(this::edited);
        Tooltip.install(roll.node(), new Tooltip("""
                Click an empty row to add a note, drag one to move it,
                drag its right edge to change its length, right-click it to take it away.
                Drag over empty rows to gather notes, shift-click to add one to them,
                Delete to take them all away. The lane below carries their velocities:
                drag a bar up or down. Everything lands on the nearest sixteenth."""));

        summary.setStyle("-fx-text-fill: #868e96;");
        paging = pageButtons();
        HBox.setHgrow(push, Priority.ALWAYS);
        controls.setAlignment(Pos.CENTER_LEFT);
        updateSummary();
        frame = StudioPanels.frame("", controls, roll.node());
        // the frame's own heading, which says where the notes come from
        heading = (Label) frame.getChildren().getFirst();
        fillControls();

        file.setOnAction(event -> {
            if (!filling && file.getValue() != null && !file.getValue().equals(window.file())) {
                pickFile(file.getValue());
            }
        });
        trackIndex.setOnAction(event -> {
            if (!filling && trackIndex.getValue() != null) {
                change(window.withTrackIndex(trackIndex.getValue()));
            }
        });
        startBar.valueProperty().addListener((property, before, after) -> change(window.withStartBar(after - 1)));
    }

    /** The roll, whose playhead follows the jam. */
    PianoRoll roll() {
        return roll;
    }

    /** Where the notes come from in one row above the roll, so the roll gets the height. */
    VBox node() {
        return frame;
    }

    /** The row above the roll, as the notes come from a file's window or from the track itself. */
    private void fillControls() {
        controls.getChildren().setAll(source instanceof MidiWindow ? fileControls() : ownControls());
        controls.getChildren().addAll(gap(), summary, push, paging);
        heading.setText((source instanceof MidiWindow ? "Melody from a MIDI file"
                : "Melody · this track's own notes").toUpperCase(Locale.ROOT));
    }

    /** A window of a file: which file, which of its tracks, and the bar it opens on. */
    private List<Node> fileControls() {
        Button browse = new Button("Browse...");
        browse.setOnAction(event -> browse(browse));
        return List.of(new Label("File"), file, browse, gap(), new Label("Track"), trackIndex,
                gap(), new Label("From bar"), startBar);
    }

    /**
     * Notes of the track's own: where they came from, if anywhere, a button to read that window
     * again, and one to read any MIDI file into the track instead of what is written here.
     */
    private List<Node> ownControls() {
        Label from = new Label(window == null ? "Written here"
                : "Taken from " + window.file().getFileName() + ", track " + window.trackIndex()
                        + ", bar " + (window.startBar() + 1));
        from.setStyle("-fx-text-fill: #868e96;");
        Button browse = new Button(window == null ? "From a MIDI file..." : "Browse...");
        browse.setOnAction(event -> browse(browse));
        if (window == null) {
            return List.of(from, browse);
        }
        Button reload = new Button("Read the file again");
        reload.setOnAction(event -> change(window));
        return List.of(from, reload, browse);
    }

    /**
     * A hand has changed the notes. The first edit of a window makes them the track's own: what a
     * file is read as stays as it is, and from now on these notes are the track's.
     */
    private void edited(List<Note> edited) {
        boolean wasWindow = source instanceof MidiWindow;
        source = source instanceof MelodySource.OwnNotes own
                ? own.with(edited)
                : MelodySource.OwnNotes.takenFrom(window, edited);
        summarise();
        onChange.accept(source);
        if (wasWindow) {
            // the row above says where the notes come from, which has just changed
            fillControls();
        }
    }

    /**
     * Which four bars of a longer loop the roll is showing, and a button either side to turn to
     * the ones before or after. The playhead turns the page too; these are for looking ahead, or
     * back, while it is somewhere else or stopped. Out of the way for a loop of four bars or less.
     */
    private HBox pageButtons() {
        Button back = new Button("<");
        Button forward = new Button(">");
        Label shownBars = new Label();
        shownBars.setMinWidth(92);
        shownBars.setAlignment(Pos.CENTER);
        back.setOnAction(event -> roll.showPage(roll.page() - 1));
        forward.setOnAction(event -> roll.showPage(roll.page() + 1));
        HBox paging = new HBox(6, back, shownBars, forward);
        paging.setAlignment(Pos.CENTER_RIGHT);
        roll.setOnPage(page -> {
            PianoRoll.Pages pages = roll.pages();
            boolean several = pages.count() > 1;
            paging.setVisible(several);
            back.setDisable(page == 0);
            forward.setDisable(page == pages.count() - 1);
            int firstBar = (int) Math.round(pages.start(page) / beatsPerBar) + 1;
            int lastBar = (int) Math.ceil(Math.min(pages.lengthBeats(), pages.start(page) + pages.pageBeats())
                    / beatsPerBar - 1e-9);
            int bars = (int) Math.ceil(pages.lengthBeats() / beatsPerBar - 1e-9);
            shownBars.setText("Bars " + firstBar + (lastBar > firstBar ? "-" + lastBar : "") + " of " + bars);
        });
        return paging;
    }

    /** A little more room between one control and its label and the next. */
    private static Region gap() {
        Region gap = new Region();
        gap.setMinWidth(10);
        return gap;
    }

    private void browse(Button owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("A MIDI file for " + track.name());
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("MIDI files", "*.mid", "*.midi"));
        // this track's own file, or the jam's, for a track that has never read one
        Path near = window != null ? window.file() : files.isEmpty() ? null : files.getFirst();
        File parent = near == null ? null : near.toAbsolutePath().getParent().toFile();
        if (parent != null && parent.isDirectory()) {
            chooser.setInitialDirectory(parent);
        }
        File chosen = chooser.showOpenDialog(owner.getScene().getWindow());
        if (chosen != null) {
            pickFile(chosen.toPath().toAbsolutePath().normalize());
        }
    }

    /** Another file: its first track with notes, from its first bar. */
    private void pickFile(Path next) {
        Sequence read;
        try {
            read = sequences.of(next);
        } catch (Exception exception) {
            onError.accept(exception);
            filling = true;
            file.setValue(window.file());
            filling = false;
            return;
        }
        sequence = read;
        filling = true;
        if (!file.getItems().contains(next)) {
            file.getItems().add(next);
        }
        file.setValue(next);
        List<Integer> withNotes = tracksWithNotes(read);
        int first = withNotes.isEmpty() ? 0 : withNotes.getFirst();
        window = new MidiWindow(next, first, 0);
        fillTracks();
        ((SpinnerValueFactory.IntegerSpinnerValueFactory) startBar.getValueFactory())
                .setMax(Math.max(1, barsIn(read)));
        startBar.getValueFactory().setValue(1);
        filling = false;
        change(window);
    }

    /** Another window, by a picker or by reading the file again: the track reads it from now on. */
    private void change(MidiWindow next) {
        if (filling) {
            return;
        }
        boolean wasOwn = !(source instanceof MidiWindow);
        window = next;
        source = next;
        updateSummary();
        onChange.accept(next);
        if (wasOwn) {
            fillControls();
        }
    }

    private void fillTracks() {
        boolean wasFilling = filling;
        filling = true;
        List<Integer> withNotes = tracksWithNotes(sequence);
        if (!withNotes.contains(window.trackIndex())) {
            withNotes.add(window.trackIndex());
        }
        trackIndex.getItems().setAll(withNotes);
        trackIndex.setValue(window.trackIndex());
        filling = wasFilling;
    }

    /** The count of the notes, and the notes themselves in the roll. */
    private void updateSummary() {
        List<Note> shown = notes.apply(source);
        summarise(shown.size());
        roll.show(shown, lengthBeats, beatsPerBar);
    }

    /** The count alone, for an edit the roll has already drawn for itself. */
    private void summarise() {
        summarise(notes.apply(source).size());
    }

    private void summarise(int count) {
        // a track is as long as the loop, so the count says over how many bars the notes lie
        summary.setText((count == 0 ? "no notes" : count + (count == 1 ? " note" : " notes")) + " in "
                + barsLabel(lengthBeats / beatsPerBar));
    }

    /** "8 bars", "1 bar", "1/4 bar". */
    private static String barsLabel(double bars) {
        if (bars < 1) {
            return "1/" + Math.round(1 / bars) + " bar";
        }
        long whole = Math.round(bars);
        return whole + (whole == 1 ? " bar" : " bars");
    }

    /** The tracks that hold any notes at all: a file's first track is often only its tempo. */
    static List<Integer> tracksWithNotes(Sequence sequence) {
        List<Integer> indices = new ArrayList<>();
        for (int index = 0; index < sequence.getTracks().length; index++) {
            if (!MidiFileReader.readTrack(sequence, index).isEmpty()) {
                indices.add(index);
            }
        }
        return indices;
    }

    private static int barsIn(Sequence sequence) {
        return (int) Math.ceil(sequence.getTickLength() / (double) sequence.getResolution() / 4);
    }

    /** A track as its index and, when the file names it, its name. */
    private final class TrackCell extends ListCell<Integer> {
        @Override
        protected void updateItem(Integer index, boolean empty) {
            super.updateItem(index, empty);
            if (empty || index == null) {
                setText(null);
                return;
            }
            String name = index < sequence.getTracks().length ? MidiFileReader.trackName(sequence, index).trim() : "";
            setText(name.isEmpty() ? String.valueOf(index) : index + " · " + name);
        }
    }
}

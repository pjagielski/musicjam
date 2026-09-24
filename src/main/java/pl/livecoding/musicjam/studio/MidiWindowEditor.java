package pl.livecoding.musicjam.studio;

import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
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
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The editor of a melody track read from a MIDI file: which file, which of its tracks and the bar
 * the window opens on, and the window's notes in a {@link PianoRoll}. How many bars it takes is the
 * loop's length, set above for the whole jam.
 */
final class MidiWindowEditor {

    /** A MIDI file read once and kept, so picking it again does not read it again. */
    @FunctionalInterface
    interface Sequences {
        Sequence of(Path file) throws Exception;
    }

    private final StudioTrack.Melody track;
    private final Sequences sequences;
    private final Consumer<MidiWindow> onChange;
    private final Consumer<Exception> onError;
    private final Function<MidiWindow, List<Note>> notes;
    private final double lengthBeats;
    private final int beatsPerBar;
    private final PianoRoll roll;
    private final ComboBox<Path> file = new ComboBox<>();
    private final ComboBox<Integer> trackIndex = new ComboBox<>();
    private final Spinner<Integer> startBar;
    private final Label summary = new Label();
    private MidiWindow window;
    private Sequence sequence;
    private boolean filling;

    /**
     * {@code onChange} hears of every new window, {@code notes} gives one's notes over a loop of
     * {@code lengthBeats}, and the roll that shows them is {@code width} wide.
     */
    MidiWindowEditor(StudioTrack.Melody track, List<Path> files, Sequences sequences, Consumer<MidiWindow> onChange,
                     Consumer<Exception> onError, Function<MidiWindow, List<Note>> notes, double lengthBeats,
                     int beatsPerBar, double width) throws Exception {
        this.track = track;
        this.sequences = sequences;
        this.onChange = onChange;
        this.onError = onError;
        this.notes = notes;
        this.lengthBeats = lengthBeats;
        this.beatsPerBar = beatsPerBar;
        this.roll = new PianoRoll(width, 260);
        this.window = track.source();
        this.sequence = sequences.of(window.file());
        List<Path> choices = new ArrayList<>(files);
        if (!choices.contains(window.file())) {
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
        file.setValue(window.file());
        startBar = new Spinner<>(1, Math.max(1, barsIn(sequence)), window.startBar() + 1);
        startBar.setPrefWidth(80);
        trackIndex.setCellFactory(list -> new TrackCell());
        trackIndex.setButtonCell(new TrackCell());
        fillTracks();

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

    /** The window's controls in one row above the roll, so the roll gets the height. */
    VBox node() {
        Button browse = new Button("Browse...");
        browse.setOnAction(event -> browse(browse));
        summary.setStyle("-fx-text-fill: #868e96;");
        HBox paging = pageButtons();
        updateSummary();
        Region push = new Region();
        HBox.setHgrow(push, Priority.ALWAYS);
        HBox controls = new HBox(8, new Label("File"), file, browse, gap(), new Label("Track"), trackIndex,
                gap(), new Label("From bar"), startBar, gap(), summary, push, paging);
        controls.setAlignment(Pos.CENTER_LEFT);
        return StudioPanels.frame("Melody from a MIDI file", controls, roll.node());
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
        File parent = window.file().toAbsolutePath().getParent().toFile();
        if (parent.isDirectory()) {
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

    private void change(MidiWindow next) {
        if (filling) {
            return;
        }
        window = next;
        updateSummary();
        onChange.accept(next);
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

    /** The count of the window's notes, and the notes themselves in the roll. */
    private void updateSummary() {
        List<Note> shown = notes.apply(window);
        int count = shown.size();
        // the window is as long as the loop, so the count says over how many bars it is taken
        summary.setText((count == 0 ? "no notes" : count + (count == 1 ? " note" : " notes")) + " in "
                + barsLabel(lengthBeats / beatsPerBar));
        roll.show(shown, lengthBeats, beatsPerBar);
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

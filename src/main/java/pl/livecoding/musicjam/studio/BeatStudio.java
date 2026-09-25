package pl.livecoding.musicjam.studio;

import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.stage.Screen;
import pl.livecoding.musicjam.BeatApp;
import pl.livecoding.musicjam.PhraseRequest;
import pl.livecoding.musicjam.audio.AudioEngine;
import pl.livecoding.musicjam.audio.NoteListener;
import pl.livecoding.musicjam.audio.SampleBank;
import pl.livecoding.musicjam.livecode.LiveCode;
import pl.livecoding.musicjam.livecode.LiveCodeException;
import pl.livecoding.musicjam.midi.ExternalMidiOutput;
import pl.livecoding.musicjam.midi.MidiFileReader;
import pl.livecoding.musicjam.model.DrumTrack;
import pl.livecoding.musicjam.model.MelodyTrack;
import pl.livecoding.musicjam.model.Note;
import pl.livecoding.musicjam.model.Song;
import pl.livecoding.musicjam.model.Track;
import pl.livecoding.musicjam.studio.knobs.FxStrip;
import pl.livecoding.musicjam.studio.knobs.PanelKnob;
import pl.livecoding.musicjam.studio.knobs.StudioIcon;
import pl.livecoding.musicjam.studio.knobs.StudioPanels;
import pl.livecoding.musicjam.studio.knobs.SynthControls;
import pl.livecoding.musicjam.synth.PitchSynth;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * A window onto a live jam: its tracks, tempo and loop length can all be changed while it plays,
 * and each change is heard from the next loop on. A jam has one drum track, which is the grid, and
 * any number of melody tracks, each a window of a MIDI file as long as the loop; the track list
 * mutes, levels and orders them, and the editor below it shows the selected one. Live code (see
 * {@link LiveCode}) run with Ctrl+Enter is drawn into the grid, where clicks can tweak it until the
 * code runs again. Every {@code jam*.properties} next to the starting config is a preset: picking
 * one loads its MIDI file, tempo, drums and synth, with the one melody track it names. Each melody
 * is played by a synth of its own, or sent to an external MIDI synth on a channel of its own, whose
 * filter follows the track's knob as a control change (CC 74 unless told otherwise).
 *
 * <p>Starts from {@code --config <file>}, or {@code src/main/resources/jam.properties}, but with a
 * eight-bar loop and the drums drawn from the starter code rather than the config's own.
 */
public final class BeatStudio extends Application {
    private static final int BEATS_PER_BAR = 4;
    private static final Path DEFAULT_CONFIG = Path.of("src/main/resources/jam.properties");
    private static final List<String> LOOP_LENGTHS =
            List.of("1/16", "1/8", "1/4", "1/2", "1", "2", "4", "8", "16", "32");
    private static final String STARTING_LOOP = "8";
    // how long a key pressed on the roll sounds: a voice is made with its length, not let go of
    private static final double AUDITION_SECONDS = 0.7;
    private static final double CELL_HEIGHT = 28;
    private static final double CELL_GAP = 4;
    private static final double BAR_WIDTH = 16 * CELL_HEIGHT + 15 * CELL_GAP;
    private static final double COLUMN_WIDTH = BAR_WIDTH + 86;
    // the track editor spans the window, as the synth's four columns do below it
    private static final double FULL_WIDTH = COLUMN_WIDTH + 18 + 790;
    // the tracks and the effects split it down the middle, where the synth's oscillator and amp do
    private static final double PANEL_GAP = 14;
    private static final double HALF_WIDTH = (FULL_WIDTH - PANEL_GAP) / 2;
    private static final String STARTER_CODE = """
            $: stack(
                s("bd(3,8,5)"),
                s("[~ sd]*2").gain(1.25),
                s("hh*16").gain("[0.2 0.1]*8"),
                s("[~ oh ~ oh]*2").gain(0.45).rel(0.1).dec(0.2)
              )
            """;

    private final AtomicReference<AudioEngine.Jam> jam = new AtomicReference<>();
    private final Map<String, Path> jams = new TreeMap<>();
    private final List<GridRow> rows = new ArrayList<>();
    private final List<Button[]> cells = new ArrayList<>();
    private final VBox grid = new VBox(CELL_GAP);
    private final Button play = new Button("Play");
    private final ComboBox<String> presets = new ComboBox<>();
    private final Slider bpm = new Slider(40, 200, 120);
    private final Label bpmLabel = new Label();
    private final ObservableList<String> loopLengths = FXCollections.observableArrayList(LOOP_LENGTHS);
    private final Spinner<String> bars = new Spinner<>(new SpinnerValueFactory.ListSpinnerValueFactory<>(loopLengths));
    private final TextArea code = new TextArea(STARTER_CODE);
    private final Button runCode = new Button("Run (Ctrl+Enter)");
    private final Label codeError = new Label();
    private final VBox codeColumn = new VBox(8, code, row(runCode, codeError));
    private final HBox drumEditor = new HBox(18, grid, codeColumn);
    private final TrackPanel trackPanel = new TrackPanel(this::newMelody);
    // the selected track's editor: the grid and its code, or a melody's window
    private final VBox editor = new VBox();
    private final TextField device = new TextField();
    private final ToggleButton connect = new ToggleButton("Connect MIDI");
    private final Spinner<Integer> channel = new Spinner<>(1, 16, 1);
    private final Spinner<Integer> cc = new Spinner<>(0, 127, 74);
    private final PanelKnob filter = StudioPanels.knob("Filter", 0, 127, "", 0, 64);
    private final PanelKnob midiLatency =
            StudioPanels.knob("Latency", 0, 400, "ms", 0, PhraseRequest.DEFAULT_MIDI_LATENCY_MILLIS);
    // the synth spans the window: its groups in four columns, two flat rows
    private final SynthControls synthControls = SynthControls.light(4);
    // the selected track's instrument, beside the performance effects
    private final VBox instrument = new VBox(10);
    private final ToggleGroup instrumentView = new ToggleGroup();
    private final ToggleButton showSynth = new ToggleButton("Synth");
    private final ToggleButton showMidi = new ToggleButton("External MIDI");
    private VBox midiFrame;
    private final ProgressBar loopProgress = new ProgressBar(0);
    private final Label status = new Label("Stopped");

    private PhraseRequest request;
    private TrackList tracks;
    // the selected melody's roll, whose playhead follows the jam; none while the grid is shown
    private PianoRoll roll;
    private List<Path> midiFiles = List.of();
    // every MIDI file read so far, and every window's notes over the loop as long as it is now
    private final Map<Path, Sequence> sequences = new HashMap<>();
    private final Map<MidiWindow, List<Note>> windows = new HashMap<>();
    private double windowsLengthBeats;
    // the jam config's synth, for a track no instrument plays: the drum track's notes are samples
    private PitchSynth fallbackSynth;
    private AudioEngine engine;
    private AudioEngine.LiveSession session;
    private AudioEngine.LiveSession ringing;
    // a session that plays no loop, opened at the first key pressed while the jam is stopped
    private AudioEngine.LiveSession idle;
    // the Performance FX strips, all let go of when the jam stops
    private final List<FxStrip> strips = new ArrayList<>();
    // read by the thread that sends notes out as well as set here, so always the output as it is now
    private volatile ExternalMidiOutput midi;
    private boolean loading;
    // the MIDI controls being set to a track's values, rather than turned for it
    private boolean showingMidi;
    private double barFraction = -1;
    private static final double MIN_ZOOM = 0.5;
    private static final double MAX_ZOOM = 2.0;

    private double zoom = 1.0;
    private Runnable fitOnStart = () -> { };
    private boolean sizeWindowToContent;

    @Override
    public void start(Stage stage) throws Exception {
        Path config = configPath();
        engine = new AudioEngine(SampleBank.load(Path.of("samples"), AudioEngine.DEFAULT_SAMPLE_RATE));
        findJams(config);
        midiFiles = findMidiFiles(config);
        presets.getItems().setAll(jams.keySet());
        // the panel plays the selected melody's instrument, and only that one
        synthControls.setOnChange((params, effects) -> {
            if (tracks != null && tracks.selected() instanceof StudioTrack.Melody melody
                    && melody.instrument() != null) {
                melody.instrument().set(synthControls.setting());
            }
        });
        trackPanel.setOnEdit(this::publish);
        trackPanel.setOnSelect(this::showSelected);
        applyMidiLatency();

        loadJam(config);
        presets.setValue(nameOf(config));
        bars.getValueFactory().setValue(STARTING_LOOP);
        runCode();

        play.setOnAction(event -> togglePlayback());
        presets.setOnAction(event -> {
            try {
                loadJam(jams.get(presets.getValue()));
            } catch (Exception exception) {
                showError(exception);
            }
        });
        bpm.valueProperty().addListener((property, before, after) -> {
            updateBpmLabel();
            synthControls.setTempo(bpm.getValue());
            retimeDelays();
            publish();
        });
        bars.valueProperty().addListener((property, before, after) -> {
            publish();
            if (tracks.selected() instanceof StudioTrack.Melody) {
                // its count of notes is over the loop, which has just changed
                showEditor();
            }
        });
        code.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (!event.isShortcutDown()) {
                return;
            }
            if (event.getCode() == KeyCode.ENTER) {
                runCode();
                event.consume();
            } else if (event.getCode() == KeyCode.SLASH || event.getCode() == KeyCode.DIVIDE) {
                toggleComment();
                event.consume();
            }
        });
        runCode.setOnAction(event -> runCode());
        connect.setOnAction(event -> {
            if (connect.isSelected()) {
                openMidi();
            } else {
                closeMidi();
            }
        });
        filter.setOnChange(value -> midiControlsMoved());
        cc.valueProperty().addListener((property, before, after) -> midiControlsMoved());
        channel.valueProperty().addListener((property, before, after) -> midiControlsMoved());
        makeTypeable(cc);
        makeTypeable(channel);
        midiLatency.setOnChange(millis -> applyMidiLatency());

        boolean presentation = getParameters().getRaw().contains("--presentation");
        Scene scene = new Scene(windowLayout(stage));
        if (presentation) {
            scene.getRoot().setStyle("-fx-font-size: 18px;");
            code.setStyle("-fx-font-family: 'Consolas', 'Menlo', monospace; -fx-font-size: 18px;");
        }
        stage.setScene(scene);
        stage.setTitle("MusicJam Studio");
        stage.getIcons().setAll(StudioIcon.sizes());
        if (presentation || getParameters().getRaw().contains("--screen")) {
            Screen screen = selectedScreen();
            Rectangle2D bounds = screen.getVisualBounds();
            stage.setX(bounds.getMinX());
            stage.setY(bounds.getMinY());
            stage.setWidth(bounds.getWidth());
            stage.setHeight(bounds.getHeight());
        }
        stage.show();
        if (presentation) {
            stage.setFullScreen(true);
        }
        sizeWindowToContent = !presentation && !getParameters().getRaw().contains("--screen");
        // two pulses: one for the window's new size to be laid out, one to measure it
        Platform.runLater(() -> Platform.runLater(fitOnStart));
        playhead().start();
    }

    @Override
    public void stop() {
        stopPlayback();
        if (ringing != null) {
            ringing.close();
            ringing = null;
        }
        if (idle != null) {
            idle.close();
            idle = null;
        }
        closeMidi();
    }

    private Path configPath() {
        List<String> args = getParameters().getRaw();
        int index = args.indexOf("--config");
        return index >= 0 && index + 1 < args.size() ? Path.of(args.get(index + 1)) : DEFAULT_CONFIG;
    }

    private Screen selectedScreen() {
        List<String> args = getParameters().getRaw();
        int index = args.indexOf("--screen");
        if (index < 0) {
            return Screen.getPrimary();
        }
        if (index + 1 >= args.size()) {
            throw new IllegalArgumentException("--screen needs an index from 0 to " + (Screen.getScreens().size() - 1));
        }
        int number = Integer.parseInt(args.get(index + 1));
        if (number < 0 || number >= Screen.getScreens().size()) {
            throw new IllegalArgumentException("Screen " + number + " not found; choose 0 to "
                    + (Screen.getScreens().size() - 1));
        }
        return Screen.getScreens().get(number);
    }

    private BorderPane windowLayout(Stage stage) {
        VBox content = layout();
        ScrollPane scroll = new ScrollPane(new Group(content));
        scroll.setPannable(true);
        scroll.setPrefViewportWidth(1500);
        scroll.setPrefViewportHeight(780);

        Label zoomLabel = new Label("100%");
        Button smaller = new Button("−");
        smaller.setOnAction(event -> setZoom(content, zoomLabel, zoom - 0.15));
        Button larger = new Button("+");
        larger.setOnAction(event -> setZoom(content, zoomLabel, zoom + 0.15));
        Button normal = new Button("100%");
        normal.setOnAction(event -> setZoom(content, zoomLabel, 1.0));
        Button fit = new Button("Fit");
        fit.setOnAction(event -> fitToWindow(content, scroll, zoomLabel, MAX_ZOOM));
        // the first layout pass is what tells us how big the content is, so size and fit only after it
        fitOnStart = () -> {
            reserveRoomForEveryTrack(content);
            if (sizeWindowToContent) {
                setZoom(content, zoomLabel, sizeToContent(stage, content, scroll));
            } else {
                fitToWindow(content, scroll, zoomLabel, 1.0);
            }
        };
        Button nextScreen = new Button("Next screen");
        nextScreen.setDisable(Screen.getScreens().size() < 2);
        nextScreen.setOnAction(event -> moveToNextScreen(stage));
        Button fullScreen = new Button("Full screen");
        fullScreen.setOnAction(event -> stage.setFullScreen(!stage.isFullScreen()));

        HBox tools = row(new Label("Zoom"), smaller, zoomLabel, larger, normal, fit,
                nextScreen, fullScreen);
        HBox transport = row(play, new Label("Jam"), presets, new Label("Tempo"), bpm, bpmLabel,
                new Label("Loop (bars)"), bars, loopProgress, status);
        HBox.setHgrow(loopProgress, Priority.ALWAYS);

        // pinned above the scroll pane: what you reach for while it plays should not scroll away
        VBox pinned = new VBox(8, tools, transport);
        pinned.setPadding(new Insets(8, 12, 10, 12));
        pinned.setStyle("-fx-background-color: #f8f9fa; -fx-border-color: transparent transparent"
                + " #dee2e6 transparent; -fx-border-width: 0 0 1 0;");
        return new BorderPane(scroll, pinned, null, null, null);
    }

    /**
     * Scales the content so all of it shows without scrolling: shrinks it on a small screen, and
     * grows it up to {@code largest} on a big one — a projector, say.
     */
    private void fitToWindow(VBox content, ScrollPane scroll, Label label, double largest) {
        Bounds natural = content.getLayoutBounds();
        Bounds viewport = scroll.getViewportBounds();
        if (natural.getWidth() <= 0 || natural.getHeight() <= 0 || viewport.getWidth() <= 0) {
            return;
        }
        // a hair under the exact fit, or the scroll bars appear for the sake of a pixel
        double fitting = 0.99 * Math.min(viewport.getWidth() / natural.getWidth(),
                viewport.getHeight() / natural.getHeight());
        setZoom(content, label, Math.min(largest, fitting));
    }

    private void setZoom(VBox content, Label label, double requested) {
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, requested));
        content.getTransforms().setAll(new Scale(zoom, zoom, 0, 0));
        label.setText(Math.round(zoom * 100) + "%");
    }

    /**
     * The window just big enough for what it shows, and never bigger than the screen: whatever the
     * window adds around the content (its frame, the pinned toolbar, the scroll pane's edges) is
     * measured rather than guessed, as the difference between the window and its viewport.
     *
     * @return the zoom that makes the content fit the window: 1 when the screen had room for it,
     *     less when the screen cut the window short. Worked out here rather than measured after
     *     the resize, which the viewport only reports a layout pass or two later.
     */
    /**
     * Keeps the content as tall as its tallest track makes it, measured by showing each track's
     * editor and instrument in turn before the window is sized. The grid and its code are much
     * lower than a melody's roll and synth, so a window sized to the drum track, which is the one
     * selected at the start, would be too short for every other — and one resized at every click
     * would jump about.
     */
    private void reserveRoomForEveryTrack(VBox content) {
        int selected = tracks.selectedIndex();
        double tallest = 0;
        for (int index = 0; index < tracks.size(); index++) {
            tracks.select(index);
            showSelected();
            content.applyCss();
            content.layout();
            tallest = Math.max(tallest, content.prefHeight(-1));
        }
        tracks.select(selected);
        showSelected();
        content.setMinHeight(tallest);
        content.getParent().layout();
    }

    private static double sizeToContent(Stage stage, VBox content, ScrollPane scroll) {
        Bounds natural = content.getLayoutBounds();
        Bounds viewport = scroll.getViewportBounds();
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        double frameWidth = stage.getWidth() - viewport.getWidth() + 4;
        double frameHeight = stage.getHeight() - viewport.getHeight() + 4;
        double width = Math.min(screen.getWidth(), natural.getWidth() + frameWidth);
        double height = Math.min(screen.getHeight(), natural.getHeight() + frameHeight);
        stage.setWidth(width);
        stage.setHeight(height);
        stage.setX(screen.getMinX() + (screen.getWidth() - width) / 2);
        stage.setY(screen.getMinY() + (screen.getHeight() - height) / 2);
        boolean roomForAll = width == natural.getWidth() + frameWidth && height == natural.getHeight() + frameHeight;
        return roomForAll ? 1.0 : 0.99 * Math.min((width - frameWidth) / natural.getWidth(),
                (height - frameHeight) / natural.getHeight());
    }

    private static void moveToNextScreen(Stage stage) {
        List<Screen> screens = Screen.getScreens();
        List<Screen> current = Screen.getScreensForRectangle(
                stage.getX() + stage.getWidth() / 2, stage.getY() + stage.getHeight() / 2, 1, 1);
        int index = current.isEmpty() ? 0 : screens.indexOf(current.getFirst());
        Rectangle2D bounds = screens.get((index + 1) % screens.size()).getVisualBounds();
        boolean fullScreen = stage.isFullScreen();
        stage.setFullScreen(false);
        double width = Math.min(stage.getWidth(), bounds.getWidth());
        double height = Math.min(stage.getHeight(), bounds.getHeight());
        stage.setWidth(width);
        stage.setHeight(height);
        stage.setX(bounds.getMinX() + (bounds.getWidth() - width) / 2);
        stage.setY(bounds.getMinY() + (bounds.getHeight() - height) / 2);
        stage.setFullScreen(fullScreen);
    }

    private void findJams(Path config) throws IOException {
        try (Stream<Path> files = Files.list(config.toAbsolutePath().getParent())) {
            files.filter(file -> file.getFileName().toString().matches("jam(-.+)?\\.properties"))
                    .forEach(file -> jams.put(nameOf(file), file));
        }
        jams.putIfAbsent(nameOf(config), config);
    }

    /** The MIDI files next to the config, which a melody track can be read from without browsing. */
    private static List<Path> findMidiFiles(Path config) throws IOException {
        try (Stream<Path> files = Files.list(config.toAbsolutePath().getParent())) {
            return files.filter(file -> file.getFileName().toString().matches("(?i).+\\.midi?"))
                    .map(file -> file.toAbsolutePath().normalize())
                    .sorted()
                    .toList();
        }
    }

    private static String nameOf(Path config) {
        String name = config.getFileName().toString().replaceFirst("\\.properties$", "");
        return name.startsWith("jam-") ? name.substring("jam-".length()) : name;
    }

    /** Everything a jam config says: MIDI file and window, the file's tempo, drums and synth. */
    private void loadJam(Path config) throws Exception {
        PhraseRequest next = PhraseRequest.fromPropertiesFile(config);
        Path file = next.file().toAbsolutePath().normalize();
        Sequence nextSequence = sequenceOf(file);
        PitchSynth nextSynth = BeatApp.resolveSynth(next.synth());
        Instrument nextInstrument = Instrument.of(next.synth());
        nextInstrument.setMidi(next.midiChannelIndex(), 74, 64);
        List<DrumTrack> pattern = BeatApp.drumPatterns().get(next.drums().toLowerCase(Locale.ROOT));
        if (pattern == null) {
            throw new IllegalArgumentException("Unknown drums \"" + next.drums() + "\", expected one of "
                    + BeatApp.drumPatterns().keySet());
        }

        loading = true;
        try {
            request = next;
            midiLatency.setValue(next.midiLatencyMillis());
            tracks = TrackList.startingWith(trackName(nextSequence, next.trackIndex()),
                    new MidiWindow(file, next.trackIndex(), next.startBar()), nextInstrument);
            trackPanel.show(tracks);
            fallbackSynth = nextSynth;
            rows.clear();
            pattern.forEach(track -> rows.add(GridRow.fromTrack(track)));
            bpm.setValue(MidiFileReader.readTempo(nextSequence));
            updateBpmLabel();
            synthControls.setTempo(bpm.getValue());
            String length = loopLabel(next.bars());
            if (!loopLengths.contains(length)) {
                loopLengths.add(length);
            }
            bars.getValueFactory().setValue(length);
            if (next.midiDevice() != null && midi == null) {
                device.setText(next.midiDevice());
            }
            buildGrid();
            showSelected();
        } finally {
            loading = false;
        }
        publish();
    }

    /**
     * Every melody's delay locked to the beat, at the tempo as it is now: the one on the panel
     * follows it there, the others here, as their knobs are not on show.
     */
    private void retimeDelays() {
        for (StudioTrack track : tracks.tracks()) {
            if (track instanceof StudioTrack.Melody melody && melody.instrument() != null
                    && melody.instrument().setting() != null) {
                melody.instrument().set(melody.instrument().setting().at(bpm.getValue()));
            }
        }
    }

    private Sequence sequenceOf(Path file) throws Exception {
        Sequence known = sequences.get(file);
        if (known == null) {
            known = MidiSystem.getSequence(file.toFile());
            sequences.put(file, known);
        }
        return known;
    }

    /** A MIDI track by the name its file gives it, or by its number when the file gives none. */
    private static String trackName(Sequence sequence, int trackIndex) {
        String name = trackIndex < sequence.getTracks().length
                ? MidiFileReader.trackName(sequence, trackIndex).trim() : "";
        return name.isEmpty() ? "Track " + trackIndex : name;
    }

    /**
     * What "+ Melody" adds: a track of the first melody's file that no melody plays yet, or the
     * first with notes when they all are taken.
     */
    private StudioTrack.Melody newMelody() {
        StudioTrack.Melody first = tracks.firstMelody();
        // a synth of its own, from the patch the first melody started from, with its knobs as they come
        String patch = first != null && first.instrument() != null && first.instrument().setting() != null
                && first.instrument().setting().preset() != null
                ? first.instrument().setting().preset() : request.synth();
        Instrument instrument = Instrument.of(patch);
        instrument.setMidi(unusedChannel(), 74, 64);
        // an empty line to write in the roll; a MIDI file can be read into it from the editor
        return new StudioTrack.Melody(unusedName("Melody"), 1.0f, false,
                new MelodySource.OwnNotes(List.of(), null), instrument);
    }

    /**
     * The lowest MIDI channel no melody goes out on, so a new track can be sent out without two
     * sharing one; channel 10, which General MIDI keeps for drums, is left alone.
     */
    private int unusedChannel() {
        List<Integer> taken = tracks.tracks().stream()
                .filter(track -> track instanceof StudioTrack.Melody melody && melody.instrument() != null)
                .map(track -> ((StudioTrack.Melody) track).instrument().channel())
                .toList();
        for (int candidate = 0; candidate < 16; candidate++) {
            if (candidate != 9 && !taken.contains(candidate)) {
                return candidate;
            }
        }
        return 0;
    }

    /** {@code name}, or "name 2", "name 3" and on when a track already goes by it. */
    private String unusedName(String name) {
        List<String> taken = tracks.tracks().stream().map(StudioTrack::name).toList();
        String candidate = name;
        for (int number = 2; taken.contains(candidate); number++) {
            candidate = name + " " + number;
        }
        return candidate;
    }

    /** The selected track's editor and instrument in place of the last one's. */
    private void showSelected() {
        showEditor();
        showInstrument();
    }

    /** The selected track's editor in place of the last one's. */
    private void showEditor() {
        roll = null;
        switch (tracks.selected()) {
            case StudioTrack.Drums drums -> editor.getChildren().setAll(drumEditor);
            case StudioTrack.Melody melody -> {
                try {
                    double lengthBeats = loopBeats();
                    // the roll takes the frame's width, less the frame's own padding and edge
                    MelodyEditor melodyEditor = new MelodyEditor(melody, midiFiles, this::sequenceOf,
                            this::sourceChanged, BeatStudio::showError, next -> notesOf(next, lengthBeats),
                            lengthBeats, BEATS_PER_BAR, FULL_WIDTH - 30);
                    VBox frame = melodyEditor.node();
                    fitWidth(frame, FULL_WIDTH);
                    editor.getChildren().setAll(frame);
                    roll = melodyEditor.roll();
                    roll.setOnKey(this::audition);
                } catch (Exception exception) {
                    editor.getChildren().clear();
                    showError(exception);
                }
            }
        }
    }

    /**
     * A key pressed on the roll: the selected track's own instrument sounds it, with that track's
     * effects, for as long as {@link #AUDITION_SECONDS}. A track that goes out over MIDI has its
     * note sent instead, and ended after the same time, since nothing here holds it.
     */
    private void audition(int midiNote) {
        if (!(tracks.selected() instanceof StudioTrack.Melody melody) || melody.instrument() == null) {
            return;
        }
        Instrument played = melody.instrument();
        ExternalMidiOutput output = midi;
        if (played.external() && output != null) {
            output.noteOn(played.channel(), midiNote, 100);
            PauseTransition holding = new PauseTransition(Duration.seconds(AUDITION_SECONDS));
            holding.setOnFinished(done -> {
                ExternalMidiOutput still = midi;
                if (still != null) {
                    still.noteOff(played.channel(), midiNote);
                }
            });
            holding.play();
            return;
        }
        try {
            listening().audition(played.synth(), midiNote, AUDITION_SECONDS, 0.9f);
        } catch (Exception exception) {
            showError(exception);
        }
    }

    /**
     * What a key is heard through: the jam's own session while it plays, or one that plays no loop,
     * opened at the first key pressed and kept until the jam starts. Nothing holds the audio device
     * until someone asks to hear something.
     */
    private AudioEngine.LiveSession listening() throws Exception {
        if (session != null) {
            return session;
        }
        if (idle == null) {
            idle = engine.openIdle(jam::get);
        }
        return idle;
    }

    /** The editor always shows the selected track, so that is the one whose notes have changed. */
    private void sourceChanged(MelodySource next) {
        int index = tracks.selectedIndex();
        if (tracks.get(index) instanceof StudioTrack.Melody melody) {
            tracks.replace(index, melody.withSource(next));
            publish();
        }
    }

    /**
     * A melody's notes over a loop of {@code lengthBeats}: its own, or a window's read from its
     * file once for each loop length. Notes past the end of a shorter loop are kept but not played.
     */
    private List<Note> notesOf(MelodySource source, double lengthBeats) {
        if (source instanceof MelodySource.OwnNotes own) {
            return NoteEdits.within(own.notes(), lengthBeats);
        }
        if (lengthBeats != windowsLengthBeats) {
            windows.clear();
            windowsLengthBeats = lengthBeats;
        }
        return windows.computeIfAbsent((MidiWindow) source, key -> BeatApp.loadMelodyTrack(
                sequences.get(key.file()), key.trackIndex(), key.startBar(), lengthBeats / BEATS_PER_BAR).notes());
    }

    private double loopBeats() {
        return barsOf(bars.getValue()) * BEATS_PER_BAR;
    }

    /** Draws the code into the grid. Code that does not parse changes nothing: the error is shown instead. */
    private void runCode() {
        try {
            List<GridRow> fromCode = GridRow.fromCode(LiveCode.parse(code.getText()));
            codeError.setText("");
            rows.clear();
            rows.addAll(fromCode);
            buildGrid();
            publish();
        } catch (LiveCodeException exception) {
            codeError.setText(exception.describe(code.getText()));
        }
    }

    private void toggleComment() {
        var edit = CodeEditing.toggleComment(code.getText(), code.getSelection().getStart(), code.getSelection().getEnd());
        code.replaceText(edit.from(), edit.to(), edit.replacement());
        code.selectRange(edit.selectionStart(), edit.selectionEnd());
    }

    /**
     * Whether the jam holds edits the loop being heard does not play yet. Tempo, loop length and
     * the tracks' gains and mutes are not among them: the engine takes those up at once.
     */
    private boolean waiting(Song heard) {
        Song next = jam.get().song();
        return heard != next && !atFullGain(heard.tracks()).equals(atFullGain(next.tracks()));
    }

    /** The tracks as they would be with every gain up, so that only their notes are compared. */
    private static List<Track> atFullGain(List<Track> tracks) {
        return tracks.stream().map(track -> (Track) switch (track) {
            case DrumTrack drums -> new DrumTrack(drums.drum(), drums.steps(), 1.0f);
            case MelodyTrack melody -> new MelodyTrack(melody.notes(), melody.patternLengthBeats(), 1.0f);
        }).toList();
    }

    /**
     * Hands the engine a new jam. Its notes are picked up when the next loop starts, its tempo and
     * loop length at once.
     */
    private void publish() {
        if (loading || tracks == null) {
            return;
        }
        double lengthBeats = loopBeats();
        Song song = tracks.song(bpm.getValue(), BEATS_PER_BAR, lengthBeats,
                GridRow.notes(rows, BEATS_PER_BAR, lengthBeats), this::notesOf);
        // each melody by its own instrument; the drum track's notes are samples, whatever synth it is given
        List<PitchSynth> synths = tracks.tracks().stream()
                .map(track -> track instanceof StudioTrack.Melody melody && melody.instrument() != null
                        ? melody.instrument().synth() : fallbackSynth)
                .toList();
        // a track sent out while no device is connected is played here, rather than not at all
        boolean connected = midi != null;
        List<Integer> channels = tracks.tracks().stream()
                .map(track -> connected && track instanceof StudioTrack.Melody melody && melody.instrument() != null
                        && melody.instrument().external() ? melody.instrument().channel() : AudioEngine.Jam.HERE)
                .toList();
        jam.set(new AudioEngine.Jam(song, synths, channels));
    }

    private void buildGrid() {
        grid.getChildren().clear();
        cells.clear();
        for (int index = 0; index < rows.size(); index++) {
            GridRow row = rows.get(index);
            Label name = new Label(row.label());
            name.setMinWidth(76);
            int steps = row.accents().length;
            double width = (BAR_WIDTH - (steps - 1) * CELL_GAP) / steps;
            HBox line = new HBox(CELL_GAP, name);
            line.setAlignment(Pos.CENTER_LEFT);
            Button[] rowCells = new Button[steps];
            for (int step = 0; step < steps; step++) {
                int cellRow = index;
                int cellStep = step;
                Button cell = new Button();
                cell.setMinSize(width, CELL_HEIGHT);
                cell.setPrefSize(width, CELL_HEIGHT);
                cell.setMaxSize(width, CELL_HEIGHT);
                cell.setPadding(Insets.EMPTY);
                cell.setFocusTraversable(false);
                cell.setOnAction(event -> cycle(cellRow, cellStep));
                rowCells[step] = cell;
                line.getChildren().add(cell);
            }
            cells.add(rowCells);
            grid.getChildren().add(line);
        }
        paintGrid();
    }

    /** rest -> x -> X -> o -> rest; any other gain, as drawn from code, is taken out with one click. */
    private void cycle(int row, int step) {
        float current = rows.get(row).accents()[step];
        float next = current == 0.0f ? 0.8f : current == 0.8f ? 1.0f : current == 1.0f ? 0.5f : 0.0f;
        rows.set(row, rows.get(row).withAccent(step, next));
        paintGrid();
        publish();
    }

    private void paintGrid() {
        for (int index = 0; index < cells.size(); index++) {
            float[] accents = rows.get(index).accents();
            Button[] rowCells = cells.get(index);
            int steps = rowCells.length;
            int playing = barFraction < 0 ? -1 : (int) (barFraction * steps);
            int stepsPerBeat = steps % BEATS_PER_BAR == 0 ? steps / BEATS_PER_BAR : steps;
            for (int step = 0; step < steps; step++) {
                float accent = accents[step];
                String fill = accent > 0.0f ? heat(accent) : step % stepsPerBeat == 0 ? "#dee2e6" : "#f1f3f5";
                String edge = step == playing ? "#1971c2" : "#ced4da";
                rowCells[step].setText(rowCells[step].getPrefWidth() >= 24 ? accentLabel(accent) : "");
                rowCells[step].setStyle("-fx-background-color: " + edge + ", " + fill
                        + "; -fx-background-insets: 0, 3; -fx-background-radius: 4; -fx-font-weight: bold;");
            }
        }
    }

    private void togglePlayback() {
        if (session != null) {
            stopPlayback();
            return;
        }
        if (ringing != null) {
            // a tail from the last stop is still sounding; it makes way for the new jam
            ringing.close();
            ringing = null;
        }
        if (idle != null) {
            // the jam is about to take the audio device; keys go through its own session from now on
            idle.close();
            idle = null;
        }
        try {
            session = engine.playLive(jam::get, toMidi());
            applyMidiLatency();
            play.setText("Stop");
        } catch (Exception exception) {
            showError(exception);
        }
    }

    /** The jam stops, but its last notes, repeats and reverb are left to die away on their own. */
    private void stopPlayback() {
        // Stop ends a locked effect too, so the tail can die away and the next Play starts clean
        strips.forEach(FxStrip::release);
        if (session != null) {
            session.release();
            ringing = session;
            session = null;
        }
        play.setText("Play");
        barFraction = -1;
        if (roll != null) {
            roll.setPlayhead(-1);
        }
        loopProgress.setProgress(0);
        status.setText("Stopped");
        paintGrid();
    }

    /**
     * Where the session sends the tracks that go out: whatever device is connected when a note is
     * due, or nowhere. So connecting, disconnecting and sending a track out all happen while the jam
     * plays, without starting it again.
     */
    private NoteListener toMidi() {
        return new NoteListener() {
            @Override
            public void noteOn(int toChannel, int midiNote, int velocity) {
                ExternalMidiOutput output = midi;
                if (output != null) {
                    output.noteOn(toChannel, midiNote, velocity);
                }
            }

            @Override
            public void noteOff(int toChannel, int midiNote) {
                ExternalMidiOutput output = midi;
                if (output != null) {
                    output.noteOff(toChannel, midiNote);
                }
            }
        };
    }

    private void openMidi() {
        try {
            midi = ExternalMidiOutput.open(device.getText(), request.midiChannelIndex(), 0);
        } catch (Exception exception) {
            connect.setSelected(false);
            showError(exception);
            return;
        }
        // each track that goes out gets its sound and its filter on its own channel
        for (StudioTrack track : tracks.tracks()) {
            if (track instanceof StudioTrack.Melody melody && melody.instrument() != null
                    && melody.instrument().external()) {
                sendProgram(melody);
                sendFilter(melody.instrument());
            }
        }
        publish();
        showInstrument();
    }

    private void closeMidi() {
        ExternalMidiOutput output = midi;
        midi = null;
        if (output != null) {
            output.close();
        }
        connect.setSelected(false);
        publish();
        showInstrument();
    }

    /** The sound a track's own track in its MIDI file names, on the track's channel. */
    private void sendProgram(StudioTrack.Melody melody) {
        ExternalMidiOutput output = midi;
        if (output == null) {
            return;
        }
        MidiWindow window = melody.window();
        if (window == null) {
            return;
        }
        try {
            output.programChange(melody.instrument().channel(),
                    MidiFileReader.readProgram(sequenceOf(window.file()), window.trackIndex()));
        } catch (Exception exception) {
            showError(exception);
        }
    }

    private void sendFilter(Instrument played) {
        ExternalMidiOutput output = midi;
        if (output != null && played.external()) {
            output.controlChange(played.channel(), played.controller(), played.filter());
        }
    }

    /** The channel, CC or filter turned for the selected track: kept, and heard if it goes out. */
    private void midiControlsMoved() {
        if (showingMidi || tracks == null || !(tracks.selected() instanceof StudioTrack.Melody melody)
                || melody.instrument() == null) {
            return;
        }
        Instrument played = melody.instrument();
        int before = played.channel();
        played.setMidi(channel.getValue() - 1, cc.getValue(), (int) Math.round(filter.value()));
        if (played.channel() != before && played.external()) {
            sendProgram(melody);
            publish();
        }
        sendFilter(played);
    }

    /**
     * A JavaFX spinner is read-only unless asked otherwise, and even then it keeps its old value
     * when the typed text is simply left behind. This one takes digits, commits them on Enter and
     * on the way out, and puts the number back when what was typed is not one.
     */
    private static void makeTypeable(Spinner<Integer> spinner) {
        spinner.setEditable(true);
        spinner.getEditor().setOnAction(event -> commit(spinner));
        spinner.focusedProperty().addListener((property, before, after) -> {
            if (!after) {
                commit(spinner);
            }
        });
    }

    private static void commit(Spinner<Integer> spinner) {
        spinner.getValueFactory().setValue(controllerFrom(spinner.getEditor().getText(), spinner.getValue()));
        spinner.getEditor().setText(String.valueOf(spinner.getValue()));
    }

    /** What the editor's text means: a controller number, or {@code fallback} when it is not one. */
    static int controllerFrom(String text, int fallback) {
        try {
            return Math.max(0, Math.min(127, Integer.parseInt(text.trim())));
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    private void applyMidiLatency() {
        if (session != null) {
            session.setExternalLatencyMillis(midiLatency.value());
        }
    }

    private AnimationTimer playhead() {
        return new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (session == null) {
                    return;
                }
                var failure = session.failure();
                if (failure.isPresent()) {
                    stopPlayback();
                    showError(failure.get());
                    return;
                }
                AudioEngine.Position position = session.position();
                if (position == null) {
                    return;
                }
                loopProgress.setProgress(position.beat() / position.lengthBeats());
                if (roll != null) {
                    roll.setPlayhead(position.beat());
                }
                double fraction = (position.beat() % BEATS_PER_BAR) / BEATS_PER_BAR;
                boolean moved = (int) (fraction * 64) != (int) (barFraction * 64);
                barFraction = fraction;
                if (moved) {
                    paintGrid();
                }
                status.setText(String.format(Locale.ROOT, "Bar %d of %s, %.1f BPM%s",
                        (int) (position.beat() / BEATS_PER_BAR) + 1,
                        loopLabel(position.lengthBeats() / BEATS_PER_BAR),
                        position.bpm(),
                        waiting(position.song()) ? "  |  changes land on the next loop" : ""));
            }
        };
    }

    private VBox layout() {
        bars.setPrefWidth(90);
        cc.setPrefWidth(80);
        device.setPrefColumnCount(10);
        bpm.setPrefWidth(220);
        loopProgress.setMaxWidth(Double.MAX_VALUE);
        code.setPrefRowCount(7);
        code.setStyle("-fx-font-family: 'Consolas', 'Menlo', monospace; -fx-font-size: 14px;");
        codeError.setStyle("-fx-text-fill: #c92a2a;");

        // the grid keeps its own width, and the code takes the rest of the row beside it
        HBox.setHgrow(codeColumn, Priority.ALWAYS);
        VBox.setVgrow(code, Priority.ALWAYS);
        fitWidth(drumEditor, FULL_WIDTH);

        // the tracks and the performance effects side by side, both within reach while it plays
        VBox effects = performancePanel();
        fitWidth(effects, HALF_WIDTH);
        fitWidth(trackPanel.node(), HALF_WIDTH);
        HBox top = new HBox(PANEL_GAP, trackPanel.node(), effects);
        midiFrame = midiPanel();
        buildInstrument();
        fitWidth(instrument, FULL_WIDTH);

        VBox root = new VBox(14, top, editor, instrument);
        root.setPadding(new Insets(16));
        return root;
    }

    /**
     * The instrument panel's frame: which of the selected melody's two ways of sounding is shown,
     * the synth here or the external one it can be sent to. What it shows follows the selection.
     */
    private void buildInstrument() {
        for (ToggleButton view : List.of(showSynth, showMidi)) {
            view.setToggleGroup(instrumentView);
            view.setFocusTraversable(false);
            view.setPrefWidth(140);
            view.setOnAction(event -> {
                // a choice stays made when clicked again: the track always plays somewhere
                instrumentView.selectToggle(view);
                if (tracks.selected() instanceof StudioTrack.Melody melody && melody.instrument() != null) {
                    boolean out = view == showMidi;
                    if (melody.instrument().external() != out) {
                        melody.instrument().setExternal(out);
                        if (out) {
                            sendProgram(melody);
                            sendFilter(melody.instrument());
                        }
                        publish();
                    }
                }
                showInstrument();
            });
        }
        instrumentView.selectToggle(showSynth);
        showInstrument();
    }

    /**
     * The selected track's instrument. A melody is played either by its own synth, its knobs where
     * they were left for it, or by an external one on a channel of its own, and the switch in the
     * heading chooses which; the drum track plays samples, with nothing to set yet.
     */
    private void showInstrument() {
        if (midiFrame == null) {
            // the window is not laid out yet; it shows the instrument once it is
            return;
        }
        Label title = new Label("INSTRUMENT");
        title.setStyle("-fx-text-fill: #868e96; -fx-font-size: 10px; -fx-font-weight: bold;");
        if (tracks.selected() instanceof StudioTrack.Drums) {
            Label drums = new Label("The drum track plays the samples in samples/, one per row of the grid.");
            drums.setStyle("-fx-text-fill: #868e96;");
            instrument.getChildren().setAll(title, drums);
            return;
        }
        StudioTrack.Melody melody = (StudioTrack.Melody) tracks.selected();
        title.setText("INSTRUMENT · " + melody.name().toUpperCase(Locale.ROOT));
        Instrument played = melody.instrument();
        boolean playable = played != null && played.playable();
        if (playable) {
            synthControls.restore(played.setting());
        }
        synthControls.node().setDisable(!playable);
        synthControls.presetPicker().setDisable(!playable);
        boolean out = played != null && played.external();
        instrumentView.selectToggle(out ? showMidi : showSynth);
        if (played != null) {
            showingMidi = true;
            try {
                channel.getValueFactory().setValue(played.channel() + 1);
                cc.getValueFactory().setValue(played.controller());
                filter.setValue(played.filter());
            } finally {
                showingMidi = false;
            }
        }
        HBox header = new HBox(8, title, showSynth, showMidi);
        String outHint = !out ? ""
                : midi == null ? "Not connected: the track plays here until a MIDI device is."
                : "Sent on channel " + (played.channel() + 1) + "; the synth here stays quiet for it.";
        Label hint = new Label(out ? outHint
                : playable ? "" : "This synth has no knobs to turn: it plays as it is.");
        hint.setStyle("-fx-text-fill: #868e96; -fx-font-size: 11px;");
        if (showSynth.isSelected()) {
            header.getChildren().add(synthControls.presetPicker());
            HBox.setMargin(synthControls.presetPicker(), new Insets(0, 0, 0, 16));
        }
        header.getChildren().add(hint);
        HBox.setMargin(hint, new Insets(0, 0, 0, 16));
        header.setAlignment(Pos.CENTER_LEFT);
        Node shown = showMidi.isSelected() ? midiFrame : synthControls.node();
        instrument.getChildren().setAll(header, shown);
    }

    /**
     * The external synth a melody can be sent to instead. The device and how far ahead of the beat
     * notes go out are the jam's, one for every track; the channel, and the filter the synth
     * follows as a control change, are the selected track's own.
     */
    private VBox midiPanel() {
        device.setPromptText("MIDI device");
        channel.setPrefWidth(70);
        Label shared = new Label("Device and latency: every track's. Channel, CC and filter: this track's.");
        shared.setStyle("-fx-text-fill: #868e96; -fx-font-size: 11px;");
        return StudioPanels.frame("External MIDI",
                StudioPanels.row(device, connect, new Label("Channel"), channel, new Label("CC"), cc, shared),
                StudioPanels.knobRow(filter.node(), midiLatency.node()));
    }

    /**
     * A frame exactly {@code width} wide, so its edges line up with the frames above and below it
     * rather than ending wherever its contents happen to.
     */
    private static void fitWidth(javafx.scene.layout.Region box, double width) {
        box.setPrefWidth(width);
        box.setMinWidth(width);
        box.setMaxWidth(width);
    }

    /**
     * Effects played over the whole mix, a sampler's FX screen: a strip per effect, held to play.
     * Stutter's zones are its slice lengths, the shortest at the top — slide up for a faster roll.
     */
    private VBox performancePanel() {
        double[] slices = {0.125, 0.25, 0.5, 1.0};
        strips.add(FxStrip.zones("Stutter", List.of("1/32", "1/16", "1/8", "1/4"), zone -> {
            if (session != null) {
                session.stutter(zone < 0 ? 0 : slices[zone]);
            }
        }));
        strips.add(FxStrip.continuous("Crush", List.of("more", "less"), false, value -> {
            if (session != null) {
                session.performance().crush(value);
            }
        }));
        strips.add(FxStrip.continuous("Filter", List.of("HP", "LP"), true, value -> {
            if (session != null) {
                session.performance().filter(value);
            }
        }));
        strips.add(FxStrip.continuous("Talkbox", List.of("u", "o", "i", "e", "a"), false, value -> {
            if (session != null) {
                session.performance().talkbox(value);
            }
        }));
        strips.add(FxStrip.continuous("Dirty", List.of("more", "less"), false, value -> {
            if (session != null) {
                session.performance().dirty(value);
            }
        }));
        strips.add(FxStrip.continuous("Dub", List.of("away", "in"), false, value -> {
            if (session != null) {
                session.performance().dub(value);
            }
        }));
        Label hint = new Label("Hold a zone to play it and slide to change it; right-click to lock it on, right-click again to let go.");
        hint.setStyle("-fx-text-fill: #868e96; -fx-font-size: 11px;");
        hint.setWrapText(true);
        return StudioPanels.frame("Performance FX", row(strips.toArray(Node[]::new)), hint);
    }

    private void updateBpmLabel() {
        bpmLabel.setText(String.format(Locale.ROOT, "%.1f BPM", bpm.getValue()));
    }

    /** From a pale tint for the quietest hit to full orange for the loudest. */
    private static String heat(float accent) {
        double t = 0.15 + 0.85 * Math.max(0.0, Math.min(1.0, accent));
        return String.format(Locale.ROOT, "#%02x%02x%02x",
                Math.round(255 + (217 - 255) * t),
                Math.round(232 + (72 - 232) * t),
                Math.round(204 + (15 - 204) * t));
    }

    /** X, x and o for the grid's own accents; any other gain, as drawn from code, as a number like ".2". */
    private static String accentLabel(float accent) {
        if (accent == 0.0f) {
            return "";
        }
        if (accent == 1.0f) {
            return "X";
        }
        if (accent == 0.8f) {
            return "x";
        }
        if (accent == 0.5f) {
            return "o";
        }
        return String.format(Locale.ROOT, "%.2f", accent).replaceFirst("^0", "").replaceFirst("0$", "");
    }

    /** "1/4" -> 0.25, "2" -> 2.0 */
    private static double barsOf(String label) {
        int slash = label.indexOf('/');
        return slash < 0
                ? Double.parseDouble(label)
                : Double.parseDouble(label.substring(0, slash)) / Double.parseDouble(label.substring(slash + 1));
    }

    /** 2.0 -> "2", 0.25 -> "1/4" */
    private static String loopLabel(double bars) {
        return bars >= 1 && bars == Math.rint(bars) ? String.valueOf((long) bars) : "1/" + Math.round(1 / bars);
    }

    private static HBox row(Node... children) {
        HBox row = new HBox(10, children);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static void showError(Throwable failure) {
        Alert alert = new Alert(Alert.AlertType.ERROR, String.valueOf(failure.getMessage()));
        alert.setHeaderText(failure.getClass().getSimpleName());
        alert.show();
    }
}

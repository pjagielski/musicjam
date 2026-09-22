package pl.livecoding.musicjam.studio;

import javafx.animation.AnimationTimer;
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
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;
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
import pl.livecoding.musicjam.model.Song;
import pl.livecoding.musicjam.model.Track;
import pl.livecoding.musicjam.studio.knobs.FxStrip;
import pl.livecoding.musicjam.studio.knobs.PanelKnob;
import pl.livecoding.musicjam.studio.knobs.StudioIcon;
import pl.livecoding.musicjam.studio.knobs.StudioPanels;
import pl.livecoding.musicjam.studio.knobs.SynthControls;
import pl.livecoding.musicjam.synth.LiveNovasawSynth;
import pl.livecoding.musicjam.synth.NovasawSynth;
import pl.livecoding.musicjam.synth.PitchSynth;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * A window onto a live jam: the drum grid, tempo, loop length and melody can all be changed while
 * it plays, and each change is heard from the next loop on. Live code (see {@link LiveCode}) run
 * with Ctrl+Enter is drawn into the grid, where clicks can tweak it until the code runs again. Every
 * {@code jam*.properties} next to the starting config is a preset: picking one loads its MIDI file,
 * tempo, drums and synth. The melody can be routed to an external MIDI synth, whose filter follows
 * the slider as a control change (CC 74 unless told otherwise).
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
    private static final double CELL_HEIGHT = 28;
    private static final double CELL_GAP = 4;
    private static final double BAR_WIDTH = 16 * CELL_HEIGHT + 15 * CELL_GAP;
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
    private final CheckBox melodyOn = new CheckBox("Melody");
    private final PanelKnob melodyVolume = StudioPanels.knob("Volume", 0, 1, "", 2, 1);
    private final TextField device = new TextField();
    private final ToggleButton connect = new ToggleButton("Connect MIDI");
    private final CheckBox melodyToMidi = new CheckBox("Melody over MIDI");
    private final Spinner<Integer> cc = new Spinner<>(0, 127, 74);
    private final PanelKnob filter = StudioPanels.knob("Filter", 0, 127, "", 0, 64);
    private final PanelKnob midiLatency =
            StudioPanels.knob("Latency", 0, 400, "ms", 0, PhraseRequest.DEFAULT_MIDI_LATENCY_MILLIS);
    private final SynthControls synthControls = SynthControls.light(2);
    private final ProgressBar loopProgress = new ProgressBar(0);
    private final Label status = new Label("Stopped");

    private PhraseRequest request;
    private Sequence sequence;
    private PitchSynth synth;
    private LiveNovasawSynth liveSynth;
    private AudioEngine engine;
    private MelodyTrack melody;
    private AudioEngine.LiveSession session;
    private AudioEngine.LiveSession ringing;
    // the Performance FX strips, all let go of when the jam stops
    private final List<FxStrip> strips = new ArrayList<>();
    private ExternalMidiOutput midi;
    private boolean loading;
    private double barFraction = -1;
    private int sentController = -1;
    private int sentFilter = -1;
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
        presets.getItems().setAll(jams.keySet());
        synthControls.setOnChange((params, effects) -> {
            if (liveSynth != null) {
                liveSynth.setParams(params);
                liveSynth.setEffectParams(effects);
            }
        });
        melodyOn.setSelected(true);
        melodyToMidi.setDisable(true);
        applyMidiLatency();

        loadJam(config);
        presets.setValue(nameOf(config));
        bars.getValueFactory().setValue(STARTING_LOOP);
        reloadMelody();
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
            publish();
        });
        bars.valueProperty().addListener((property, before, after) -> reloadMelody());
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
        melodyOn.selectedProperty().addListener((property, before, after) -> publish());
        melodyVolume.setOnChange(volume -> publish());
        connect.setOnAction(event -> {
            if (connect.isSelected()) {
                openMidi();
            } else if (melodyToMidi.isSelected()) {
                reconfigure(this::closeMidi);
            } else {
                closeMidi();
            }
        });
        melodyToMidi.setOnAction(event -> reconfigure(() -> { }));
        filter.setOnChange(value -> sendFilter());
        cc.valueProperty().addListener((property, before, after) -> sendFilter());
        makeTypeable(cc);
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

    private static String nameOf(Path config) {
        String name = config.getFileName().toString().replaceFirst("\\.properties$", "");
        return name.startsWith("jam-") ? name.substring("jam-".length()) : name;
    }

    /** Everything a jam config says: MIDI file and window, the file's tempo, drums and synth. */
    private void loadJam(Path config) throws Exception {
        PhraseRequest next = PhraseRequest.fromPropertiesFile(config);
        Sequence nextSequence = MidiSystem.getSequence(next.file().toFile());
        PitchSynth nextSynth = BeatApp.resolveSynth(next.synth());
        List<DrumTrack> pattern = BeatApp.drumPatterns().get(next.drums().toLowerCase(Locale.ROOT));
        if (pattern == null) {
            throw new IllegalArgumentException("Unknown drums \"" + next.drums() + "\", expected one of "
                    + BeatApp.drumPatterns().keySet());
        }

        loading = true;
        try {
            request = next;
            midiLatency.setValue(next.midiLatencyMillis());
            sequence = nextSequence;
            setSynth(next.synth(), nextSynth);
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
            reloadMelody();
        } finally {
            loading = false;
        }
        publish();
    }

    /**
     * A ported patch becomes a {@link LiveNovasawSynth}, so the panel's knobs reach the notes that
     * are sounding; anything else plays as it always did, with the panel switched off.
     */
    private void setSynth(String name, PitchSynth next) {
        if (next instanceof NovasawSynth patch) {
            liveSynth = new LiveNovasawSynth(patch);
            synth = liveSynth;
            synthControls.node().setDisable(false);
            synthControls.selectPreset(name);
        } else {
            liveSynth = null;
            synth = next;
            synthControls.node().setDisable(true);
        }
    }

    private void reloadMelody() {
        melody = BeatApp.loadMelodyTrack(sequence, request.trackIndex(), request.startBar(), barsOf(bars.getValue()));
        publish();
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
     * Whether the jam holds edits the loop being heard does not play yet. Tempo and loop length are
     * not among them: the engine takes those up at once.
     */
    private boolean waiting(Song heard) {
        Song next = jam.get().song();
        return heard != next && !heard.tracks().equals(next.tracks());
    }

    /**
     * Hands the engine a new jam. Its notes are picked up when the next loop starts, its tempo and
     * loop length at once.
     */
    private void publish() {
        if (loading || melody == null) {
            return;
        }
        double lengthBeats = melody.patternLengthBeats();
        float gain = melodyOn.isSelected() ? (float) melodyVolume.value() : 0.0f;
        List<Track> tracks = List.of(
                new MelodyTrack(GridRow.notes(rows, BEATS_PER_BAR, lengthBeats), lengthBeats, 1.0f),
                new MelodyTrack(melody.notes(), lengthBeats, gain));
        jam.set(new AudioEngine.Jam(new Song(bpm.getValue(), BEATS_PER_BAR, tracks), synth));
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
        try {
            NoteListener externalMelody = melodyToMidi.isSelected() && midi != null ? BeatApp.melodyListener(midi) : null;
            session = engine.playLive(jam::get, externalMelody);
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
        loopProgress.setProgress(0);
        status.setText("Stopped");
        paintGrid();
    }

    /** Routing the melody is fixed for the life of a session, so changing it restarts playback. */
    private void reconfigure(Runnable change) {
        boolean wasPlaying = session != null;
        stopPlayback();
        change.run();
        if (wasPlaying) {
            togglePlayback();
        }
    }

    private void openMidi() {
        try {
            int program = MidiFileReader.readProgram(sequence, request.trackIndex());
            midi = ExternalMidiOutput.open(device.getText(), request.midiChannelIndex(), program);
            sentController = -1;
            sendFilter();
        } catch (Exception exception) {
            connect.setSelected(false);
            showError(exception);
        }
        melodyToMidi.setDisable(midi == null);
    }

    private void closeMidi() {
        if (midi != null) {
            midi.close();
            midi = null;
        }
        connect.setSelected(false);
        melodyToMidi.setSelected(false);
        melodyToMidi.setDisable(true);
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

    private void sendFilter() {
        int controller = cc.getValue();
        int value = (int) Math.round(filter.value());
        if (midi == null || (controller == sentController && value == sentFilter)) {
            return;
        }
        midi.controlChange(controller, value);
        sentController = controller;
        sentFilter = value;
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

        // the grid sets the left column's width; the code area follows it rather than the window
        code.setPrefWidth(BAR_WIDTH + 86);
        VBox jamColumn = new VBox(14,
                grid,
                code,
                row(runCode, codeError),
                melodyAndMidiPanels());

        HBox columns = new HBox(18, jamColumn, synthPanel());
        columns.setAlignment(Pos.TOP_LEFT);
        columns.setFillHeight(false);

        VBox root = new VBox(14, columns);
        root.setPadding(new Insets(16));
        return root;
    }

    /**
      * The melody and the external synth, framed like the knob panel so the window reads as one.
      * Both frames take the column's full width, so their edges line up with the grid and the code
      * above them rather than ending wherever their contents happen to.
      */
    private VBox melodyAndMidiPanels() {
        device.setPromptText("MIDI device");
        VBox frame = StudioPanels.frame("Melody and external MIDI",
                StudioPanels.row(melodyOn, device, connect, melodyToMidi, new Label("CC"), cc),
                StudioPanels.knobRow(melodyVolume.node(), filter.node(), midiLatency.node()));
        VBox effects = performancePanel();
        for (VBox box : List.of(frame, effects)) {
            box.setPrefWidth(BAR_WIDTH + 86);
            box.setMinWidth(BAR_WIDTH + 86);
            box.setMaxWidth(BAR_WIDTH + 86);
        }
        return new VBox(12, frame, effects);
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
        strips.add(FxStrip.continuous("Crush", List.of("less", "more"), false, value -> {
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
        Label hint = new Label("Hold a zone to play it and slide to change it; right-click to lock it on, right-click again to let go.");
        hint.setStyle("-fx-text-fill: #868e96; -fx-font-size: 11px;");
        hint.setWrapText(true);
        return StudioPanels.frame("Performance FX", row(strips.toArray(Node[]::new)), hint);
    }

    /** The synth's front panel, which folds away for anyone who only wants the grid. */
    private TitledPane synthPanel() {
        TitledPane pane = new TitledPane("Synth", synthControls.node());
        pane.setExpanded(true);
        return pane;
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

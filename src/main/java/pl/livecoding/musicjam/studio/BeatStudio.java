package pl.livecoding.musicjam.studio;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
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
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
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
    private static final double CELL_HEIGHT = 34;
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
    private final Button play = new Button("Graj");
    private final ComboBox<String> presets = new ComboBox<>();
    private final Slider bpm = new Slider(40, 200, 120);
    private final Label bpmLabel = new Label();
    private final ObservableList<String> loopLengths = FXCollections.observableArrayList(LOOP_LENGTHS);
    private final Spinner<String> bars = new Spinner<>(new SpinnerValueFactory.ListSpinnerValueFactory<>(loopLengths));
    private final TextArea code = new TextArea(STARTER_CODE);
    private final Button runCode = new Button("Uruchom (Ctrl+Enter)");
    private final Label codeError = new Label();
    private final CheckBox melodyOn = new CheckBox("Melodia");
    private final Slider melodyVolume = new Slider(0, 1, 1);
    private final TextField device = new TextField();
    private final ToggleButton connect = new ToggleButton("Połącz MIDI");
    private final CheckBox melodyToMidi = new CheckBox("Melodia przez MIDI");
    private final Spinner<Integer> cc = new Spinner<>(0, 127, 74);
    private final Slider filter = new Slider(0, 127, 64);
    private final Slider midiLatency = new Slider(0, 400, PhraseRequest.DEFAULT_MIDI_LATENCY_MILLIS);
    private final Label midiLatencyLabel = new Label();
    private final ProgressBar loopProgress = new ProgressBar(0);
    private final Label status = new Label("Zatrzymane");

    private PhraseRequest request;
    private Sequence sequence;
    private PitchSynth synth;
    private AudioEngine engine;
    private MelodyTrack melody;
    private AudioEngine.LiveSession session;
    private ExternalMidiOutput midi;
    private boolean loading;
    private double barFraction = -1;
    private int sentController = -1;
    private int sentFilter = -1;
    private double zoom = 1.0;

    @Override
    public void start(Stage stage) throws Exception {
        Path config = configPath();
        engine = new AudioEngine(SampleBank.load(Path.of("samples"), AudioEngine.DEFAULT_SAMPLE_RATE));
        findJams(config);
        presets.getItems().setAll(jams.keySet());
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
        melodyVolume.valueProperty().addListener((property, before, after) -> publish());
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
        filter.valueProperty().addListener((property, before, after) -> sendFilter());
        cc.valueProperty().addListener((property, before, after) -> sendFilter());
        midiLatency.valueProperty().addListener((property, before, after) -> applyMidiLatency());

        boolean presentation = getParameters().getRaw().contains("--presentation");
        Scene scene = new Scene(windowLayout(stage));
        if (presentation) {
            scene.getRoot().setStyle("-fx-font-size: 18px;");
            code.setStyle("-fx-font-family: 'Consolas', 'Menlo', monospace; -fx-font-size: 18px;");
        }
        stage.setScene(scene);
        stage.setTitle("MusicJam Studio");
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
        playhead().start();
    }

    @Override
    public void stop() {
        stopPlayback();
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
        scroll.setPrefViewportWidth(1100);
        scroll.setPrefViewportHeight(700);

        Label zoomLabel = new Label("100%");
        Button smaller = new Button("−");
        smaller.setOnAction(event -> setZoom(content, zoomLabel, zoom - 0.15));
        Button larger = new Button("+");
        larger.setOnAction(event -> setZoom(content, zoomLabel, zoom + 0.15));
        Button normal = new Button("100%");
        normal.setOnAction(event -> setZoom(content, zoomLabel, 1.0));
        Button nextScreen = new Button("Drugi ekran");
        nextScreen.setDisable(Screen.getScreens().size() < 2);
        nextScreen.setOnAction(event -> moveToNextScreen(stage));
        Button fullScreen = new Button("Pełny ekran");
        fullScreen.setOnAction(event -> stage.setFullScreen(!stage.isFullScreen()));

        HBox tools = row(new Label("Powiększenie"), smaller, zoomLabel, larger, normal,
                nextScreen, fullScreen);
        tools.setPadding(new Insets(8, 12, 8, 12));
        return new BorderPane(scroll, tools, null, null, null);
    }

    private void setZoom(VBox content, Label label, double requested) {
        zoom = Math.max(0.7, Math.min(2.0, requested));
        content.getTransforms().setAll(new Scale(zoom, zoom, 0, 0));
        label.setText(Math.round(zoom * 100) + "%");
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
            synth = nextSynth;
            rows.clear();
            pattern.forEach(track -> rows.add(GridRow.fromTrack(track)));
            bpm.setValue(MidiFileReader.readTempo(nextSequence));
            updateBpmLabel();
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

    /** Hands the engine a new jam; it picks it up when the next loop starts. */
    private void publish() {
        if (loading || melody == null) {
            return;
        }
        double lengthBeats = melody.patternLengthBeats();
        float gain = melodyOn.isSelected() ? (float) melodyVolume.getValue() : 0.0f;
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
            name.setMinWidth(90);
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
        try {
            NoteListener externalMelody = melodyToMidi.isSelected() && midi != null ? BeatApp.melodyListener(midi) : null;
            session = engine.playLive(jam::get, externalMelody);
            applyMidiLatency();
            play.setText("Stop");
        } catch (Exception exception) {
            showError(exception);
        }
    }

    private void stopPlayback() {
        if (session != null) {
            session.close();
            session = null;
        }
        play.setText("Graj");
        barFraction = -1;
        loopProgress.setProgress(0);
        status.setText("Zatrzymane");
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

    private void sendFilter() {
        int controller = cc.getValue();
        int value = (int) Math.round(filter.getValue());
        if (midi == null || (controller == sentController && value == sentFilter)) {
            return;
        }
        midi.controlChange(controller, value);
        sentController = controller;
        sentFilter = value;
    }

    private void applyMidiLatency() {
        midiLatencyLabel.setText(String.format(Locale.ROOT, "%.0f ms", midiLatency.getValue()));
        if (session != null) {
            session.setExternalLatencyMillis(midiLatency.getValue());
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
                status.setText(String.format(Locale.ROOT, "Takt %d z %s, %.1f BPM%s",
                        (int) (position.beat() / BEATS_PER_BAR) + 1,
                        loopLabel(position.lengthBeats() / BEATS_PER_BAR),
                        position.song().bpm(),
                        position.song() == jam.get().song() ? "" : "  |  zmiany wejdą od następnej pętli"));
            }
        };
    }

    private VBox layout() {
        bars.setPrefWidth(90);
        cc.setPrefWidth(80);
        device.setPrefColumnCount(10);
        bpm.setPrefWidth(220);
        melodyVolume.setPrefWidth(160);
        filter.setPrefWidth(220);
        midiLatency.setPrefWidth(160);
        loopProgress.setMaxWidth(Double.MAX_VALUE);
        code.setPrefRowCount(7);
        code.setStyle("-fx-font-family: 'Consolas', 'Menlo', monospace; -fx-font-size: 14px;");
        codeError.setStyle("-fx-text-fill: #c92a2a;");

        VBox root = new VBox(14,
                row(play, new Label("Jam"), presets, new Label("Tempo"), bpm, bpmLabel,
                        new Label("Pętla (takty)"), bars),
                grid,
                code,
                row(runCode, codeError),
                loopProgress,
                row(melodyOn, new Label("Głośność"), melodyVolume),
                row(new Label("Urządzenie MIDI"), device, connect, melodyToMidi,
                        new Label("CC"), cc, new Label("Filtr"), filter),
                row(new Label("Opóźnienie syntezatora"), midiLatency, midiLatencyLabel),
                status);
        root.setPadding(new Insets(16));
        return root;
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

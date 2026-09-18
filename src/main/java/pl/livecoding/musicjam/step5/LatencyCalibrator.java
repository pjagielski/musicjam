package pl.livecoding.musicjam.step5;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import pl.livecoding.musicjam.Config;
import pl.livecoding.musicjam.audio.AudioEngine;
import pl.livecoding.musicjam.audio.SampleBank;
import pl.livecoding.musicjam.midi.BeatClicks;
import pl.livecoding.musicjam.midi.MidiFileReader;
import pl.livecoding.musicjam.midi.TrackData;
import pl.livecoding.musicjam.model.Drum;

import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequencer;
import javax.sound.midi.Synthesizer;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Finds {@code midiLatency} by ear: the kick from AudioEngine and a note on the synth, both on every
 * beat, with the note sent as early as the slider says. Where the two sound as one is the latency to
 * put in the properties file. The note goes to the channel and program of the config's track, the
 * way playJam sends the melody.
 */
public class LatencyCalibrator extends Application {
    private static final double BPM = 100;
    private static final int MAX_LATENCY_MILLIS = 400;
    private static final String GERVILL = "Gervill (inside the JDK)";
    private static final Color TEXT = Color.web("#3b4048");

    private AudioEngine engine;
    private final AtomicInteger latencyMillis = new AtomicInteger();
    private final ComboBox<String> devices = new ComboBox<>();
    private final Slider latency = new Slider(0, MAX_LATENCY_MILLIS, 0);
    private final Label latencyLabel = new Label();
    private final TextField propertyLine = new TextField();
    private final Button startStop = new Button();
    private final Label status = new Label();
    private int channel;
    private int program;

    private volatile boolean playing;
    private BeatClicks clicks;
    private Thread drumThread;
    private Thread synthThread;

    @Override
    public void start(Stage stage) throws Exception {
        engine = new AudioEngine(SampleBank.load(Path.of("samples"), AudioEngine.DEFAULT_SAMPLE_RATE));
        Config config = Config.fromArgs(getParameters().getRaw().toArray(String[]::new));
        channel = config.midiChannel().isPresent() ? config.midiChannel().getAsInt() - 1 : 0;
        if (config.track().isPresent()) {
            TrackData track = MidiFileReader.read(config.file()).track(config.track().getAsInt());
            channel = config.channelFor(track);
            program = track.program().orElse(0);
        }
        devices.getItems().add(GERVILL);
        devices.getItems().addAll(externalDevices());
        devices.setValue(config.midiDevice()
                .flatMap(wanted -> devices.getItems().stream()
                        .filter(name -> name.toLowerCase(Locale.ROOT).contains(wanted.toLowerCase(Locale.ROOT)))
                        .findFirst())
                .orElse(GERVILL));
        latency.setValue(Math.min(config.midiLatency(), MAX_LATENCY_MILLIS));

        stage.setTitle("midiLatency: the drum and the synth as one");
        stage.setScene(new Scene(content()));
        stage.show();
    }

    private Parent content() {
        Label heading = new Label("How early does the synth need its notes?");
        heading.setFont(Font.font("System", FontWeight.BOLD, 16));
        heading.setTextFill(TEXT);
        Label hint = new Label("""
                On every beat the kick comes from AudioEngine and a note goes out over MIDI. \
                Move the slider until the two sound as one hit. Still two? Move it a few ms: \
                if they drift further apart, go the other way.""");
        hint.setWrapText(true);
        hint.setTextFill(TEXT);

        devices.setOnAction(event -> {
            if (playing) {
                stopPlaying();
                startPlaying();
            }
        });
        startStop.setOnAction(event -> {
            if (playing) {
                stopPlaying();
            } else {
                startPlaying();
            }
        });
        updateStartStop();
        HBox deviceRow = row(new Label("MIDI device"), devices, startStop);
        HBox.setHgrow(devices, Priority.ALWAYS);
        devices.setMaxWidth(Double.MAX_VALUE);

        latency.setShowTickLabels(true);
        latency.setShowTickMarks(true);
        latency.setMajorTickUnit(50);
        latency.setMinorTickCount(4);
        latency.setBlockIncrement(1);
        latency.valueProperty().addListener((property, before, after) -> showLatency());
        HBox.setHgrow(latency, Priority.ALWAYS);
        latencyLabel.setFont(Font.font("Monospaced", FontWeight.BOLD, 20));
        latencyLabel.setMinWidth(90);
        latencyLabel.setAlignment(Pos.CENTER_RIGHT);
        HBox latencyRow = row(nudge(-5), nudge(-1), latency, nudge(1), nudge(5), latencyLabel);
        showLatency();

        propertyLine.setEditable(false);
        propertyLine.setFont(Font.font("Monospaced", 14));
        HBox.setHgrow(propertyLine, Priority.ALWAYS);
        Button copy = new Button("Copy");
        copy.setOnAction(event -> {
            ClipboardContent line = new ClipboardContent();
            line.putString(propertyLine.getText());
            Clipboard.getSystemClipboard().setContent(line);
        });
        HBox propertyRow = row(new Label("For the .properties file"), propertyLine, copy);

        status.setTextFill(Color.web("#b3261e"));
        status.setWrapText(true);

        VBox box = new VBox(14, heading, hint, deviceRow, latencyRow, propertyRow, status);
        box.setPadding(new Insets(20));
        box.setPrefWidth(640);
        box.setStyle("-fx-background-color: white;");
        return box;
    }

    private static HBox row(Node... nodes) {
        HBox row = new HBox(8, nodes);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Button nudge(int millis) {
        Button button = new Button(millis > 0 ? "+" + millis : "−" + -millis);
        button.setOnAction(event -> latency.setValue(Math.round(latency.getValue()) + millis));
        return button;
    }

    private void showLatency() {
        int millis = (int) Math.round(latency.getValue());
        latencyMillis.set(millis);
        latencyLabel.setText(millis + " ms");
        propertyLine.setText("midiLatency=" + millis);
    }

    private void startPlaying() {
        status.setText("");
        try {
            String device = devices.getValue();
            clicks = GERVILL.equals(device)
                    ? BeatClicks.openMidi(channel, program)
                    : BeatClicks.openDevice(device, channel, program);
        } catch (Exception exception) {
            status.setText("Cannot open " + devices.getValue() + ": " + exception.getMessage());
            return;
        }
        playing = true;
        drumThread = Thread.ofPlatform().daemon().start(() -> {
            try {
                engine.playEveryBeat(Drum.KICK, BPM, () -> playing);
            } catch (Exception exception) {
                Platform.runLater(() -> {
                    status.setText("No sound card to play on: " + exception.getMessage());
                    stopPlaying();
                });
            }
        });
        synthThread = Thread.ofPlatform().daemon().start(() -> {
            try {
                // the beats are counted from the drum, so wait until it can be heard
                while (engine.heardNanos() == 0 && drumThread.isAlive()) {
                    Thread.sleep(1);
                }
                clicks.play(BPM, engine::heardNanos, () -> TimeUnit.MILLISECONDS.toNanos(latencyMillis.get()));
            } catch (InterruptedException stopped) {
                // how the clicks end
            }
        });
        updateStartStop();
    }

    private void stopPlaying() {
        if (!playing) {
            return;
        }
        playing = false;
        synthThread.interrupt();
        try {
            synthThread.join();
            drumThread.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        clicks.close();
        updateStartStop();
    }

    private void updateStartStop() {
        startStop.setText(playing ? "■ Stop" : "▶ Play");
    }

    /** What listMidiDevices shows as taking messages, but not the JDK's own sequencer and synthesizer. */
    private static List<String> externalDevices() throws Exception {
        var names = new LinkedHashSet<String>();
        for (MidiDevice.Info info : MidiSystem.getMidiDeviceInfo()) {
            MidiDevice device = MidiSystem.getMidiDevice(info);
            if (device.getMaxReceivers() != 0 && !(device instanceof Sequencer) && !(device instanceof Synthesizer)) {
                names.add(info.getName());
            }
        }
        return List.copyOf(names);
    }

    @Override
    public void stop() {
        stopPlaying();
    }
}

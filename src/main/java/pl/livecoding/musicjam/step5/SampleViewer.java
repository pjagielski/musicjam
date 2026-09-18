package pl.livecoding.musicjam.step5;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import pl.livecoding.musicjam.audio.AudioEngine;
import pl.livecoding.musicjam.audio.Sample;
import pl.livecoding.musicjam.audio.SampleBank;
import pl.livecoding.musicjam.audio.WavSampleLoader;
import pl.livecoding.musicjam.model.Drum;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Every drum twice, on one time scale: the sample read from its WAV file and the one computed by a
 * formula, each to look at and to play. To the renderer both are nothing but an array of floats.
 */
public class SampleViewer extends Application {
    private static final int RATE = AudioEngine.DEFAULT_SAMPLE_RATE;
    private static final double MILLIS_SHOWN = 450;
    private static final double WIDTH = 860;
    private static final double LANE_HEIGHT = 56;
    private static final Color FILE = Color.web("#2f6fd6");
    private static final Color FORMULA = Color.web("#e07a1f");
    private static final Color LANE = Color.web("#f3f4f6");
    private static final Color GRID = Color.web("#d9dce1");
    private static final Color TEXT = Color.web("#3b4048");

    @Override
    public void start(Stage stage) throws Exception {
        stage.setTitle("Samples: from a file and from a formula");
        stage.setScene(new Scene(content(Path.of("samples"))));
        stage.show();
    }

    static Parent content(Path directory) throws Exception {
        SampleBank formulas = SampleBank.synthesized(RATE);
        AudioEngine engine = new AudioEngine(formulas);
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(3);
        grid.setPadding(new Insets(16));
        grid.setStyle("-fx-background-color: white;");

        int row = 0;
        for (Drum drum : Drum.values()) {
            String fileName = SampleBank.fileName(drum);
            Path file = directory.resolve(fileName);
            boolean hasFile = Files.isRegularFile(file);
            Sample fromFile = hasFile ? WavSampleLoader.load(file, RATE) : null;
            Sample fromFormula = formulas.sample(drum);

            Label name = new Label(drum.name().toLowerCase(Locale.ROOT).replace('_', ' '));
            name.setFont(Font.font("System", FontWeight.BOLD, 14));
            name.setTextFill(TEXT);
            grid.add(name, 0, row, 1, 2);

            grid.add(describe("file", fromFile, hasFile ? fileFormat(file) : fileName + " is missing"), 1, row);
            grid.add(playButton(engine, fromFile), 2, row);
            grid.add(waveform(fromFile, FILE), 3, row);
            grid.add(describe("formula", fromFormula, "computed by DrumSamples"), 1, row + 1);
            grid.add(playButton(engine, fromFormula), 2, row + 1);
            grid.add(waveform(fromFormula, FORMULA), 3, row + 1);

            row += 2;
            if (drum.ordinal() < Drum.values().length - 1) {
                grid.add(new Canvas(WIDTH, 8), 3, row++);
            }
        }
        grid.add(timeAxis(), 3, row);
        return grid;
    }

    /** How long and how loud, and underneath, where the sample came from. */
    private static Label describe(String source, Sample sample, String origin) {
        String measured = sample == null
                ? String.format(Locale.ROOT, "%-8s -", source)
                : String.format(Locale.ROOT, "%-8s %4d ms, peak %.2f", source,
                        sample.frameCount() * 1000 / RATE, peak(sample.copyMono()));
        Label label = new Label(measured + "\n" + String.format(Locale.ROOT, "%-8s %s", "", origin));
        label.setFont(Font.font("Monospaced", 12));
        label.setTextFill(TEXT);
        label.setMinWidth(300);
        return label;
    }

    /** How the file itself stores the sound, before WavSampleLoader turns it into 44.1 kHz mono floats. */
    private static String fileFormat(Path file) throws Exception {
        AudioFormat format = AudioSystem.getAudioFileFormat(file.toFile()).getFormat();
        String channels = switch (format.getChannels()) {
            case 1 -> "mono";
            case 2 -> "stereo";
            default -> format.getChannels() + " channels";
        };
        return String.format(Locale.ROOT, "%s: %d Hz, %s, %d bit",
                file.getFileName(), Math.round(format.getSampleRate()), channels, format.getSampleSizeInBits());
    }

    private static Button playButton(AudioEngine engine, Sample sample) {
        Button play = new Button("▶");
        play.setDisable(sample == null);
        play.setOnAction(event -> Thread.ofVirtual().start(() -> play(engine, sample)));
        return play;
    }

    /** Through AudioEngine, the way the drums reach the sound card. */
    private static void play(AudioEngine engine, Sample sample) {
        try {
            engine.play(sample);
        } catch (LineUnavailableException exception) {
            System.err.println("No sound card to play on: " + exception.getMessage());
        }
    }

    /** One pixel column is a slice of time; the line spans the lowest and the highest value in it. */
    private static Canvas waveform(Sample sample, Color color) {
        Canvas canvas = new Canvas(WIDTH, LANE_HEIGHT);
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(LANE);
        g.fillRect(0, 0, WIDTH, LANE_HEIGHT);
        g.setStroke(GRID);
        for (int millis = 50; millis < MILLIS_SHOWN; millis += 50) {
            double x = millis / MILLIS_SHOWN * WIDTH;
            g.strokeLine(x, 0, x, LANE_HEIGHT);
        }
        double middle = LANE_HEIGHT / 2;
        g.strokeLine(0, middle, WIDTH, middle);
        if (sample == null) {
            g.setFill(TEXT);
            g.setTextBaseline(VPos.CENTER);
            g.fillText("no file - the formula plays instead", 12, middle);
            return canvas;
        }

        float[] values = sample.copyMono();
        double framesPerPixel = MILLIS_SHOWN / 1000 * RATE / WIDTH;
        g.setStroke(color);
        for (int x = 0; x < WIDTH; x++) {
            int from = (int) (x * framesPerPixel);
            int to = (int) Math.min(values.length, (x + 1) * framesPerPixel);
            if (from >= values.length) {
                break;
            }
            float low = 0.0f;
            float high = 0.0f;
            for (int frame = from; frame < to; frame++) {
                low = Math.min(low, values[frame]);
                high = Math.max(high, values[frame]);
            }
            double top = middle - Math.min(1.0, high) * (middle - 2);
            double bottom = middle - Math.max(-1.0, low) * (middle - 2);
            g.strokeLine(x + 0.5, top, x + 0.5, Math.max(bottom, top + 1));
        }
        return canvas;
    }

    private static Canvas timeAxis() {
        Canvas canvas = new Canvas(WIDTH, 16);
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(TEXT);
        g.setFont(Font.font("System", 11));
        g.setTextBaseline(VPos.TOP);
        for (int millis = 0; millis < MILLIS_SHOWN; millis += 50) {
            g.fillText(millis + " ms", millis / MILLIS_SHOWN * WIDTH + 2, 1);
        }
        return canvas;
    }

    private static float peak(float[] values) {
        float peak = 0.0f;
        for (float value : values) {
            peak = Math.max(peak, Math.abs(value));
        }
        return peak;
    }
}

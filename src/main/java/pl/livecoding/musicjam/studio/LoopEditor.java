package pl.livecoding.musicjam.studio;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;
import pl.livecoding.musicjam.audio.Sample;
import pl.livecoding.musicjam.audio.Slices;
import pl.livecoding.musicjam.studio.knobs.StudioPanels;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The editor of one loop track: the audio drawn as a waveform with the bars it fills over it and
 * the playhead moving across it, and above that which file it plays and how many bars it is taken
 * to be — with what those two together say about it, the tempo it must have been cut at and how much
 * faster or slower than that it is playing at the jam's tempo now.
 *
 * <p>The bars are the only thing to set, because they are the only thing the engine needs: nothing
 * in the file says how long a bar is, so the number here is what makes a break land on the beat.
 * Getting it wrong is audible at once — a loop taken for twice its length plays at half speed.
 */
final class LoopEditor {

    /** A WAV file read once and kept, so picking it again does not read it again. */
    @FunctionalInterface
    interface Loops {
        Sample of(Path file) throws Exception;
    }

    /**
     * A piece of the loop to be heard now, outside the jam: frames {@code from} up to {@code until},
     * read at {@code rate} frames a frame, which is the rate the loop itself is playing at.
     */
    @FunctionalInterface
    interface Tries {
        void play(Sample audio, int from, int until, double rate);
    }

    /** The lengths the box offers, whatever the file turned out to be taken as. */
    private static final double[] CHOICES = {0.25, 0.5, 1, 2, 4, 8, 16, 32};

    /** How a loop can be cut: no cutting, then a slice a beat and down from there. */
    private static final String OFF = "Off";
    private static final Map<String, Double> CUTS = new LinkedHashMap<>();

    static {
        CUTS.put(OFF, 0.0);
        CUTS.put("1/4", 1.0);
        CUTS.put("1/8", 0.5);
        CUTS.put("1/16", 0.25);
        CUTS.put("1/32", 0.125);
    }

    /** How far a boundary may move to find its hit: a fifth of a slice either way. */
    private static final double NEAR = 0.1;

    private final Loops loops;
    private final Consumer<StudioTrack.Loop> onChange;
    private final Consumer<Exception> onError;
    private final Tries tries;
    private final int sampleRate;
    private final int beatsPerBar;
    private double jamBpm;
    // how long the jam's own loop is, which is all of the pass that is ever reached
    private double jamLengthBeats;
    private final ComboBox<Double> bars = new ComboBox<>();
    private final ComboBox<String> cut = new ComboBox<>();
    private final ToggleButton toHits = new ToggleButton("To the hits");
    private final LoopWave wave;
    private final Label fileName = new Label();
    private final Label reading = new Label();
    private final Label details = new Label();
    private final VBox frame;
    private final HBox controls = new HBox(8);
    private StudioTrack.Loop track;
    private boolean filling;

    LoopEditor(StudioTrack.Loop track, Loops loops, Consumer<StudioTrack.Loop> onChange,
               Consumer<Exception> onError, Tries tries, int sampleRate, int beatsPerBar, double jamBpm,
               double jamLengthBeats, double width) {
        this.track = track;
        this.tries = tries;
        this.jamLengthBeats = jamLengthBeats;
        this.wave = new LoopWave(width, 150);
        this.loops = loops;
        this.onChange = onChange;
        this.onError = onError;
        this.sampleRate = sampleRate;
        this.beatsPerBar = beatsPerBar;
        this.jamBpm = jamBpm;

        bars.setPrefWidth(90);
        bars.setConverter(new StringConverter<>() {
            @Override
            public String toString(Double value) {
                return value == null ? "" : LoopBars.label(value);
            }

            @Override
            public Double fromString(String text) {
                return null;
            }
        });
        bars.setOnAction(event -> {
            if (!filling && bars.getValue() != null && bars.getValue() != this.track.bars()) {
                change(this.track.over(bars.getValue()));
            }
        });

        cut.getItems().setAll(CUTS.keySet());
        cut.setPrefWidth(90);
        cut.setOnAction(event -> {
            if (!filling && cut.getValue() != null) {
                change(this.track.cutInto(new StudioTrack.Slicing(CUTS.get(cut.getValue()), toHits.isSelected())));
            }
        });
        toHits.setOnAction(event -> {
            if (!filling) {
                change(this.track.cutInto(new StudioTrack.Slicing(this.track.slicing().beats(),
                        toHits.isSelected())));
            }
        });

        wave.setOnTry((from, until) -> tries.play(track.audio(), from, until, rate()));
        Tooltip.install(wave.node(), new Tooltip("""
                Click to hear a slice on its own, or the whole loop when it is not being cut.
                It sounds at the rate it plays at in the jam, whether the jam is running or not."""));

        Button browse = new Button("Browse...");
        browse.setOnAction(event -> browse(browse));
        fileName.setStyle("-fx-text-fill: #868e96;");
        reading.setStyle("-fx-text-fill: #868e96;");

        Region push = new Region();
        HBox.setHgrow(push, Priority.ALWAYS);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.getChildren().setAll(new Label("File"), fileName, browse, gap(),
                new Label("Fills"), bars, new Label("bars"), gap(),
                new Label("Slices"), cut, toHits, gap(), reading, push);

        frame = StudioPanels.frame("", controls, details());
        fill();
    }

    /** Where the audio comes from and how long it is taken to be, above what it says about itself. */
    VBox node() {
        return frame;
    }

    /** The jam's tempo has moved, and with it how fast this loop is being read. */
    void setTempo(double bpm) {
        jamBpm = bpm;
        details.setText(summary());
    }

    /**
     * Where the jam is, in beats of its own loop. A loop shorter than the jam's comes round more
     * than once inside it, so the playhead crosses the waveform once for every pass.
     */
    void setPlayhead(double beat) {
        if (beat < 0) {
            wave.setPlayhead(-1);
            return;
        }
        double pass = track.bars() * beatsPerBar;
        wave.setPlayhead(beat % pass / pass);
    }

    private Node details() {
        details.setStyle("-fx-text-fill: #495057; -fx-font-size: 12px;");
        VBox box = new VBox(8, wave.node(), details);
        box.setAlignment(Pos.TOP_LEFT);
        return box;
    }

    /** What the file is and what taking it for these bars amounts to, in full sentences. */
    private String summary() {
        Sample audio = track.audio();
        double seconds = audio.frameCount() / (double) sampleRate;
        double source = track.sourceBpm(sampleRate, beatsPerBar);
        double rate = jamBpm / source;
        String speed = Math.abs(rate - 1) < 0.005 ? "at the tempo it was cut at"
                : String.format(Locale.ROOT, "%.2f× %s than it was cut, so it sounds %s",
                        rate > 1 ? rate : 1 / rate, rate > 1 ? "faster" : "slower",
                        rate > 1 ? "higher" : "lower");
        double pass = track.bars() * beatsPerBar;
        String cut = jamLengthBeats <= 0 || jamLengthBeats >= pass ? ""
                : String.format(Locale.ROOT, "%nThe jam's loop is only %s bars, so it starts again at the"
                        + " shaded part: that much of the file is never reached.",
                        LoopBars.label(jamLengthBeats / beatsPerBar));
        return String.format(Locale.ROOT,
                "%.2f seconds of %s audio, taken to fill %s bars, which makes it %.1f BPM.%n"
                        + "The jam is at %.1f, so it plays %s.%n"
                        + "Change the bars if it does not land on the beat: a loop taken for twice its"
                        + " length plays at half speed.%s",
                seconds, audio.stereo() ? "stereo" : "mono", LoopBars.label(track.bars()), source, jamBpm, speed,
                cut);
    }

    /** Frames of the sample read for each frame played: what makes its bars the jam's bars. */
    private double rate() {
        double passFrames = track.bars() * beatsPerBar * 60.0 / jamBpm * sampleRate;
        return passFrames <= 0 ? 1 : track.audio().frameCount() / passFrames;
    }

    /** Where this loop's slices begin, or nothing at all when it is not being cut. */
    private int[] slices() {
        StudioTrack.Slicing slicing = track.slicing();
        int count = slicing.count(track.bars() * beatsPerBar);
        if (count <= 0) {
            return new int[0];
        }
        return slicing.toHits() ? Slices.onTheHits(track.audio(), count, NEAR)
                : Slices.onTheGrid(track.audio().frameCount(), count);
    }

    /** The name the box gives a slicing, which is the one it was chosen by. */
    private static String label(StudioTrack.Slicing slicing) {
        return CUTS.entrySet().stream()
                .filter(each -> each.getValue() == slicing.beats())
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(OFF);
    }

    private void browse(Button owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("A loop for " + track.name());
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Audio files", "*.wav", "*.aif",
                "*.aiff", "*.au"));
        File parent = track.file().toAbsolutePath().getParent().toFile();
        if (parent.isDirectory()) {
            chooser.setInitialDirectory(parent);
        }
        File chosen = chooser.showOpenDialog(owner.getScene().getWindow());
        if (chosen == null) {
            return;
        }
        try {
            Path file = chosen.toPath().toAbsolutePath().normalize();
            Sample audio = loops.of(file);
            double guess = LoopBars.guess(file, audio.frameCount(), sampleRate, beatsPerBar, jamBpm);
            change(new StudioTrack.Loop(track.name(), track.gain(), track.muted(), file, guess, audio));
        } catch (Exception exception) {
            onError.accept(exception);
        }
    }

    private void change(StudioTrack.Loop next) {
        track = next;
        fill();
        onChange.accept(next);
    }

    /** The controls as the track now stands; the box is not to answer while it is being filled. */
    private void fill() {
        filling = true;
        try {
            List<Double> choices = new ArrayList<>();
            for (double choice : CHOICES) {
                choices.add(choice);
            }
            if (!choices.contains(track.bars())) {
                choices.add(track.bars());
                choices.sort(Double::compare);
            }
            bars.getItems().setAll(choices);
            bars.setValue(track.bars());
            fileName.setText(track.file().getFileName().toString());
            reading.setText(String.format(Locale.ROOT, "%.1f BPM as cut", track.sourceBpm(sampleRate, beatsPerBar)));
            details.setText(summary());
            cut.setValue(label(track.slicing()));
            toHits.setSelected(track.slicing().toHits());
            toHits.setDisable(!track.slicing().on());
            wave.show(track.audio(), track.bars(), beatsPerBar, jamLengthBeats, slices());
            ((Label) frame.getChildren().getFirst()).setText("Loop · recorded audio".toUpperCase(Locale.ROOT));
        } finally {
            filling = false;
        }
    }

    private static Region gap() {
        Region gap = new Region();
        gap.setMinWidth(16);
        return gap;
    }
}

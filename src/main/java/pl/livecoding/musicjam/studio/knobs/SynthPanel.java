package pl.livecoding.musicjam.studio.knobs;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import pl.livecoding.musicjam.audio.AudioEngine;
import pl.livecoding.musicjam.audio.SampleBank;
import pl.livecoding.musicjam.model.Drum;
import pl.livecoding.musicjam.model.DrumTrack;
import pl.livecoding.musicjam.model.MelodyTrack;
import pl.livecoding.musicjam.model.Note;
import pl.livecoding.musicjam.model.Song;
import pl.livecoding.musicjam.model.Track;
import pl.livecoding.musicjam.model.Voice;
import pl.livecoding.musicjam.synth.AnthemLeadSynth;
import pl.livecoding.musicjam.synth.LiveNovasawSynth;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The synth panel on its own, with a loop under it: a window for working on the sound, and for
 * showing on a projector what the controls do. The panel itself is {@link SynthControls}, the same
 * node the studio embeds; everything around it here is transport — what plays while you turn knobs.
 *
 * <p>Every move is heard at once, in the note already sounding: pick the long note and sweep the
 * cutoff. The preset list belongs to the panel itself and loads the ported patches' parameters.
 *
 * <p>Arguments: {@code --light} to start on the light theme, {@code --snapshot <file.png>} to save
 * the panel and quit.
 */
public final class SynthPanel extends Application {

    private static final int BEATS_PER_BAR = 4;

    private final SynthControls controls = SynthControls.dark(2);
    private final List<Button> buttons = new ArrayList<>();
    private final Label title = new Label("MusicJam — synth panel");
    private final Label help = new Label("Drag a knob up or down, Shift for fine steps, or use the scroll wheel; "
            + "double-click for the starting value. Every change is heard at once, even mid-note.");
    private final Label patch = new Label();
    private final Label status = new Label("Stopped");
    private final Button themeButton = new Button();
    private final Button play = new Button("Play");
    private final Slider bpm = new Slider(60, 170, 120);
    private final Label bpmLabel = new Label();
    private final ComboBox<Phrase> phrase = new ComboBox<>();
    private final CheckBox drums = new CheckBox("Drums");
    private final VBox root = new VBox(14);

    private final LiveNovasawSynth synth = new LiveNovasawSynth(new AnthemLeadSynth());

    private AudioEngine engine;
    private AudioEngine.LiveSession session;
    private AudioEngine.LiveSession ringing;

    /** What plays under the knobs: something short and busy, or one note long enough to sweep. */
    private enum Phrase {
        ARPEGGIO("Arpeggio 1/16"), CHORDS("Chords"), LONG("Long note");

        private final String label;

        Phrase(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    @Override
    public void start(Stage stage) throws Exception {
        engine = new AudioEngine(SampleBank.load(Path.of("samples"), AudioEngine.DEFAULT_SAMPLE_RATE));
        controls.setOnChange((params, effects) -> {
            synth.setParams(params);
            synth.setEffectParams(effects);
            patch.setText(controls.describe());
        });

        phrase.getItems().setAll(Phrase.values());
        phrase.setValue(Phrase.ARPEGGIO);
        drums.setSelected(true);
        bpm.setPrefWidth(160);
        bpm.valueProperty().addListener((property, before, after) -> {
            updateBpmLabel();
            controls.setTempo(bpm.getValue());
        });
        updateBpmLabel();
        controls.setTempo(bpm.getValue());
        play.setOnAction(event -> togglePlayback());
        buttons.add(play);

        HBox transport = new HBox(10, play, new Label("Tempo"), bpm, bpmLabel,
                new Label("Phrase"), phrase, drums, status);
        transport.setAlignment(Pos.CENTER_LEFT);

        HBox presets = new HBox(8, button("Copy parameters", this::copyPatch), themeButton);
        presets.setAlignment(Pos.CENTER_LEFT);
        themeButton.setOnAction(event -> setTheme(controls.theme().other()));
        buttons.add(themeButton);

        patch.setWrapText(true);
        root.getChildren().addAll(title, transport, presets, controls.node(), help, patch);
        root.setPadding(new Insets(18));
        controls.selectPreset("anthem");
        setTheme(getParameters().getRaw().contains("--light") ? Theme.LIGHT : Theme.DARK);

        stage.setScene(new Scene(root));
        stage.setTitle("MusicJam — knobs");
        stage.getIcons().setAll(StudioIcon.sizes());
        stage.show();

        int snapshot = getParameters().getRaw().indexOf("--snapshot");
        if (snapshot >= 0) {
            Platform.runLater(() -> {
                writeSnapshot(root, getParameters().getRaw().get(snapshot + 1));
                Platform.exit();
            });
        }
    }

    @Override
    public void stop() {
        stopPlayback();
        closeRinging();
    }

    private void togglePlayback() {
        if (session != null) {
            stopPlayback();
            return;
        }
        if (ringing != null) {
            ringing.close();
            ringing = null;
        }
        try {
            session = engine.playLive(() -> new AudioEngine.Jam(song(), synth), null);
            play.setText("Stop");
            status.setText("Playing");
        } catch (Exception failure) {
            new Alert(Alert.AlertType.ERROR, String.valueOf(failure.getMessage())).show();
        }
    }

    /** The loop stops, but its repeats and reverb are left to ring out. */
    private void stopPlayback() {
        if (session != null) {
            session.release();
            ringing = session;
            session = null;
        }
        play.setText("Play");
        status.setText("Stopped");
    }

    private void closeRinging() {
        if (ringing != null) {
            ringing.close();
            ringing = null;
        }
    }

    /** One loop: the chosen phrase through the live synth, with drums under it when asked for. */
    private Song song() {
        List<Track> tracks = new ArrayList<>();
        tracks.add(new MelodyTrack(phraseNotes(phrase.getValue()), BEATS_PER_BAR, 1.0f));
        if (drums.isSelected()) {
            tracks.add(new DrumTrack(Drum.KICK, "X...X...X...X...", 0.5f));
            tracks.add(new DrumTrack(Drum.CLOSED_HAT, ".x.x.x.x.x.x.x.x", 0.2f));
        }
        return new Song(bpm.getValue(), BEATS_PER_BAR, tracks);
    }

    private static List<Note> phraseNotes(Phrase chosen) {
        List<Note> notes = new ArrayList<>();
        switch (chosen) {
            case ARPEGGIO -> {
                int[] pitches = {57, 60, 64, 67, 72, 67, 64, 60};
                for (int step = 0; step < 16; step++) {
                    notes.add(new Note(step * 0.25, new Voice.Pitch(pitches[step % pitches.length]),
                            0.22, 0.85f));
                }
            }
            case CHORDS -> {
                for (int pitch : new int[] {57, 60, 64}) {
                    notes.add(new Note(0, new Voice.Pitch(pitch), 1.9, 0.7f));
                }
                for (int pitch : new int[] {53, 57, 60}) {
                    notes.add(new Note(2, new Voice.Pitch(pitch), 1.9, 0.7f));
                }
            }
            case LONG -> notes.add(new Note(0, new Voice.Pitch(57), 3.8, 0.85f));
        }
        return notes;
    }

    private void updateBpmLabel() {
        bpmLabel.setText(String.format(Locale.ROOT, "%.0f BPM", bpm.getValue()));
    }

    /** One palette reaches the panel, the text around it and the buttons. */
    private void setTheme(Theme next) {
        controls.setTheme(next);
        themeButton.setText("Theme: " + next.name());
        root.setStyle("-fx-background-color: " + Theme.web(next.background()) + ";");
        title.setStyle("-fx-text-fill: " + Theme.web(next.text())
                + "; -fx-font-size: 15px; -fx-font-weight: bold;");
        String muted = "-fx-text-fill: " + Theme.web(next.mutedText()) + "; -fx-font-size: 11px;";
        help.setStyle(muted);
        patch.setStyle(muted + " -fx-font-family: 'Consolas', 'Menlo', monospace;");
        status.setStyle(muted);
        bpmLabel.setStyle(muted);
        buttons.forEach(button -> button.setStyle("-fx-background-color: "
                + Theme.web(button == themeButton || button == play ? next.accentButton() : next.buttonFace())
                + "; -fx-text-fill: " + Theme.web(button == themeButton || button == play
                        ? Color.WHITE : next.buttonText())
                + "; -fx-background-radius: 6; -fx-padding: 5 14 5 14;"));
        // the plain labels between the controls: tempo, phrase, the checkbox
        root.lookupAll(".label").forEach(node -> {
            if (node instanceof Label label && label.getStyle().isEmpty()) {
                label.setTextFill(next.mutedText());
            }
        });
        drums.setTextFill(next.text());
        phrase.setStyle("-fx-background-color: " + Theme.web(next.buttonFace()) + "; -fx-background-radius: 6;");
        phrase.setButtonCell(SynthControls.themedCell(next));
        phrase.setCellFactory(list -> SynthControls.themedCell(next));
    }

    private Button button(String label, Runnable action) {
        Button button = new Button(label);
        button.setOnAction(event -> action.run());
        buttons.add(button);
        return button;
    }

    private void copyPatch() {
        ClipboardContent content = new ClipboardContent();
        content.putString(controls.describe());
        Clipboard.getSystemClipboard().setContent(content);
    }

    /** Saves the panel as a PNG, so the layout can be looked at without opening the window. */
    private static void writeSnapshot(Node panel, String file) {
        WritableImage image = panel.snapshot(null, null);
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        BufferedImage png = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        PixelReader pixels = image.getPixelReader();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                png.setRGB(x, y, pixels.getArgb(x, y));
            }
        }
        try {
            ImageIO.write(png, "png", new File(file));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}

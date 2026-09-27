package pl.livecoding.musicjam.studio;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import pl.livecoding.musicjam.studio.knobs.NoPanning;
import pl.livecoding.musicjam.studio.knobs.StudioPanels;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The jam's tracks, one row each: what kind of track it is, its name, a mute and a gain. A click on
 * a row selects it, and the selected track is the one the editor below shows. Under the rows, a
 * melody track can be added, and the selected one removed or moved.
 */
final class TrackPanel {
    private static final String SELECTED = "-fx-background-color: #e7f5ff; -fx-border-color: #1971c2;";
    private static final String UNSELECTED = "-fx-background-color: transparent; -fx-border-color: #dee2e6;";

    private final VBox rows = new VBox(4);
    private final List<HBox> rowNodes = new ArrayList<>();
    private final Button addMelody = new Button("+ Melody");
    private final Button addLoop = new Button("+ Loop");
    private final Button duplicate = new Button("Duplicate");
    private final Button remove = new Button("Remove");
    private final Button up = new Button("Up");
    private final Button down = new Button("Down");
    private final VBox node;
    private final Supplier<StudioTrack.Melody> newMelody;

    private TrackList tracks;
    private Runnable onEdit = () -> { };
    private Runnable onSelect = () -> { };
    // called before a track is added, removed or moved, so the studio can keep what is about to change
    private Runnable onStructural = () -> { };

    /**
     * {@code newMelody} is what "+ Melody" adds: a track with a window already chosen. {@code
     * newLoop} is what "+ Loop" adds, or null when nothing was chosen to play. {@code copyOf} is
     * what "Duplicate" makes of the selected track, or null for one that cannot be copied.
     */
    TrackPanel(Supplier<StudioTrack.Melody> newMelody, Supplier<StudioTrack.Loop> newLoop,
               UnaryOperator<StudioTrack> copyOf) {
        this.newMelody = newMelody;
        duplicate.setOnAction(event -> {
            StudioTrack copy = copyOf.apply(tracks.selected());
            if (copy == null) {
                return;
            }
            onStructural.run();
            // just below the one it came from, and selected, so the hand is already on the copy
            tracks.insertAfter(tracks.selectedIndex(), copy);
            rebuild();
            onEdit.run();
            onSelect.run();
        });
        addLoop.setOnAction(event -> {
            StudioTrack.Loop loop = newLoop.get();
            if (loop == null) {
                return;
            }
            onStructural.run();
            tracks.add(loop);
            rebuild();
            onEdit.run();
            onSelect.run();
        });
        addMelody.setOnAction(event -> {
            onStructural.run();
            tracks.add(this.newMelody.get());
            rebuild();
            onEdit.run();
            onSelect.run();
        });
        remove.setOnAction(event -> {
            onStructural.run();
            tracks.remove(tracks.selectedIndex());
            rebuild();
            onEdit.run();
            onSelect.run();
        });
        up.setOnAction(event -> move(-1));
        down.setOnAction(event -> move(1));
        node = StudioPanels.frame("Tracks", rows,
                StudioPanels.row(addMelody, addLoop, duplicate, remove, up, down));
    }

    VBox node() {
        return node;
    }

    /** What changes the jam: a gain, a mute, a track added, removed or moved. */
    void setOnEdit(Runnable action) {
        onEdit = action;
    }

    /** Another track selected, whose editor is to be shown. */
    void setOnSelect(Runnable action) {
        onSelect = action;
    }

    /**
     * What a track being added, removed or moved is announced to before it happens: a gain or a
     * mute is a performance and is not, so playing the faders never fills an undo history.
     */
    void setOnStructural(Runnable action) {
        onStructural = action;
    }

    void show(TrackList next) {
        tracks = next;
        rebuild();
    }

    private void move(int by) {
        onStructural.run();
        tracks.move(tracks.selectedIndex(), by);
        rebuild();
        onEdit.run();
    }

    private void rebuild() {
        rows.getChildren().clear();
        rowNodes.clear();
        for (int index = 0; index < tracks.size(); index++) {
            HBox row = row(index, tracks.get(index));
            rowNodes.add(row);
            rows.getChildren().add(row);
        }
        paintSelection();
    }

    private HBox row(int index, StudioTrack track) {
        Label kind = new Label(switch (track) {
            case StudioTrack.Drums ignored -> "DRUMS";
            case StudioTrack.Melody ignored -> "MELODY";
            case StudioTrack.Loop ignored -> "LOOP";
        });
        kind.setMinWidth(58);
        kind.setAlignment(Pos.CENTER);
        kind.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: white; -fx-padding: 2 6;"
                + " -fx-background-radius: 4; -fx-background-color: " + switch (track) {
                    case StudioTrack.Drums ignored -> "#e8590c";
                    case StudioTrack.Melody ignored -> "#1c7ed6";
                    case StudioTrack.Loop ignored -> "#2f9e44";
                } + ";");

        TextField name = new TextField(track.name());
        name.setPrefColumnCount(12);
        // taken by a click, not by the window opening: typing is not meant to rename a track by chance
        name.setFocusTraversable(false);
        // a name is only the panel's: the jam sounds the same, so nothing is handed to the engine
        name.textProperty().addListener((property, before, after) ->
                tracks.replace(index, tracks.get(index).named(after)));

        ToggleButton mute = new ToggleButton("M");
        mute.setSelected(track.muted());
        mute.setFocusTraversable(false);
        mute.setOnAction(event -> {
            tracks.replace(index, tracks.get(index).withMuted(mute.isSelected()));
            onEdit.run();
        });

        Slider gain = new Slider(0, 1, track.gain());
        gain.setPrefWidth(150);
        NoPanning.on(gain);
        Label gainLabel = new Label(percent(track.gain()));
        gainLabel.setMinWidth(40);
        gain.valueProperty().addListener((property, before, after) -> {
            gainLabel.setText(percent(after.floatValue()));
            tracks.replace(index, tracks.get(index).withGain(after.floatValue()));
            onEdit.run();
        });

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox row = new HBox(8, kind, name, gap, mute, gain, gainLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4, 8, 4, 6));
        // pressed rather than clicked, and let through, so the name or the slider still gets it
        row.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> select(index));
        return row;
    }

    private void select(int index) {
        if (index == tracks.selectedIndex()) {
            return;
        }
        tracks.select(index);
        paintSelection();
        onSelect.run();
    }

    private void paintSelection() {
        for (int index = 0; index < rowNodes.size(); index++) {
            rowNodes.get(index).setStyle((index == tracks.selectedIndex() ? SELECTED : UNSELECTED)
                    + " -fx-background-radius: 6; -fx-border-radius: 6;");
        }
        int selected = tracks.selectedIndex();
        duplicate.setDisable(!tracks.canDuplicate(selected));
        remove.setDisable(!tracks.canRemove(selected));
        up.setDisable(!tracks.canMove(selected, -1));
        down.setDisable(!tracks.canMove(selected, 1));
    }

    private static String percent(float gain) {
        return Math.round(gain * 100) + "%";
    }
}

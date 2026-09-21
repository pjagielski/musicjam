package pl.livecoding.musicjam.studio.knobs;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import pl.livecoding.musicjam.synth.AcidBassSynth;
import pl.livecoding.musicjam.synth.AnthemLeadSynth;
import pl.livecoding.musicjam.synth.ChordsSynth;
import pl.livecoding.musicjam.synth.DelayMode;
import pl.livecoding.musicjam.synth.EffectParams;
import pl.livecoding.musicjam.synth.NovasawSynth;
import pl.livecoding.musicjam.synth.SubBassSynth;
import pl.livecoding.musicjam.synth.SynthParams;
import pl.livecoding.musicjam.synth.TrancePluckSynth;
import pl.livecoding.musicjam.synth.WidePadSynth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * The synth's front panel as one node: six framed groups in a grid of equal columns — oscillator
 * and amplifier (with the sidechain that ducks the synth under the kick), filter and envelope,
 * delay and reverb. Four of them carry a picture of what their knobs do, drawn right above them:
 * the XY pad for the filter, the envelope's own shape, where the delay's repeats land ear by ear,
 * and the reverb's tail dying away. The filter's and the envelope's
 * pictures can be dragged too; knob and picture are two views of one value.
 *
 * <p>Every move publishes a whole new {@link SynthParams} to whoever is listening — in the studio, a
 * {@link pl.livecoding.musicjam.synth.LiveNovasawSynth} that is playing right now.
 *
 * <p>Two of these exist: the panel dropped into {@code BeatStudio} ({@link #light()}) and the
 * standalone {@link SynthPanel} window ({@link #dark()}).
 */
public final class SynthControls {

    private static final double COLUMN_WIDTH = 350;
    private static final double KNOB_WIDTH = 62;

    private static final Param CUTOFF = Param.exponential("Cutoff", 40, 12000, "Hz", 0, 7200);
    private static final Param RESONANCE = Param.linear("Res", 0, 0.95, "", 2, 0.55);
    private static final Param ATTACK = Param.exponential("Attack", 1, 2000, "ms", 0, 6);
    private static final Param DECAY = Param.exponential("Decay", 1, 3000, "ms", 0, 350);
    private static final Param SUSTAIN = Param.linear("Sustain", 0, 1, "", 2, 0.82);
    private static final Param RELEASE = Param.exponential("Release", 1, 4000, "ms", 0, 110);

    /** What a delay repeat is worth in beats, so the echo lands with the jam instead of near it. */
    enum Sync {
        FREE("Free", 0), QUARTER("1/4", 1.0), DOTTED_EIGHTH("1/8.", 0.75),
        EIGHTH("1/8", 0.5), TRIPLET_EIGHTH("1/8T", 1.0 / 3), SIXTEENTH("1/16", 0.25);

        private final String label;
        private final double beats;

        Sync(String label, double beats) {
            this.label = label;
            this.beats = beats;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** The ported patches, as the panel's preset list offers them. */
    private static final Map<String, Supplier<NovasawSynth>> PATCHES = new LinkedHashMap<>();

    static {
        PATCHES.put("anthem", AnthemLeadSynth::new);
        PATCHES.put("pluck", TrancePluckSynth::new);
        PATCHES.put("pad", WidePadSynth::new);
        PATCHES.put("chords", ChordsSynth::new);
        PATCHES.put("sub bass", SubBassSynth::new);
        PATCHES.put("acid bass", AcidBassSynth::new);
    }

    private final List<Knob> knobs = new ArrayList<>();
    private final Map<Label, Theme.Accent> headers = new LinkedHashMap<>();
    private final List<VBox> frames = new ArrayList<>();

    private final Knob detune;
    private final Knob sub;
    private final Knob vibrato;
    private final Knob motionRate;
    private final Knob drift;
    private final Knob cutoff;
    private final Knob resonance;
    private final Knob envAmount;
    private final Knob keyTrack;
    private final Knob drive;
    private final Knob trim;
    private final Knob attack;
    private final Knob decay;
    private final Knob sustain;
    private final Knob release;
    private final Knob delayTime;
    private final Knob delayFeedback;
    private final Knob delayTone;
    private final Knob delayMix;
    private final Knob reverbSize;
    private final Knob reverbDamping;
    private final Knob reverbMix;
    private final Knob duckDepth;
    private final Knob duckRecover;
    private final XyPad pad;
    private final AdsrEditor envelope;
    private final ComboBox<String> preset = new ComboBox<>();
    private final Choice<Sync> sync;
    private final Label syncLabel = new Label("Sync");
    private final Choice<DelayMode> delayMode;
    private final DelayDiagram delayDiagram;
    private final ReverbDiagram reverbDiagram;
    private double bpm = 120;
    private final Label presetLabel = new Label("Preset");
    private final VBox panel;

    private Theme theme;
    private BiConsumer<SynthParams, EffectParams> onChange = (params, effects) -> { };
    private boolean applying;

    /** For a light background, as a column of {@code columns} groups: 1 beside a jam, 2 on its own. */
    public static SynthControls light(int columns) {
        return new SynthControls(Theme.LIGHT, columns);
    }

    public static SynthControls dark(int columns) {
        return new SynthControls(Theme.DARK, columns);
    }

    private SynthControls(Theme theme, int columns) {
        this.theme = theme;
        detune = knob(Param.linear("Detune", 0, 60, "ct", 1, 17), Theme.Accent.OSC, 62);
        sub = knob(Param.linear("Sub", 0, 1, "", 2, 0), Theme.Accent.OSC, 62);
        vibrato = knob(Param.linear("Vibrato", 0, 12, "ct", 2, 1.4), Theme.Accent.OSC, 62);
        motionRate = knob(Param.exponential("Motion", 0.05, 12, "Hz", 2, 5.2), Theme.Accent.OSC, 62);
        drift = knob(Param.linear("Drift", 0, 1, "", 2, 0.34), Theme.Accent.OSC, 62);
        drive = knob(Param.linear("Drive", 0, 2, "", 2, 1.09), Theme.Accent.AMP, 62);
        trim = knob(Param.linear("Output", 0, 1.5, "", 2, 1.0), Theme.Accent.AMP, 62);

        cutoff = knob(CUTOFF, Theme.Accent.FILTER, 72);
        resonance = knob(RESONANCE, Theme.Accent.FILTER, 62);
        envAmount = knob(Param.bipolar("Env→Filt", -4000, 8000, "Hz", 0, 1200), Theme.Accent.FILTER, 62);
        keyTrack = knob(Param.linear("Key trk", 0, 80, "Hz/semi", 0, 45), Theme.Accent.FILTER, 62);
        pad = new XyPad(CUTOFF, RESONANCE, Theme.Accent.FILTER, COLUMN_WIDTH, 150, theme);

        attack = knob(ATTACK, Theme.Accent.AMP, 62);
        decay = knob(DECAY, Theme.Accent.AMP, 62);
        sustain = knob(SUSTAIN, Theme.Accent.AMP, 62);
        release = knob(RELEASE, Theme.Accent.AMP, 62);
        envelope = new AdsrEditor(ATTACK, DECAY, SUSTAIN, RELEASE, Theme.Accent.AMP, COLUMN_WIDTH, 150, theme);

        EffectParams start = EffectParams.DEFAULT;
        delayTime = knob(Param.exponential("Time", 20, 2000, "ms", 0, start.delayMillis()), Theme.Accent.FX, 62);
        delayFeedback = knob(Param.linear("Feedback", 0, 0.95, "", 2, start.delayFeedback()), Theme.Accent.FX, 62);
        delayTone = knob(Param.linear("Tone", 0, 1, "", 2, start.delayTone()), Theme.Accent.FX, 62);
        delayMix = knob(Param.linear("Mix", 0, 1, "", 2, start.delayMix()), Theme.Accent.FX, 62);
        reverbSize = knob(Param.linear("Size", 0, 1, "", 2, start.reverbSize()), Theme.Accent.FX, 62);
        reverbDamping = knob(Param.linear("Damping", 0, 1, "", 2, start.reverbDamping()), Theme.Accent.FX, 62);
        reverbMix = knob(Param.linear("Mix", 0, 1, "", 2, start.reverbMix()), Theme.Accent.FX, 62);
        duckDepth = knob(Param.linear("Duck", 0, 1, "", 2, start.duckDepth()), Theme.Accent.FX, 62);
        duckRecover = knob(Param.exponential("Recover", 20, 1000, "ms", 0, start.duckMillis()),
                Theme.Accent.FX, 62);

        // knob and picture are two views of the same 0..1 position, so neither can drift from the other
        cutoff.position().bindBidirectional(pad.across());
        resonance.position().bindBidirectional(pad.up());
        attack.position().bindBidirectional(envelope.attackAt());
        decay.position().bindBidirectional(envelope.decayAt());
        sustain.position().bindBidirectional(envelope.sustainAt());
        release.position().bindBidirectional(envelope.releaseAt());
        knobs.forEach(knob -> knob.position().addListener((property, before, after) -> publish()));

        preset.getItems().setAll(PATCHES.keySet());
        preset.setOnAction(event -> {
            Supplier<NovasawSynth> chosen = PATCHES.get(preset.getValue());
            if (chosen != null && !applying) {
                apply(chosen.get().params());
            }
        });
        HBox presetRow = new HBox(10, presetLabel, preset);
        presetRow.setAlignment(Pos.CENTER_LEFT);

        delayMode = new Choice<>(Choice.Look.SEGMENTS, List.of(DelayMode.values()),
                EffectParams.DEFAULT.delayMode(), Theme.Accent.FX, theme);
        delayMode.setOnChange(mode -> publish());
        sync = new Choice<>(Choice.Look.CHIPS, List.of(Sync.values()), Sync.FREE, Theme.Accent.FX, theme);
        sync.setOnChange(division -> applySync());
        HBox syncRow = new HBox(8, syncLabel, sync);
        syncRow.setAlignment(Pos.CENTER_LEFT);
        delayDiagram = new DelayDiagram(COLUMN_WIDTH, Theme.Accent.FX, theme);
        VBox delayPicture = new VBox(8, delayMode, delayDiagram, syncRow);
        reverbDiagram = new ReverbDiagram(COLUMN_WIDTH, 128, Theme.Accent.FX, theme);

        List<VBox> groupBoxes = List.of(
                section("Oscillator", Theme.Accent.OSC, null, detune, sub, vibrato, motionRate, drift),
                section("Amp · Sidechain", Theme.Accent.AMP, null, drive, trim, duckDepth, duckRecover),
                section("Filter", Theme.Accent.FILTER, pad, cutoff, resonance, envAmount, keyTrack),
                section("Envelope", Theme.Accent.AMP, envelope, attack, decay, sustain, release),
                section("Delay", Theme.Accent.FX, delayPicture, delayTime, delayFeedback, delayTone, delayMix),
                section("Reverb", Theme.Accent.FX, reverbDiagram, reverbSize, reverbDamping, reverbMix));
        GridPane groups = new GridPane();
        groups.setHgap(14);
        groups.setVgap(12);
        for (int index = 0; index < groupBoxes.size(); index++) {
            groups.add(groupBoxes.get(index), index % columns, index / columns);
        }

        panel = new VBox(12, presetRow, groups);
        setTheme(theme);
        redrawEffects();
    }

    public Region node() {
        return panel;
    }

    /** Called whenever a control moves, with everything the synth and its effects need. */
    public void setOnChange(BiConsumer<SynthParams, EffectParams> listener) {
        this.onChange = listener;
    }

    /**
     * The jam's tempo, for a delay locked to it. Call it whenever the tempo changes: a synced delay
     * follows it straight away, a free one ignores it.
     */
    public void setTempo(double bpm) {
        this.bpm = bpm;
        applySync();
    }

    /** With a division chosen, the Time knob is driven by the tempo and left for the panel to set. */
    private void applySync() {
        Sync chosen = sync.value();
        delayTime.setDisable(chosen != null && chosen != Sync.FREE);
        if (chosen == null || chosen == Sync.FREE || bpm <= 0) {
            return;
        }
        delayTime.setValue(millisFor(chosen, bpm));
    }

    /** How long one repeat of {@code division} lasts at {@code bpm}, in milliseconds. */
    static double millisFor(Sync division, double bpm) {
        return division.beats * 60_000 / bpm;
    }

    /** The delay and reverb knobs, as the synth channel's effects read them. */
    public EffectParams effects() {
        return new EffectParams(delayMode.value(),
                (float) delayTime.value(), (float) delayFeedback.value(), (float) delayTone.value(),
                (float) delayMix.value(), (float) reverbSize.value(), (float) reverbDamping.value(),
                (float) reverbMix.value(), (float) duckDepth.value(), (float) duckRecover.value());
    }

    /** The knobs as the synth reads them. */
    public SynthParams params() {
        return SynthParams.of(
                (float) (attack.value() / 1000), (float) (decay.value() / 1000),
                (float) sustain.value(), (float) (release.value() / 1000),
                (float) detune.value(), (float) sub.value(), (float) vibrato.value(),
                (float) motionRate.value(),
                (float) drift.value(), (float) cutoff.value(), (float) resonance.value(),
                (float) envAmount.value(), (float) keyTrack.value(),
                (float) drive.value(), (float) trim.value());
    }

    /** Puts a patch onto the panel, publishing it once rather than once per control. */
    public void apply(SynthParams params) {
        applying = true;
        try {
            detune.setValue(params.detuneCents());
            sub.setValue(params.subLevel());
            vibrato.setValue(params.vibratoCents());
            motionRate.setValue(params.motionRateHz());
            drift.setValue(params.motion());
            cutoff.setValue(params.cutoffHz());
            resonance.setValue(params.resonance());
            envAmount.setValue(params.filterEnvAmountHz());
            keyTrack.setValue(params.keyTrackHzPerSemitone());
            drive.setValue(params.drive());
            trim.setValue(params.outputTrim());
            attack.setValue(params.attackSeconds() * 1000);
            decay.setValue(params.decaySeconds() * 1000);
            sustain.setValue(params.sustainLevel());
            release.setValue(params.releaseSeconds() * 1000);
        } finally {
            applying = false;
        }
        publish();
    }

    /**
     * Shows {@code name} as the chosen preset and loads its parameters; an unknown name (a synth
     * from outside the ported patches) just clears the list's selection.
     */
    public void selectPreset(String name) {
        Supplier<NovasawSynth> chosen = PATCHES.get(name == null ? "" : name.toLowerCase(Locale.ROOT));
        applying = true;
        try {
            preset.setValue(chosen == null ? null : name.toLowerCase(Locale.ROOT));
        } finally {
            applying = false;
        }
        if (chosen != null) {
            apply(chosen.get().params());
        }
    }

    /** The whole patch on one line, for a readout or the clipboard. */
    public String describe() {
        SynthParams params = params();
        EffectParams effects = effects();
        return String.format(Locale.ROOT,
                "detune=%.1fct sub=%.2f vibrato=%.2fct motion=%.2fHz drift=%.2f | cutoff=%.0fHz"
                        + " res=%.2f env=%+.0fHz keyTrack=%.0f | drive=%.2f trim=%.2f"
                        + " | A=%.0fms D=%.0fms S=%.2f R=%.0fms",
                params.detuneCents(), params.subLevel(), params.vibratoCents(), params.motionRateHz(),
                params.motion(),
                params.cutoffHz(), params.resonance(), params.filterEnvAmountHz(),
                params.keyTrackHzPerSemitone(), params.drive(), params.outputTrim(),
                params.attackSeconds() * 1000, params.decaySeconds() * 1000, params.sustainLevel(),
                params.releaseSeconds() * 1000)
                + String.format(Locale.ROOT,
                " | delay %s %.0fms fb=%.2f tone=%.2f mix=%.2f | reverb size=%.2f damp=%.2f mix=%.2f"
                        + " | duck=%.2f %.0fms",
                effects.delayMode(), effects.delayMillis(), effects.delayFeedback(),
                effects.delayTone(), effects.delayMix(),
                effects.reverbSize(), effects.reverbDamping(), effects.reverbMix(),
                effects.duckDepth(), effects.duckMillis());
    }

    private void publish() {
        redrawEffects();
        if (!applying) {
            onChange.accept(params(), effects());
        }
    }

    /** The two pictures follow the effect knobs, whoever moved them. */
    private void redrawEffects() {
        if (delayDiagram != null) {
            EffectParams current = effects();
            delayDiagram.update(current);
            reverbDiagram.update(current);
        }
    }

    Theme theme() {
        return theme;
    }

    void setTheme(Theme next) {
        theme = next;
        knobs.forEach(knob -> knob.setTheme(next));
        pad.setTheme(next);
        envelope.setTheme(next);
        String muted = "-fx-text-fill: " + Theme.web(next.mutedText()) + "; -fx-font-size: 11px;";
        presetLabel.setStyle(muted);
        syncLabel.setStyle(muted);
        sync.setTheme(next);
        delayMode.setTheme(next);
        delayDiagram.setTheme(next);
        reverbDiagram.setTheme(next);
        preset.setStyle("-fx-background-color: " + Theme.web(next.buttonFace())
                + "; -fx-background-radius: 6;");
        // an inline style cannot reach a combo's cells, and a dark theme would leave them unreadable
        preset.setButtonCell(themedCell(next));
        preset.setCellFactory(list -> themedCell(next));
        headers.forEach((header, accent) -> header.setStyle("-fx-text-fill: "
                + Theme.web(accent == null ? next.mutedText() : next.accent(accent))
                + "; -fx-font-size: 10px; -fx-font-weight: bold;"));
        frames.forEach(frame -> frame.setStyle("-fx-background-color: " + Theme.web(next.panel())
                + "; -fx-background-radius: 10; -fx-border-radius: 10; -fx-border-color: "
                + Theme.web(next.panelBorder()) + ";"));
    }

    /** A combo cell that takes its colours from the theme, since CSS selectors are out of reach. */
    static <T> ListCell<T> themedCell(Theme theme) {
        return new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.toString());
                setTextFill(theme.buttonText());
                setStyle("-fx-background-color: " + Theme.web(theme.buttonFace()) + ";");
            }
        };
    }

    private Knob knob(Param param, Theme.Accent accent, double size) {
        Knob knob = new Knob(param, accent, size, theme);
        // one width for every knob, so four of them fill a column and two sit centred in one
        knob.setPrefWidth(KNOB_WIDTH);
        knob.setMinWidth(KNOB_WIDTH);
        knobs.add(knob);
        return knob;
    }

    /**
     * A framed group of one column's width: its knobs across the bottom, and above them the picture
     * of what they do when the group has one. Every frame is the same width, so the four line up.
     */
    private VBox section(String name, Theme.Accent accent, Node picture, Knob... controls) {
        HBox row = new HBox(10, controls);
        row.setAlignment(Pos.CENTER);
        row.setPrefWidth(COLUMN_WIDTH);

        Label header = new Label(name.toUpperCase());
        headers.put(header, accent);
        VBox box = picture == null
                ? new VBox(10, header, row)
                : new VBox(10, header, picture, row);
        box.setPadding(new Insets(12, 14, 14, 14));
        box.setPrefWidth(COLUMN_WIDTH + 28);
        box.setMinWidth(COLUMN_WIDTH + 28);
        box.setMaxWidth(COLUMN_WIDTH + 28);
        frames.add(box);
        return box;
    }
}

package pl.livecoding.musicjam.studio;

import pl.livecoding.musicjam.livecode.LiveCode;
import pl.livecoding.musicjam.model.Drum;
import pl.livecoding.musicjam.model.DrumTrack;
import pl.livecoding.musicjam.model.Envelope;
import pl.livecoding.musicjam.model.Note;
import pl.livecoding.musicjam.model.PatternCompiler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One row of the studio's grid: a drum, the accent of each step across one bar (0 for a rest), the
 * row's gain, how long each hit's note lasts in bars (what {@code .rel} counts from) and how its
 * hits are shaped. A preset's {@link DrumTrack} and live code are both drawn into rows, and the rows
 * are what plays.
 */
record GridRow(String label, Drum drum, float[] accents, float gain, double noteBars, Envelope envelope) {

    private static final int MAX_STEPS = 64;
    private static final double EPSILON = 1e-6;

    static GridRow fromTrack(DrumTrack track) {
        String steps = track.steps();
        float[] accents = new float[steps.length()];
        for (int step = 0; step < accents.length; step++) {
            accents[step] = PatternCompiler.accentOf(steps.charAt(step));
        }
        return new GridRow(nameOf(track.drum()), track.drum(), accents, track.gain(), 1.0 / accents.length,
                Envelope.NONE);
    }

    /** One row per sound in each layer of the code, with as many steps as it takes to hold every hit exactly. */
    static List<GridRow> fromCode(LiveCode code) {
        Map<Key, List<LiveCode.Hit>> groups = new LinkedHashMap<>();
        for (LiveCode.Hit hit : code.hits()) {
            groups.computeIfAbsent(new Key(hit.layer(), hit.drum()), key -> new ArrayList<>()).add(hit);
        }
        List<GridRow> rows = new ArrayList<>();
        for (List<LiveCode.Hit> hits : groups.values()) {
            int steps = stepsFor(hits);
            float[] accents = new float[steps];
            for (LiveCode.Hit hit : hits) {
                int step = (int) Math.round(hit.start() * steps) % steps;
                accents[step] = Math.max(accents[step], hit.velocity());
            }
            LiveCode.Hit first = hits.get(0);
            rows.add(new GridRow((first.layer() + 1) + " " + nameOf(first.drum()), first.drum(), accents, 1.0f,
                    first.duration(), first.envelope()));
        }
        return List.copyOf(rows);
    }

    /** Every row's notes over {@code lengthBeats}, one bar after another, cut off where the length ends. */
    static List<Note> notes(List<GridRow> rows, double beatsPerBar, double lengthBeats) {
        List<Note> notes = new ArrayList<>();
        int bars = (int) Math.ceil(lengthBeats / beatsPerBar);
        for (int bar = 0; bar < bars; bar++) {
            for (GridRow row : rows) {
                int steps = row.accents().length;
                for (int step = 0; step < steps; step++) {
                    float velocity = Math.min(1.0f, row.accents()[step] * row.gain());
                    double beat = (bar + (double) step / steps) * beatsPerBar;
                    if (velocity > 0.0f && beat < lengthBeats) {
                        notes.add(new Note(beat, row.drum(), row.noteBars() * beatsPerBar, velocity, row.envelope()));
                    }
                }
            }
        }
        notes.sort(Comparator.comparingDouble(Note::beat));
        return List.copyOf(notes);
    }

    GridRow withAccent(int step, float accent) {
        float[] changed = accents.clone();
        changed[step] = accent;
        return new GridRow(label, drum, changed, gain, noteBars, envelope);
    }

    /** The fewest steps that put every hit exactly on one, widened to 16 whenever that still fits. */
    private static int stepsFor(List<LiveCode.Hit> hits) {
        for (int steps = 1; steps <= MAX_STEPS; steps++) {
            int candidate = steps;
            boolean fits = hits.stream()
                    .allMatch(hit -> Math.abs(hit.start() * candidate - Math.rint(hit.start() * candidate)) < EPSILON);
            if (fits) {
                return 16 % candidate == 0 ? 16 : candidate;
            }
        }
        return MAX_STEPS;
    }

    private static String nameOf(Drum drum) {
        return drum.sampleFile().replaceFirst("\\.wav$", "");
    }

    private record Key(int layer, Drum drum) {
    }
}

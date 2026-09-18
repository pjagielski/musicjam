package pl.livecoding.musicjam.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class PatternCompiler {

    private PatternCompiler() {
    }

    /**
     * One bar of a drum pattern as notes - the same {@link Note}s a MIDI file gives. Every track's
     * steps divide the bar evenly, and every step that is not a rest is a note one step long, as loud
     * as its accent times its track's gain.
     */
    public static List<Note> compile(List<DrumTrack> tracks, int beatsPerBar) {
        var notes = new ArrayList<Note>();
        for (DrumTrack track : tracks) {
            String steps = track.steps();
            double stepBeats = (double) beatsPerBar / steps.length();
            for (int step = 0; step < steps.length(); step++) {
                double beat = step * stepBeats;
                parse(steps.charAt(step)).ifPresent(accent ->
                        notes.add(new Note(beat, track.drum(), stepBeats, track.gain() * accent)));
            }
        }
        notes.sort(Comparator.comparingDouble(Note::beat));
        return List.copyOf(notes);
    }

    /**
     * How loud one step of a pattern is: {@code X} at full volume, {@code x} a little softer,
     * {@code o} at half - and no accent at all for a rest: a dot, a dash or a space.
     */
    public static Optional<Float> parse(char step) {
        return switch (step) {
            case 'X' -> Optional.of(1.0f);
            case 'x' -> Optional.of(0.8f);
            case 'o' -> Optional.of(0.5f);
            case '.', '-', ' ' -> Optional.empty();
            default -> throw new IllegalArgumentException("Unknown pattern character: " + step);
        };
    }
}

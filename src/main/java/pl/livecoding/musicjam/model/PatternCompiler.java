package pl.livecoding.musicjam.model;

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
        // TODO(step-5): a track of n steps divides the bar into n equal steps: step i starts at beat
        // TODO(step-5): i * beatsPerBar / n and lasts one step. parse says how loud a step is, and a rest
        // TODO(step-5): is no note at all. Every track goes into the same list, sorted by beat.
        throw new UnsupportedOperationException("PatternCompiler.compile");
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

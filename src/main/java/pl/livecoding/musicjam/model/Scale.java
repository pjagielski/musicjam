package pl.livecoding.musicjam.model;

import java.util.List;

/**
 * The key a jam is in: a root note and the steps a mode takes from it. It forbids nothing — it is
 * what the studio shades the piano roll with, so the notes that belong are the ones that stay
 * white and a note outside the key looks like the choice it is.
 *
 * <p>{@link Mode#ANY} holds every note, which is what a jam is in until someone says otherwise.
 */
public record Scale(int root, Mode mode) {

    /** The steps of a mode from its root, in semitones. */
    public enum Mode {
        ANY("Any", 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
        MAJOR("major", 0, 2, 4, 5, 7, 9, 11),
        MINOR("minor", 0, 2, 3, 5, 7, 8, 10),
        HARMONIC_MINOR("harmonic minor", 0, 2, 3, 5, 7, 8, 11),
        DORIAN("dorian", 0, 2, 3, 5, 7, 9, 10),
        PHRYGIAN("phrygian", 0, 1, 3, 5, 7, 8, 10),
        LYDIAN("lydian", 0, 2, 4, 6, 7, 9, 11),
        MIXOLYDIAN("mixolydian", 0, 2, 4, 5, 7, 9, 10),
        MINOR_PENTATONIC("minor pentatonic", 0, 3, 5, 7, 10),
        MAJOR_PENTATONIC("major pentatonic", 0, 2, 4, 7, 9);

        private final String label;
        private final boolean[] steps = new boolean[12];

        Mode(String label, int... semitones) {
            this.label = label;
            for (int step : semitones) {
                steps[step] = true;
            }
        }

        boolean holdsStep(int semitones) {
            return steps[semitones];
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** The twelve roots, as a picker offers them; sharps rather than flats, as the roll's keys are drawn. */
    public static final List<String> ROOTS =
            List.of("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B");

    /** No key chosen: every note belongs, and the roll shades nothing. */
    public static final Scale ANY = new Scale(0, Mode.ANY);

    public Scale {
        if (root < 0 || root > 11) {
            throw new IllegalArgumentException("A root is one of the twelve notes, not " + root);
        }
    }

    /** Whether {@code midiNote} belongs to this key, whichever octave it is in. */
    public boolean holds(int midiNote) {
        return mode.holdsStep(Math.floorMod(midiNote - root, 12));
    }

    /** Whether this note is the key's own root, the one a line comes home to. */
    public boolean isRoot(int midiNote) {
        return mode != Mode.ANY && Math.floorMod(midiNote - root, 12) == 0;
    }

    /** Whether anything is shaded at all: a jam with no key chosen holds every note. */
    public boolean chosen() {
        return mode != Mode.ANY;
    }

    public String rootName() {
        return ROOTS.get(root);
    }

    @Override
    public String toString() {
        return mode == Mode.ANY ? mode.toString() : rootName() + " " + mode;
    }
}

package pl.livecoding.musicjam.studio;

import java.util.Objects;

/**
 * One track of the studio's jam as the track list shows it: a name, a gain and a mute, and what
 * it plays. There is one {@link Drums} track, which is the grid, and any number of {@link Melody}
 * tracks, each with its own source of notes.
 */
sealed interface StudioTrack permits StudioTrack.Drums, StudioTrack.Melody {

    String name();

    float gain();

    boolean muted();

    StudioTrack named(String name);

    StudioTrack withGain(float gain);

    StudioTrack withMuted(boolean muted);

    /** The gain the engine is handed: the track's own, or nothing while it is muted. */
    default float audibleGain() {
        return muted() ? 0.0f : gain();
    }

    /** The drum grid, whose notes live in the grid itself rather than here. */
    record Drums(String name, float gain, boolean muted) implements StudioTrack {

        public Drums {
            Objects.requireNonNull(name, "name");
            checkGain(gain);
        }

        @Override
        public Drums named(String next) {
            return new Drums(next, gain, muted);
        }

        @Override
        public Drums withGain(float next) {
            return new Drums(name, next, muted);
        }

        @Override
        public Drums withMuted(boolean next) {
            return new Drums(name, gain, next);
        }
    }

    /** A line of notes played by the synth, read from a window of a MIDI file. */
    record Melody(String name, float gain, boolean muted, MidiWindow source) implements StudioTrack {

        public Melody {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(source, "source");
            checkGain(gain);
        }

        @Override
        public Melody named(String next) {
            return new Melody(next, gain, muted, source);
        }

        @Override
        public Melody withGain(float next) {
            return new Melody(name, next, muted, source);
        }

        @Override
        public Melody withMuted(boolean next) {
            return new Melody(name, gain, next, source);
        }

        public Melody withSource(MidiWindow next) {
            return new Melody(name, gain, muted, next);
        }
    }

    private static void checkGain(float gain) {
        if (!Float.isFinite(gain) || gain < 0.0f || gain > 1.0f) {
            throw new IllegalArgumentException("Gain must be between 0 and 1");
        }
    }
}

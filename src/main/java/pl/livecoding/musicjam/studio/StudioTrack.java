package pl.livecoding.musicjam.studio;

import pl.livecoding.musicjam.audio.Sample;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One track of the studio's jam as the track list shows it: a name, a gain and a mute, and what
 * it plays. There is one {@link Drums} track, which is the grid, any number of {@link Melody}
 * tracks, each with its own source of notes, and any number of {@link Loop} tracks, each playing a
 * piece of recorded audio in time with the jam.
 */
sealed interface StudioTrack permits StudioTrack.Drums, StudioTrack.Melody, StudioTrack.Loop {

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

    /** A line of notes of its own or of a MIDI file's, played by an instrument of its own. */
    record Melody(String name, float gain, boolean muted, MelodySource source, Instrument instrument)
            implements StudioTrack {

        public Melody {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(source, "source");
            checkGain(gain);
        }

        /** A track with no instrument to play it: what the track list's own tests need, and no more. */
        Melody(String name, float gain, boolean muted, MelodySource source) {
            this(name, gain, muted, source, null);
        }

        /**
         * The MIDI file window this track plays, or the one its own notes were taken from; null for
         * notes with no file behind them.
         */
        MidiWindow window() {
            return switch (source) {
                case MidiWindow window -> window;
                case MelodySource.OwnNotes own -> own.from();
            };
        }

        @Override
        public Melody named(String next) {
            return new Melody(next, gain, muted, source, instrument);
        }

        @Override
        public Melody withGain(float next) {
            return new Melody(name, next, muted, source, instrument);
        }

        @Override
        public Melody withMuted(boolean next) {
            return new Melody(name, gain, next, source, instrument);
        }

        public Melody withSource(MelodySource next) {
            return new Melody(name, gain, muted, next, instrument);
        }
    }

    /** A piece of recorded audio, played over as many bars of the jam as it is taken to fill. */
    record Loop(String name, float gain, boolean muted, Path file, double bars, Sample audio)
            implements StudioTrack {

        public Loop {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(audio, "audio");
            checkGain(gain);
            if (!Double.isFinite(bars) || bars <= 0) {
                throw new IllegalArgumentException("A loop fills a positive number of bars, not " + bars);
            }
        }

        @Override
        public Loop named(String next) {
            return new Loop(next, gain, muted, file, bars, audio);
        }

        @Override
        public Loop withGain(float next) {
            return new Loop(name, next, muted, file, bars, audio);
        }

        @Override
        public Loop withMuted(boolean next) {
            return new Loop(name, gain, next, file, bars, audio);
        }

        Loop over(double nextBars) {
            return new Loop(name, gain, muted, file, nextBars, audio);
        }

        /** The tempo the audio was cut at, as far as its length and its bars tell. */
        double sourceBpm(int sampleRate, int beatsPerBar) {
            return bars * beatsPerBar * 60.0 * sampleRate / audio.frameCount();
        }
    }

    private static void checkGain(float gain) {
        if (!Float.isFinite(gain) || gain < 0.0f || gain > 1.0f) {
            throw new IllegalArgumentException("Gain must be between 0 and 1");
        }
    }
}

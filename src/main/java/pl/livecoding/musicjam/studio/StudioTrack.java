package pl.livecoding.musicjam.studio;

import pl.livecoding.musicjam.audio.Sample;
import pl.livecoding.musicjam.audio.Slices;
import pl.livecoding.musicjam.model.Step;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * How a loop is cut up and in what order its slices are played: not at all, or into slices of
     * {@code beats} each, every boundary but the first moved to the hit nearest it when
     * {@code toHits} and there is one to move to.
     *
     * <p>{@code starts} is where the slices begin, worked out once when the cut changes rather than
     * every time the jam is handed over - finding the hits means reading the whole sample.
     * {@code order} is which slice each step of the pass plays, or -1 for a step that strikes
     * nothing; an order that is simply 0, 1, 2 ... is a loop played as it was recorded.
     */
    record Slicing(double beats, boolean toHits, List<Integer> starts, List<Integer> order) {

        static final Slicing NONE = new Slicing(0, true, List.of(), List.of());

        /** How far a boundary may move to find its hit: a tenth of a slice either way. */
        private static final double NEAR = 0.1;

        public Slicing {
            starts = List.copyOf(starts);
            order = List.copyOf(order);
        }

        boolean on() {
            return beats > 0 && !starts.isEmpty();
        }

        int count() {
            return starts.size();
        }

        /**
         * Where the slices of {@code audio} fall for a cut of {@code beats} over a pass of
         * {@code lengthBeats}, with {@code wanted} kept where it still fits and the rest of the
         * order laid out plainly. This is the one place a cut is worked out.
         */
        static Slicing of(Sample audio, double beats, boolean toHits, double lengthBeats, List<Integer> wanted) {
            if (beats <= 0 || lengthBeats <= 0) {
                return NONE;
            }
            int count = Math.max(1, (int) Math.round(lengthBeats / beats));
            int[] found = toHits ? Slices.onTheHits(audio, count, NEAR)
                    : Slices.onTheGrid(audio.frameCount(), count);
            List<Integer> starts = new ArrayList<>(found.length);
            for (int start : found) {
                starts.add(start);
            }
            List<Integer> order = new ArrayList<>(count);
            for (int step = 0; step < count; step++) {
                int was = step < wanted.size() ? wanted.get(step) : step;
                // a step naming a slice the cut no longer has goes back to naming its own
                order.add(was >= count ? step : was);
            }
            return new Slicing(beats, toHits, starts, order);
        }

        /** The same cut with {@code step} playing {@code slice}, or -1 for nothing struck there. */
        Slicing with(int step, int slice) {
            List<Integer> next = new ArrayList<>(order);
            next.set(step, slice);
            return new Slicing(beats, toHits, starts, next);
        }

        /** The same cut, played as it was recorded. */
        Slicing straightened() {
            List<Integer> next = new ArrayList<>(count());
            for (int step = 0; step < count(); step++) {
                next.add(step);
            }
            return new Slicing(beats, toHits, starts, next);
        }

        /** Whether the slices are played in the order they were recorded in, which is the plain case. */
        boolean straight() {
            for (int step = 0; step < order.size(); step++) {
                if (order.get(step) != step) {
                    return false;
                }
            }
            return true;
        }

        /**
         * The steps the engine plays, or nothing at all where the order is the plain one - a loop
         * played as recorded is one piece of audio struck once, not a slice struck at every step.
         */
        List<Step> steps(int frameCount) {
            if (!on() || straight()) {
                return List.of();
            }
            List<Step> steps = new ArrayList<>(order.size());
            for (int slice : order) {
                if (slice < 0 || slice >= starts.size()) {
                    steps.add(Step.REST);
                    continue;
                }
                int from = starts.get(slice);
                int until = slice + 1 < starts.size() ? starts.get(slice + 1) : frameCount;
                steps.add(new Step(from, until));
            }
            return steps;
        }
    }

    /** A piece of recorded audio, played over as many bars of the jam as it is taken to fill. */
    record Loop(String name, float gain, boolean muted, Path file, double bars, Sample audio, Slicing slicing)
            implements StudioTrack {

        public Loop {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(audio, "audio");
            Objects.requireNonNull(slicing, "slicing");
            checkGain(gain);
            if (!Double.isFinite(bars) || bars <= 0) {
                throw new IllegalArgumentException("A loop fills a positive number of bars, not " + bars);
            }
        }

        /** A loop with nothing cut out of it yet, which is how one is first chosen. */
        Loop(String name, float gain, boolean muted, Path file, double bars, Sample audio) {
            this(name, gain, muted, file, bars, audio, Slicing.NONE);
        }

        @Override
        public Loop named(String next) {
            return new Loop(next, gain, muted, file, bars, audio, slicing);
        }

        @Override
        public Loop withGain(float next) {
            return new Loop(name, next, muted, file, bars, audio, slicing);
        }

        @Override
        public Loop withMuted(boolean next) {
            return new Loop(name, gain, next, file, bars, audio, slicing);
        }

        Loop over(double nextBars) {
            return new Loop(name, gain, muted, file, nextBars, audio, slicing);
        }

        Loop cutInto(Slicing next) {
            return new Loop(name, gain, muted, file, bars, audio, next);
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

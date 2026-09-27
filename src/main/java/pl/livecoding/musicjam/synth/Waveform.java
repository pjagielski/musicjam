package pl.livecoding.musicjam.synth;

/**
 * What each of a patch's oscillators puts out before anything is done to it. Until now it was a saw
 * and only a saw, which is what a supersaw is made of and is wrong for half of what a bass wants: a
 * square has no even harmonics at all and hollows out under a filter quite differently, and a sine
 * has nothing to filter, so it is the fundamental and nothing else.
 *
 * <p>The saw and the square are band-limited by {@code polyBlep}, so neither folds its top
 * harmonics back down the spectrum as it climbs. The sine needs no such help, having nothing above
 * its fundamental to fold.
 *
 * <p>Each carries the gain that makes it about as loud as the others. Measured through whole voices
 * rather than taken from the textbook figures for one cycle, because what is being matched is
 * several oscillators detuned against each other, summed, driven and filtered. It cannot be exact
 * and is not: a square is odd harmonics only, so a closed filter keeps less of it than of a saw and
 * an open one keeps more, which leaves about two decibels either way across the patches. That is
 * the waveform being a different sound, not the levelling being wrong.
 */
public enum Waveform {

    /** Every harmonic, falling at 6 dB an octave: what the patches were written on. */
    SAW("Saw", 1.0f),
    /** Odd harmonics only, falling at the same rate: hollow, and a third lower in the low end. */
    SQUARE("Square", 0.64f),
    /** One harmonic. Nothing to filter and nothing to fold; a fundamental and no more. */
    SINE("Sine", 0.88f);

    private final String label;
    private final float level;

    Waveform(String label, float level) {
        this.label = label;
        this.level = level;
    }

    public String label() {
        return label;
    }

    /** What it is multiplied by so that changing the waveform does not change the loudness. */
    public float level() {
        return level;
    }

    @Override
    public String toString() {
        return label;
    }
}

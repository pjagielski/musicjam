package pl.livecoding.musicjam.model;

import pl.livecoding.musicjam.audio.Sample;

import java.util.Objects;

/**
 * A track that plays a piece of recorded audio rather than notes: a break, a vocal, a bar of
 * chords. {@code bars} is how many bars of the jam it is meant to fill, which is all the engine
 * needs to work out how fast to read it — the rate that makes those bars last as long as the jam's
 * do, whatever tempo the jam is at.
 *
 * <p>A loop shorter than the jam's own loop plays again from its start; the engine starts it every
 * {@code bars} bars.
 */
public record LoopTrack(Sample audio, double bars, float gain) implements Track {

    public LoopTrack {
        Objects.requireNonNull(audio, "audio");
        if (!Double.isFinite(bars) || bars <= 0.0) {
            throw new IllegalArgumentException("A loop fills a positive number of bars, not " + bars);
        }
        if (!Float.isFinite(gain) || gain < 0.0f || gain > 1.0f) {
            throw new IllegalArgumentException("Gain must be between 0 and 1");
        }
    }

    /** How long one pass lasts, in beats. */
    public double lengthBeats(int beatsPerBar) {
        return bars * beatsPerBar;
    }

    /**
     * The tempo the audio was recorded at, as far as its length and its bars tell: what the studio
     * shows beside a loop, and what says whether the bars given to it are right.
     */
    public double sourceBpm(int sampleRate, int beatsPerBar) {
        double seconds = audio.frameCount() / (double) sampleRate;
        return lengthBeats(beatsPerBar) * 60.0 / seconds;
    }
}

package pl.livecoding.musicjam.audio;

/**
 * Something the synth's output passes through on its way to the mix — a delay, a reverb, a chain of
 * both. Called once per frame, in order, so it can keep a tail of what it has already heard.
 *
 * <p>One frame goes in and a stereo pair comes out: everything else in the engine is mono sent to
 * both ears, so an effect is the one place width can come from.
 *
 * <p>Only the voices of a {@link pl.livecoding.musicjam.synth.LivePitchSynth} go through it; the
 * drums are mixed straight in, as an insert on one channel of a desk would be.
 */
public interface AudioEffect {

    /** Writes the left and right output for {@code input} into {@code stereoOut}, tails and all. */
    void process(float input, float[] stereoOut);

    /**
     * The sidechain's key: a kick has just landed, on the frame about to be processed. An effect
     * that ducks under it dips now and comes back on its own; one that does not ignores it.
     */
    default void duck() {
    }
}

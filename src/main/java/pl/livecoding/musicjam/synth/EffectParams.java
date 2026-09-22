package pl.livecoding.musicjam.synth;

/**
 * The knobs of the synth channel's two effects: a delay (which way its repeats travel, how long,
 * how much comes back, how dark each repeat gets, how much of it is heard) and a reverb (how big
 * the room is, how dark it is, how much of it is heard), the crusher in front of them (how far the
 * channel's sample rate and bit depth are dropped, 0 for neither), and the sidechain that ducks
 * what comes out under the kick (how deep it dips, how long it takes to come back).
 * Like {@link SynthParams} this is a value the audio thread reads and a panel replaces whole.
 */
public record EffectParams(
        DelayMode delayMode, float delayMillis, float delayFeedback, float delayTone, float delayMix,
        float reverbSize, float reverbDamping, float reverbMix,
        float duckDepth, float duckMillis, float crush) {

    /** Both effects present but quiet, no ducking: the sound is the patch's, with a little room around it. */
    public static final EffectParams DEFAULT =
            new EffectParams(DelayMode.PING_PONG, 250, 0.35f, 0.55f, 0.0f, 0.62f, 0.45f, 0.12f);

    /** Delay and reverb only, with the sidechain and the crusher off. */
    public EffectParams(DelayMode delayMode, float delayMillis, float delayFeedback, float delayTone,
                        float delayMix, float reverbSize, float reverbDamping, float reverbMix) {
        this(delayMode, delayMillis, delayFeedback, delayTone, delayMix, reverbSize, reverbDamping,
                reverbMix, 0.0f, 250, 0.0f);
    }

    /** Everything but the crusher, which stays off. */
    public EffectParams(DelayMode delayMode, float delayMillis, float delayFeedback, float delayTone,
                        float delayMix, float reverbSize, float reverbDamping, float reverbMix,
                        float duckDepth, float duckMillis) {
        this(delayMode, delayMillis, delayFeedback, delayTone, delayMix, reverbSize, reverbDamping,
                reverbMix, duckDepth, duckMillis, 0.0f);
    }

    public EffectParams {
        delayMode = delayMode == null ? DelayMode.PING_PONG : delayMode;
        delayMillis = clamp(delayMillis, 10, 2000);
        delayFeedback = clamp(delayFeedback, 0, 0.95f);
        delayTone = clamp(delayTone, 0, 1);
        delayMix = clamp(delayMix, 0, 1);
        reverbSize = clamp(reverbSize, 0, 1);
        reverbDamping = clamp(reverbDamping, 0, 1);
        reverbMix = clamp(reverbMix, 0, 1);
        duckDepth = clamp(duckDepth, 0, 1);
        duckMillis = clamp(duckMillis, 20, 2000);
        crush = clamp(crush, 0, 1);
    }

    public EffectParams withDuck(float depth, float millis) {
        return new EffectParams(delayMode, delayMillis, delayFeedback, delayTone, delayMix,
                reverbSize, reverbDamping, reverbMix, depth, millis, crush);
    }

    public EffectParams withCrush(float crush) {
        return new EffectParams(delayMode, delayMillis, delayFeedback, delayTone, delayMix,
                reverbSize, reverbDamping, reverbMix, duckDepth, duckMillis, crush);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}

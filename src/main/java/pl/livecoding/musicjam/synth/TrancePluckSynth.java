package pl.livecoding.musicjam.synth;

/**
 * "Trance Pluck - Classic" patch (Sandbox/novasaw, Source/MainComponent.cpp, recipe index 1,
 * factory preset intensity=0.58 tone=0.64 space=0.54 motion=0.28 — "space" unused, see
 * {@link NovasawSynth}). Near-instant attack, short decay to a low sustain — the pluck comes from
 * the envelope shape, not from any release trickery.
 */
public final class TrancePluckSynth extends NovasawSynth {

    public TrancePluckSynth() {
        super(
                0.002f, 0.120f, 0.18f, 0.070f,
                7.0f, 22.0f,
                900.0f, 6400.0f, 4600.0f, 35.0f,
                3.0f, 1.2f,
                0.10f, 1.10f, 1.06f,
                0.58f, 0.64f, 0.28f
        );
    }
}

package pl.livecoding.musicjam.synth;

/**
 * "Wide Pad - Halo" patch (Sandbox/novasaw, Source/MainComponent.cpp, recipe index 2, factory
 * preset intensity=0.44 tone=0.48 space=0.84 motion=0.52 — "space" unused, see
 * {@link NovasawSynth}). Slow attack/release, darker and less driven than {@link AnthemLeadSynth}.
 */
public final class WidePadSynth extends NovasawSynth {

    public WidePadSynth() {
        super(
                0.380f, 1.200f, 0.88f, 1.500f,
                3.0f, 11.0f,
                700.0f, 4300.0f, 900.0f, 28.0f,
                0.18f, 2.5f,
                0.00f, 0.35f, 0.78f,
                0.44f, 0.48f, 0.52f
        );
    }
}

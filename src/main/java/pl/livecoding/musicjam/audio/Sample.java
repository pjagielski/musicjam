package pl.livecoding.musicjam.audio;

import pl.livecoding.musicjam.model.Envelope;

import java.util.Arrays;

public final class Sample {
    private final float[] mono;

    private Sample(float[] mono) {
        if (mono.length == 0) {
            throw new IllegalArgumentException("Sample cannot be empty");
        }
        this.mono = mono;
    }

    public static Sample mono(float... data) {
        return new Sample(Arrays.copyOf(data, data.length));
    }

    public int frameCount() {
        return mono.length;
    }

    float valueAt(int frame) {
        return mono[frame];
    }

    /** Defensive copy, for callers outside this package that render their own mix. */
    public float[] copyMono() {
        return Arrays.copyOf(mono, mono.length);
    }

    /**
     * A copy shaped by {@code envelope}: decaying to a thousandth of its level (-60 dB) over the
     * decay time and cut there, then fading out linearly over the release time once
     * {@code heldFrames} - the length of its note - have passed.
     */
    Sample shaped(Envelope envelope, int heldFrames, int sampleRate) {
        if (!envelope.shapes()) {
            return this;
        }
        int length = mono.length;
        double decayPerFrame = 0.0;
        if (envelope.decaySeconds() > 0.0) {
            decayPerFrame = Math.log(1000.0) / envelope.decaySeconds() / sampleRate;
            length = Math.min(length, (int) Math.round(envelope.decaySeconds() * sampleRate));
        }
        int releaseFrames = (int) Math.round(envelope.releaseSeconds() * sampleRate);
        if (releaseFrames > 0) {
            length = Math.min(length, heldFrames + releaseFrames);
        }
        float[] shaped = new float[Math.max(1, length)];
        for (int frame = 0; frame < shaped.length; frame++) {
            double gain = Math.exp(-decayPerFrame * frame);
            if (releaseFrames > 0 && frame >= heldFrames) {
                gain *= 1.0 - (double) (frame - heldFrames) / releaseFrames;
            }
            shaped[frame] = (float) (mono[frame] * gain);
        }
        return new Sample(shaped);
    }
}

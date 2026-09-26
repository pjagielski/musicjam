package pl.livecoding.musicjam.audio;

import pl.livecoding.musicjam.model.Envelope;

import java.util.Arrays;

/**
 * A piece of audio the engine can play: a drum hit, a note rendered in advance, a loop off a
 * record. One channel or two — a drum sample is the same in both ears, a break is not, and the
 * engine is stereo from the mix on, so it is kept the way it was read.
 */
public final class Sample {
    private final float[] left;
    private final float[] right;

    private Sample(float[] left, float[] right) {
        if (left.length == 0) {
            throw new IllegalArgumentException("Sample cannot be empty");
        }
        if (right.length != left.length) {
            throw new IllegalArgumentException("A sample's channels are the same length");
        }
        this.left = left;
        this.right = right;
    }

    public static Sample mono(float... data) {
        float[] copy = Arrays.copyOf(data, data.length);
        return new Sample(copy, copy);
    }

    /** Two channels, as a stereo file was read: the same length, and kept apart. */
    public static Sample stereo(float[] left, float[] right) {
        return new Sample(Arrays.copyOf(left, left.length), Arrays.copyOf(right, right.length));
    }

    public int frameCount() {
        return left.length;
    }

    /** Whether the two channels differ; a mono sample is the same array twice. */
    public boolean stereo() {
        return left != right;
    }

    float valueAt(int frame) {
        return left[frame];
    }

    float leftAt(int frame) {
        return left[frame];
    }

    float rightAt(int frame) {
        return right[frame];
    }

    /** Defensive copy of both channels, left then right; a mono sample gives the same twice. */
    public float[][] copyStereo() {
        return new float[][] {Arrays.copyOf(left, left.length), Arrays.copyOf(right, right.length)};
    }

    /** Defensive copy of what is heard in the middle, for callers that render their own mix. */
    public float[] copyMono() {
        if (!stereo()) {
            return Arrays.copyOf(left, left.length);
        }
        float[] both = new float[left.length];
        for (int frame = 0; frame < both.length; frame++) {
            both[frame] = (left[frame] + right[frame]) / 2;
        }
        return both;
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
        int length = left.length;
        double decayPerFrame = 0.0;
        if (envelope.decaySeconds() > 0.0) {
            decayPerFrame = Math.log(1000.0) / envelope.decaySeconds() / sampleRate;
            length = Math.min(length, (int) Math.round(envelope.decaySeconds() * sampleRate));
        }
        int releaseFrames = (int) Math.round(envelope.releaseSeconds() * sampleRate);
        if (releaseFrames > 0) {
            length = Math.min(length, heldFrames + releaseFrames);
        }
        float[] shapedLeft = new float[Math.max(1, length)];
        float[] shapedRight = stereo() ? new float[shapedLeft.length] : shapedLeft;
        for (int frame = 0; frame < shapedLeft.length; frame++) {
            double gain = Math.exp(-decayPerFrame * frame);
            if (releaseFrames > 0 && frame >= heldFrames) {
                gain *= 1.0 - (double) (frame - heldFrames) / releaseFrames;
            }
            shapedLeft[frame] = (float) (left[frame] * gain);
            if (stereo()) {
                shapedRight[frame] = (float) (right[frame] * gain);
            }
        }
        return new Sample(shapedLeft, shapedRight);
    }
}

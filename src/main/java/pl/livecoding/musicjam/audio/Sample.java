package pl.livecoding.musicjam.audio;

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
}

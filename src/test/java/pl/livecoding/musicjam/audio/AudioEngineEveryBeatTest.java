package pl.livecoding.musicjam.audio;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The drum the calibration helper of step 5 plays on every beat, green from the start. At 1000 frames
 * a second and 120 BPM a beat is 500 frames.
 */
class AudioEngineEveryBeatTest {

    @Test
    void startsTheClickOnTheFrameOfEveryBeat() {
        float[] mix = new float[128 * 2];

        AudioEngine.mixEveryBeat(Sample.mono(1.0f), 500.0, 384, mix, 128);

        assertEquals(0.0f, mix[115 * 2]);
        assertEquals(1.0f, mix[116 * 2], "frame 500 is the 116th of the block that starts at 384");
        assertEquals(1.0f, mix[116 * 2 + 1]);
        assertEquals(0.0f, mix[117 * 2]);
    }

    @Test
    void theFirstBeatIsFrameZero() {
        float[] mix = new float[128 * 2];

        AudioEngine.mixEveryBeat(Sample.mono(1.0f), 500.0, 0, mix, 128);

        assertEquals(1.0f, mix[0]);
    }

    @Test
    void aClickCutByTheBlockEdgeGoesOnInTheNextBlock() {
        var click = Sample.mono(0.1f, 0.2f, 0.3f);
        float[] first = new float[501 * 2];
        float[] next = new float[128 * 2];

        AudioEngine.mixEveryBeat(click, 500.0, 0, first, 501);
        AudioEngine.mixEveryBeat(click, 500.0, 501, next, 128);

        assertEquals(0.1f, first[500 * 2]);
        assertEquals(0.2f, next[0]);
        assertEquals(0.3f, next[2]);
        assertEquals(0.0f, next[2 * 2]);
    }

    @Test
    void clearsWhatTheLastBlockLeft() {
        float[] mix = new float[128 * 2];
        Arrays.fill(mix, 0.5f);

        AudioEngine.mixEveryBeat(Sample.mono(1.0f), 500.0, 128, mix, 128);

        assertEquals(0.0f, mix[0]);
    }
}

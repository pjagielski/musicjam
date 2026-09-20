package pl.livecoding.musicjam.synth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SynthEffectsTest {

    private static final int SAMPLE_RATE = 1_000;
    private static final int LEFT = 0;
    private static final int RIGHT = 1;

    @Test
    void theDelayBouncesItsRepeatsFromEarToEar() {
        // 100 ms, half of it fed back, tone wide open, nothing but the delay heard
        var effects = new SynthEffects(SAMPLE_RATE,
                () -> new EffectParams(DelayMode.PING_PONG, 100, 0.5f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f));

        float[][] out = impulse(effects, 450);

        assertEquals(1.0f, out[LEFT][100], 1e-3f);
        assertEquals(0.0f, out[RIGHT][100], 1e-3f);
        assertEquals(0.0f, out[LEFT][200], 1e-3f);
        assertTrue(out[RIGHT][200] > 0.4f, "the first repeat comes back on the other side");
        assertTrue(out[LEFT][300] > 0.15f && out[LEFT][300] < out[RIGHT][200],
                "and crosses back, quieter each time");
    }

    @Test
    void monoPutsTheSameRepeatInBothEars() {
        var effects = new SynthEffects(SAMPLE_RATE,
                () -> new EffectParams(DelayMode.MONO, 100, 0.5f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f));

        float[][] out = impulse(effects, 350);

        assertEquals(1.0f, out[LEFT][100], 1e-3f);
        assertEquals(out[LEFT][100], out[RIGHT][100], 1e-6f);
        assertEquals(out[LEFT][200], out[RIGHT][200], 1e-6f);
    }

    @Test
    void stereoGivesTheRightEarTheShorterTime() {
        var effects = new SynthEffects(SAMPLE_RATE,
                () -> new EffectParams(DelayMode.STEREO, 150, 0.4f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f));

        float[][] out = impulse(effects, 400);

        // the left repeat lands at its own time, the right at two thirds of it
        assertEquals(1.0f, out[LEFT][150], 1e-3f);
        assertEquals(1.0f, out[RIGHT][100], 1e-3f);
        assertEquals(0.0f, out[RIGHT][150], 1e-3f);
    }

    @Test
    void tapeDriftsAwayFromTheTimeItWasGiven() {
        var steady = new SynthEffects(SAMPLE_RATE,
                () -> new EffectParams(DelayMode.MONO, 200, 0.6f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f));
        var tape = new SynthEffects(SAMPLE_RATE,
                () -> new EffectParams(DelayMode.TAPE, 200, 0.6f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f));

        float[][] straight = impulse(steady, 3_000);
        float[][] warbled = impulse(tape, 3_000);

        assertTrue(difference(straight[LEFT], warbled[LEFT]) > 0.01,
                "the drifting read head should not land where a steady one does");
        for (float value : warbled[LEFT]) {
            assertTrue(Math.abs(value) <= 1.05f, "warble is no excuse for getting louder");
        }
    }

    @Test
    void theToneKnobDarkensEachRepeat() {
        float bright = repeatLevel(1.0f);
        float dark = repeatLevel(0.2f);

        assertTrue(dark < bright * 0.9f, "a closed tone should take the edge off the repeats");
    }

    @Test
    void feedbackNearTheTopStaysBounded() {
        var effects = new SynthEffects(SAMPLE_RATE,
                () -> new EffectParams(DelayMode.PING_PONG, 50, 0.95f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f));

        float[][] out = impulse(effects, 20_000);

        for (int channel = 0; channel < 2; channel++) {
            for (float value : out[channel]) {
                assertTrue(Math.abs(value) <= 1.05f, "a runaway delay would blow past its input");
            }
        }
    }

    @Test
    void theReverbRingsOnInStereoAndDiesAway() {
        var effects = new SynthEffects(SAMPLE_RATE,
                () -> new EffectParams(DelayMode.PING_PONG, 100, 0.0f, 1.0f, 0.0f, 0.7f, 0.4f, 1.0f));

        float[][] out = impulse(effects, 8_000);

        assertTrue(energy(out[LEFT], 0, 2_000) > 0.001, "the room should answer the impulse");
        assertTrue(energy(out[LEFT], 6_000, 8_000) < energy(out[LEFT], 0, 2_000), "and then die away");
        // the right channel's twelve delay lines are longer, so the two ears never hear the same room
        assertTrue(difference(out[LEFT], out[RIGHT]) > 0.0001, "the two channels should differ");
        for (int channel = 0; channel < 2; channel++) {
            for (float value : out[channel]) {
                assertTrue(Math.abs(value) <= 1.0f, "a reverb louder than its input is feeding back");
            }
        }
    }

    @Test
    void aBiggerRoomRingsForLonger() {
        float small = tailEnergy(0.1f);
        float large = tailEnergy(0.95f);

        assertTrue(large > small * 2, "size should stretch the tail, not just colour it");
    }

    /** The level of the first repeat, which is what the tone knob is expected to change. */
    private static float repeatLevel(float tone) {
        var effects = new SynthEffects(SAMPLE_RATE,
                () -> new EffectParams(DelayMode.PING_PONG, 100, 0.5f, tone, 1.0f, 0.0f, 0.0f, 0.0f));
        float[][] out = impulse(effects, 260);
        float peak = 0;
        for (int frame = 180; frame < 260; frame++) {
            peak = Math.max(peak, Math.abs(out[RIGHT][frame]));
        }
        return peak;
    }

    private static float tailEnergy(float size) {
        var effects = new SynthEffects(SAMPLE_RATE,
                () -> new EffectParams(DelayMode.PING_PONG, 100, 0.0f, 1.0f, 0.0f, size, 0.2f, 1.0f));
        float[][] out = impulse(effects, 8_000);
        return (float) energy(out[LEFT], 5_000, 8_000);
    }

    /** One frame in at full level, then silence: what comes out is the effect's own tail. */
    private static float[][] impulse(SynthEffects effects, int frames) {
        float[][] out = new float[2][frames];
        float[] stereo = new float[2];
        for (int frame = 0; frame < frames; frame++) {
            effects.process(frame == 0 ? 1.0f : 0.0f, stereo);
            out[LEFT][frame] = stereo[LEFT];
            out[RIGHT][frame] = stereo[RIGHT];
        }
        return out;
    }

    private static double energy(float[] values, int from, int to) {
        double energy = 0;
        for (int index = from; index < to; index++) {
            energy += values[index] * values[index];
        }
        return energy;
    }

    private static double difference(float[] left, float[] right) {
        double difference = 0;
        for (int index = 0; index < left.length; index++) {
            difference += Math.abs(left[index] - right[index]);
        }
        return difference;
    }
}

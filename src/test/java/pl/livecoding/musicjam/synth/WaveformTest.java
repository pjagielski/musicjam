package pl.livecoding.musicjam.synth;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaveformTest {

    private static final int RATE = 48_000;

    /** How much energy sits at {@code hz}, by Goertzel. */
    private static double energyAt(float[] wave, double hz) {
        double coefficient = 2 * Math.cos(2 * Math.PI * hz / RATE);
        double s1 = 0;
        double s2 = 0;
        for (float sample : wave) {
            double s0 = sample + coefficient * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        return Math.sqrt(Math.max(0, s1 * s1 + s2 * s2 - coefficient * s1 * s2)) / wave.length;
    }

    private static float[] square(double hz, boolean corrected) {
        float[] out = new float[RATE];
        double phase = 0;
        float increment = (float) (hz / RATE);
        for (int frame = 0; frame < out.length; frame++) {
            out[frame] = corrected ? NovasawDsp.polyBlepSquare((float) phase, increment)
                    : (phase < 0.5 ? 1.0f : -1.0f);
            phase += increment;
            if (phase >= 1.0) {
                phase -= 1.0;
            }
        }
        return out;
    }

    private static double voiceLevel(SynthParams params) {
        var held = new AtomicReference<>(params);
        int frames = RATE / 3;
        NovasawVoice voice = new NovasawVoice(40, frames, RATE, held::get);
        double sum = 0;
        int counted = 0;
        for (int frame = 0; frame < frames; frame++) {
            float out = voice.next();
            if (frame > RATE / 10) {
                sum += out * out;
                counted++;
            }
        }
        return Math.sqrt(sum / counted);
    }

    @Test
    void theSquareIsBandLimited() {
        // a square at 5 kHz has its ninth harmonic at 45 kHz, which folds back to 3 kHz; nothing
        // the square is meant to contain lands there, so whatever is there is the fold
        double corrected = energyAt(square(5_000, true), 3_000);
        double naive = energyAt(square(5_000, false), 3_000);

        assertTrue(corrected < naive / 30, "the correction should take the fold down by 30 dB and more, but "
                + "took it from " + naive + " to " + corrected);
    }

    @Test
    void theSquareIsStillASquare() {
        float[] wave = square(110, true);
        double peak = 0;
        for (float sample : wave) {
            peak = Math.max(peak, Math.abs(sample));
        }

        assertEquals(1.0, peak, 0.01, "a square reaches one and no further");
        // a hundred and ten hertz at 48 kHz is 436 frames a cycle: high in the first half, low in the second
        assertTrue(wave[100] > 0.99, "high early in the cycle");
        assertTrue(wave[320] < -0.99, "and low late in it");
    }

    @Test
    void changingTheWaveformBarelyChangesTheLoudness() {
        // not exactly: a square is odd harmonics only, so a closed filter keeps less of it and an
        // open one keeps more. Two and a half decibels either way is the shape being a shape
        for (NovasawSynth patch : new NovasawSynth[] {
                new AcidBassSynth(), new SubBassSynth(), new AnthemLeadSynth(), new TrancePluckSynth()}) {
            double saw = voiceLevel(patch.params().withWaveform(Waveform.SAW));
            for (Waveform wave : Waveform.values()) {
                double off = 20 * Math.log10(voiceLevel(patch.params().withWaveform(wave)) / saw);
                assertTrue(Math.abs(off) < 3.0,
                        patch.getClass().getSimpleName() + " on " + wave + " was " + off + " dB off the saw");
            }
        }
    }

    @Test
    void aPatchArrivesOnTheSawItWasWrittenOn() {
        for (NovasawSynth patch : new NovasawSynth[] {new AcidBassSynth(), new AnthemLeadSynth()}) {
            assertEquals(Waveform.SAW, patch.params().waveform());
        }
        assertEquals(1.0f, Waveform.SAW.level(), "and the saw is not scaled at all");
        // the waveform is kept when anything else about the patch changes
        assertEquals(Waveform.SQUARE,
                new AcidBassSynth().params().withWaveform(Waveform.SQUARE).withCutoff(900).waveform());
    }
}

package pl.livecoding.musicjam.synth;

import org.junit.jupiter.api.Test;
import pl.livecoding.musicjam.audio.Sample;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the sound of the ported patches down to the sample, so that moving the render into
 * {@link NovasawVoice} — or letting a knob feed it — cannot quietly change what a patch sounds like.
 */
class NovasawSynthTest {

    private static final int SAMPLE_RATE = 44_100;

    @Test
    void anthemRendersTheSameSamplesAsItAlwaysHas() {
        Sample rendered = new AnthemLeadSynth().render(64, 11_025, SAMPLE_RATE);

        assertEquals(15_876, rendered.frameCount());
        assertEquals("15876 0.134694 0.037062 -0.004263 79.047081", fingerprint(rendered));
    }

    @Test
    void everyPatchKeepsItsOwnFingerprint() {
        assertEquals("8599 0.171009 -0.102297 0.000591 35.564352",
                fingerprint(new TrancePluckSynth().render(72, 5_512, SAMPLE_RATE)));
        assertEquals("52920 0.068558 0.117932 0.011762 201.642778",
                fingerprint(new WidePadSynth().render(48, 22_050, SAMPLE_RATE)));
        assertEquals("37485 -0.047769 -0.006545 0.000311 186.599356",
                fingerprint(new ChordsSynth().render(60, 22_050, SAMPLE_RATE)));
    }

    @Test
    void theSubFillsInTheOctaveBelowTheNote() {
        SynthParams dry = new SubBassSynth().params().withSub(0);
        SynthParams withSub = new SubBassSynth().params().withSub(0.8f);
        double noteHz = 440 * Math.pow(2, (40 - 69) / 12.0);

        double without = levelAt(NovasawSynth.render(40, SAMPLE_RATE, SAMPLE_RATE, dry), noteHz / 2);
        double with = levelAt(NovasawSynth.render(40, SAMPLE_RATE, SAMPLE_RATE, withSub), noteHz / 2);

        assertTrue(with > without * 3, "the sub should put real weight an octave down, not a hint");
    }

    @Test
    void theBassPatchesKeepTheirOwnFingerprints() {
        assertEquals("48069 -0.024709 -0.062270 0.002613 247.637361",
                fingerprint(new SubBassSynth().render(40, 44_100, SAMPLE_RATE)));
        assertEquals("47628 0.052664 -0.006201 0.000492 113.447781",
                fingerprint(new AcidBassSynth().render(45, 44_100, SAMPLE_RATE)));
    }

    /** How much of {@code hertz} a sample holds, by correlating it with that frequency. */
    private static double levelAt(Sample sample, double hertz) {
        float[] mono = sample.copyMono();
        double real = 0;
        double imaginary = 0;
        for (int frame = 0; frame < mono.length; frame++) {
            double angle = 2 * Math.PI * hertz * frame / SAMPLE_RATE;
            real += mono[frame] * Math.cos(angle);
            imaginary += mono[frame] * Math.sin(angle);
        }
        return Math.hypot(real, imaginary) / mono.length;
    }

    /** A few frames spread across the sample, plus its energy: enough to catch any change in the DSP. */
    private static String fingerprint(Sample sample) {
        float[] mono = sample.copyMono();
        double energy = 0;
        for (float value : mono) {
            energy += value * value;
        }
        return String.format(Locale.ROOT, "%d %.6f %.6f %.6f %.6f", mono.length,
                mono[mono.length / 8], mono[mono.length / 3], mono[mono.length - 1], energy);
    }
}

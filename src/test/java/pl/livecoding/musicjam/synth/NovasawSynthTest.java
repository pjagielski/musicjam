package pl.livecoding.musicjam.synth;

import org.junit.jupiter.api.Test;
import pl.livecoding.musicjam.audio.Sample;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        assertEquals("15876 0.265375 0.060857 -0.006843 349.231649", fingerprint(rendered));
    }

    @Test
    void everyPatchKeepsItsOwnFingerprint() {
        assertEquals("8599 0.169281 -0.115222 0.000365 42.624193",
                fingerprint(new TrancePluckSynth().render(72, 5_512, SAMPLE_RATE)));
        assertEquals("88200 0.004914 0.034132 -0.001887 29.678691",
                fingerprint(new WidePadSynth().render(48, 22_050, SAMPLE_RATE)));
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

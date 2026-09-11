package pl.livecoding.musicjam.audio;

import pl.livecoding.musicjam.model.Envelope;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class SampleTest {

    @Test
    void decayReachesAThousandthAtTheDecayTimeAndStopsThere() {
        Sample shaped = ones(100).shaped(new Envelope(0.01, 0.0), -1, 1_000);

        assertEquals(10, shaped.frameCount());
        assertEquals(1.0f, shaped.valueAt(0));
        assertEquals((float) Math.pow(1000, -0.5), shaped.valueAt(5), 1e-6f);
    }

    @Test
    void releaseFadesOutOnceTheNoteHasEnded() {
        Sample shaped = ones(100).shaped(new Envelope(0.0, 0.004), 5, 1_000);

        assertEquals(9, shaped.frameCount());
        assertEquals(1.0f, shaped.valueAt(5));
        assertEquals(0.5f, shaped.valueAt(7));
    }

    @Test
    void noEnvelopeLeavesTheSampleAlone() {
        Sample sample = ones(3);

        assertSame(sample, sample.shaped(Envelope.NONE, 1, 1_000));
    }

    private static Sample ones(int frames) {
        float[] data = new float[frames];
        Arrays.fill(data, 1.0f);
        return Sample.mono(data);
    }
}

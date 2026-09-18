package pl.livecoding.musicjam.audio;

import org.junit.jupiter.api.Test;
import pl.livecoding.musicjam.model.Drum;
import pl.livecoding.musicjam.model.Note;
import pl.livecoding.musicjam.model.Voice;
import pl.livecoding.musicjam.synth.PitchSynth;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Step 5's renderer: every hit on its exact frame, wherever the blocks fall. At 1000 frames a second
 * and 120 BPM a beat is 500 frames, and blocks of 128 frames put every hit below somewhere inside a
 * block rather than on its edge. Everything renders into memory; no sound card is involved.
 */
class AudioEngineTest {

    private static final SampleBank ONE_FRAME_KICK = SampleBank.from(Map.of(Drum.KICK, Sample.mono(1.0f)));

    @Test
    void refusesAMelodyNoteWithNoSynthToRenderIt() {
        var engine = new AudioEngine(ONE_FRAME_KICK, 1_000, 128, 8);

        assertThrows(IllegalArgumentException.class,
                () -> engine.render(List.of(new Note(0.0, new Voice.Pitch(60), 1.0, 1.0f)), 120, 4.0, 1));
    }

    @Test
    void rendersAMelodyNoteThroughTheSynthOnItsFrameForAsLongAsItLasts() {
        var engine = new AudioEngine(ONE_FRAME_KICK, 1_000, 128, 8);
        // a synth that holds one level for exactly as many frames as it is asked for
        PitchSynth flat = (midiNote, frameCount, sampleRate) -> {
            float[] tone = new float[frameCount];
            Arrays.fill(tone, midiNote / 100.0f);
            return Sample.mono(tone);
        };

        var audio = engine.render(List.of(new Note(1.0, new Voice.Pitch(50), 0.5, 1.0f)), 120, 4.0, 1, flat);

        assertEquals(0.0f, audio.sampleAt(499, 0));
        assertEquals(0.5f, audio.sampleAt(500, 0), "the note starts on the frame of its beat");
        assertEquals(0.5f, audio.sampleAt(749, 0));
        assertEquals(0.0f, audio.sampleAt(750, 0), "half a beat at 120 BPM is 250 frames");
    }

    @Test
    void startsAHitOnItsExactFrameInsideABlock() {
        var engine = new AudioEngine(ONE_FRAME_KICK, 1_000, 128, 8);

        var audio = engine.render(List.of(new Note(1.0, Drum.KICK, 1.0, 0.5f)), 120, 4.0, 1);

        assertEquals(0.0f, audio.sampleAt(499, 0), "frame 500 lies inside the block that starts at 384");
        assertEquals(0.5f, audio.sampleAt(500, 0));
        assertEquals(0.5f, audio.sampleAt(500, 1));
        assertEquals(0.0f, audio.sampleAt(501, 0));
    }

    @Test
    void startsEveryLoopOnePatternLengthLater() {
        var engine = new AudioEngine(ONE_FRAME_KICK, 1_000, 128, 8);

        var audio = engine.render(List.of(new Note(0.0, Drum.KICK, 1.0, 0.5f)), 120, 1.0, 3);

        assertEquals(1_500, audio.frameCount());
        assertEquals(0.5f, audio.sampleAt(0, 0));
        assertEquals(0.5f, audio.sampleAt(500, 0));
        assertEquals(0.0f, audio.sampleAt(999, 0));
        assertEquals(0.5f, audio.sampleAt(1_000, 0));
    }

    @Test
    void hitsOnTheSameFrameSoundTogether() {
        var samples = SampleBank.from(Map.of(Drum.KICK, Sample.mono(1.0f), Drum.SNARE, Sample.mono(1.0f)));
        var engine = new AudioEngine(samples, 1_000, 128, 8);

        var audio = engine.render(List.of(
                new Note(1.0, Drum.KICK, 1.0, 0.25f),
                new Note(1.0, Drum.SNARE, 1.0, 0.5f)), 120, 4.0, 1);

        assertEquals(0.75f, audio.sampleAt(500, 0));
        assertEquals(0.0f, audio.sampleAt(501, 0));
    }

    @Test
    void aStolenVoicePlaysUntilTheExactFrameOfItsReplacement() {
        float[] sustained = new float[1_000];
        Arrays.fill(sustained, 1.0f);
        var samples = SampleBank.from(Map.of(Drum.KICK, Sample.mono(sustained), Drum.SNARE, Sample.mono(sustained)));
        // a single voice, and at 600 BPM the snare comes 100 frames after the kick, in the same block
        var engine = new AudioEngine(samples, 1_000, 128, 1);

        var audio = engine.render(List.of(
                new Note(0.0, Drum.KICK, 1.0, 0.25f),
                new Note(1.0, Drum.SNARE, 1.0, 0.75f)), 600, 4.0, 1);

        assertEquals(0.25f, audio.sampleAt(99, 0));
        assertEquals(0.75f, audio.sampleAt(100, 0));
    }
}

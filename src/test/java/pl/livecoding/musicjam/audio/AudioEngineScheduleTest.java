package pl.livecoding.musicjam.audio;

import org.junit.jupiter.api.Test;
import pl.livecoding.musicjam.model.Drum;
import pl.livecoding.musicjam.model.Note;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Step 5's exercise: notes into hits on frames - the schedule of step 3 again, counted in frames
 * instead of nanoseconds. At 120 BPM and 44100 frames a second one beat is 22050 frames, which is
 * what every expected number below is counted in. Nothing here renders or plays.
 */
class AudioEngineScheduleTest {

    private static final double BPM = 120.0;
    private static final int RATE = 44_100;
    private static final long BEAT = 22_050;

    @Test
    void aNoteBecomesAHitOnTheFrameOfItsBeat() {
        Note snare = new Note(1.0, Drum.SNARE, 0.25, 0.5f);

        List<AudioEngine.Hit> hits = AudioEngine.schedule(List.of(snare), BPM, 4.0, 1, RATE);

        assertEquals(List.of(new AudioEngine.Hit(BEAT, snare)), hits);
    }

    @Test
    void everyLoopStartsOnePatternLengthAfterTheOneBefore() {
        List<AudioEngine.Hit> hits =
                AudioEngine.schedule(List.of(new Note(0.0, Drum.KICK, 0.25, 1.0f)), BPM, 2.0, 3, RATE);

        assertEquals(List.of(0L, 2 * BEAT, 4 * BEAT), frames(hits));
    }

    @Test
    void everyFrameIsCountedFromTheStartSoRoundingCannotAddUp() {
        // a sixteenth at 120 BPM is 5512.5 frames; adding a rounded sixteenth hit after hit would drift
        List<Note> sixteenths = List.of(new Note(0.0, Drum.CLOSED_HAT, 0.25, 1.0f),
                new Note(0.25, Drum.CLOSED_HAT, 0.25, 1.0f));

        List<AudioEngine.Hit> hits = AudioEngine.schedule(sixteenths, BPM, 0.5, 8, RATE);

        assertEquals(16, hits.size());
        assertEquals(82_688, frames(hits).getLast(), "beat 3.75 is frame 82687.5, rounded once");
    }

    @Test
    void refusesToPlayNothing() {
        assertThrows(IllegalArgumentException.class,
                () -> AudioEngine.schedule(List.of(new Note(0.0, Drum.KICK, 1.0, 1.0f)), BPM, 4.0, 0, RATE));
    }

    private static List<Long> frames(List<AudioEngine.Hit> hits) {
        return hits.stream().map(AudioEngine.Hit::frame).sorted().toList();
    }
}

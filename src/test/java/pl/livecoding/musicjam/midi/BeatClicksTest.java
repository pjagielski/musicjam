package pl.livecoding.musicjam.midi;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The calibration helper of step 5, green from the start. The arithmetic with plain numbers - beats of
 * 500 ms - and then a few beats against "audio" that is nothing but the clock.
 */
class BeatClicksTest {

    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);

    @Test
    void theFirstBeatIsTheOneAfterWhatIsHeardNow() {
        assertEquals(1, BeatClicks.nextBeat(0, 0, 500 * MS, -1));
        assertEquals(3, BeatClicks.nextBeat(1_200 * MS, 0, 500 * MS, -1));
    }

    @Test
    void aBeatDueSoonerThanTheLatencyIsTooLateToSend() {
        // beat 2 is heard at 1000 ms, so with 75 ms of latency it should have gone out at 925 ms
        assertEquals(3, BeatClicks.nextBeat(980 * MS, 75 * MS, 500 * MS, -1));
        assertEquals(2, BeatClicks.nextBeat(900 * MS, 75 * MS, 500 * MS, -1));
    }

    @Test
    void neverSendsABeatTwiceWhenTheLatencyShrinks() {
        assertEquals(4, BeatClicks.nextBeat(1_000 * MS, 0, 500 * MS, 3));
    }

    @Test
    void sendsEveryNoteTheLatencyEarlyAndReleasesIt() throws Exception {
        var output = new RecordingOutput();
        var clicks = new BeatClicks(output);
        long audioStart = System.nanoTime();
        // 600 BPM: a beat every 100 ms
        Thread player = Thread.ofPlatform().start(() -> {
            try {
                clicks.play(600, () -> System.nanoTime() - audioStart, () -> 30 * MS);
            } catch (InterruptedException stopped) {
                // how play() ends
            }
        });
        Thread.sleep(360);
        player.interrupt();
        player.join();

        List<Long> noteOns = output.noteOnsAfter(audioStart);
        assertTrue(noteOns.size() >= 3, "notes sent: " + noteOns);
        for (int i = 0; i < noteOns.size(); i++) {
            long expected = (i + 1) * 100 * MS - 30 * MS;
            assertEquals(expected, noteOns.get(i), 20 * MS, "note " + i + " sent at " + noteOns.get(i) / MS + " ms");
        }
        assertEquals(output.noteOns.size(), output.noteOffs, "every note released");
    }

    private static final class RecordingOutput implements NoteOutput {
        private final List<Long> noteOns = new ArrayList<>();
        private int noteOffs;

        @Override
        public synchronized void noteOn(int pitch, int velocity) {
            assertEquals(BeatClicks.PITCH, pitch);
            noteOns.add(System.nanoTime());
        }

        @Override
        public synchronized void noteOff(int pitch) {
            noteOffs++;
        }

        synchronized List<Long> noteOnsAfter(long startNanos) {
            return noteOns.stream().map(at -> at - startNanos).toList();
        }
    }
}

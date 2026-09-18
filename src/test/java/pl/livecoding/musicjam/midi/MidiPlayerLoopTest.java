package pl.livecoding.musicjam.midi;

import org.junit.jupiter.api.Test;
import pl.livecoding.musicjam.model.Note;
import pl.livecoding.musicjam.model.Voice;
import pl.livecoding.musicjam.scheduler.SchedulerKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Step 5's second exercise: a melody that starts every loop from how much of the drums has been
 * heard. The arithmetic comes finished, so its four tests are green from the start; the last test
 * plays three loops through playLoop against "audio" that falls 100 ms behind.
 */
class MidiPlayerLoopTest {

    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);

    @Test
    void theFirstLoopStartsWhenTheAudioStarted() {
        // 10 s on the clock and 2 s of drums heard: the drums started at 8 s
        assertEquals(8_000 * MS, MidiPlayer.loopStartNanos(10_000 * MS, 2_000 * MS, 0, 1_000 * MS, 0));
    }

    @Test
    void everyLaterLoopStartsThatManyLoopsAfterTheAudioStarted() {
        assertEquals(10_000 * MS, MidiPlayer.loopStartNanos(10_000 * MS, 2_000 * MS, 2, 1_000 * MS, 0));
    }

    @Test
    void audioThatStalledStartsTheLoopLater() {
        // the same 10 s, but a stall means only 1.7 s have been heard: the drums started at 8.3 s
        assertEquals(9_300 * MS, MidiPlayer.loopStartNanos(10_000 * MS, 1_700 * MS, 1, 1_000 * MS, 0));
    }

    @Test
    void notesGoOutEarlyByTheSynthsLatency() {
        assertEquals(7_963 * MS, MidiPlayer.loopStartNanos(10_000 * MS, 2_000 * MS, 0, 1_000 * MS, 37 * MS));
    }

    @Test
    void catchesUpWithStalledAudioAtTheNextLoop() throws InterruptedException {
        var noteOns = Collections.synchronizedList(new ArrayList<Long>());
        var audioLag = new AtomicLong();
        NoteOutput output = new NoteOutput() {
            @Override
            public void noteOn(int pitch, int velocity) {
                noteOns.add(System.nanoTime());
                // the audio stalls for 100 ms during the first loop, and stays that far behind
                audioLag.set(100 * MS);
            }

            @Override
            public void noteOff(int pitch) {
            }
        };
        var notes = List.of(new Note(0.0, new Voice.Pitch(60), 0.5, 100 / 127f));
        long started = System.nanoTime();

        try (var player = MidiPlayer.forOutput(output, SchedulerKind.PLATFORM, 0)) {
            // 300 BPM: a one-beat loop lasts 200 ms
            player.playLoop(notes, 300, 1.0, 3, () -> System.nanoTime() - started - audioLag.get(), 0);
        }

        assertEquals(3, noteOns.size());
        assertEquals(300, (noteOns.get(1) - noteOns.get(0)) / 1e6, 40, "the loop after the stall starts later");
        assertEquals(200, (noteOns.get(2) - noteOns.get(1)) / 1e6, 40, "and then keeps the loop length again");
    }
}

package pl.livecoding.musicjam.midi;

import pl.livecoding.musicjam.model.Note;
import pl.livecoding.musicjam.model.Voice;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MidiPlayerTest {

    @Test
    void loopsThePatternFiringNoteOnBeforeTheMatchingNoteOff() throws InterruptedException {
        var played = Collections.synchronizedList(new ArrayList<String>());
        NoteOutput output = new NoteOutput() {
            @Override
            public void noteOn(int pitch, int velocity) {
                played.add("ON " + pitch + " " + velocity);
            }

            @Override
            public void noteOff(int pitch) {
                played.add("OFF " + pitch);
            }
        };
        var notes = List.of(
                new Note(0.0, new Voice.Pitch(60), 0.5, 100 / 127f),
                new Note(1.0, new Voice.Pitch(64), 0.5, 90 / 127f)
        );

        try (var player = MidiPlayer.forOutput(output)) {
            player.playLoop(notes, 1_000_000, 2.0, 2);
        }

        assertEquals(List.of(
                "ON 60 100", "OFF 60",
                "ON 64 90", "OFF 64",
                "ON 60 100", "OFF 60",
                "ON 64 90", "OFF 64"
        ), played);
    }

    @Test
    void startsEveryLoopWhereTheAudioItFollowsHasGotTo() throws InterruptedException {
        var noteOns = Collections.synchronizedList(new ArrayList<Long>());
        var audioLag = new AtomicLong();
        NoteOutput output = new NoteOutput() {
            @Override
            public void noteOn(int pitch, int velocity) {
                noteOns.add(System.nanoTime());
                // the audio stalls for 100 ms during the first loop, and stays that far behind
                audioLag.set(TimeUnit.MILLISECONDS.toNanos(100));
            }

            @Override
            public void noteOff(int pitch) {
            }
        };
        var notes = List.of(new Note(0.0, new Voice.Pitch(60), 0.5, 100 / 127f));
        long started = System.nanoTime();

        try (var player = MidiPlayer.forOutput(output)) {
            // 300 BPM: a one-beat loop lasts 200 ms
            player.playLoop(notes, 300, 1.0, 3, () -> System.nanoTime() - started - audioLag.get(), 0L);
        }

        assertEquals(300, (noteOns.get(1) - noteOns.get(0)) / 1e6, 40);
        assertEquals(200, (noteOns.get(2) - noteOns.get(1)) / 1e6, 40);
    }

    @Test
    void sendsNoteOffForNotesStillSoundingWhenInterrupted() throws Exception {
        var released = Collections.synchronizedList(new ArrayList<Integer>());
        NoteOutput output = new NoteOutput() {
            @Override
            public void noteOn(int pitch, int velocity) {
            }

            @Override
            public void noteOff(int pitch) {
                released.add(pitch);
            }
        };
        var notes = List.of(new Note(0.0, new Voice.Pitch(60), 1000.0, 100 / 127f));
        var player = MidiPlayer.forOutput(output);

        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                player.playLoop(notes, 60, 2000.0, 1);
            } catch (InterruptedException ignored) {
                // Expected: interrupted while the note-off event was still far in the future.
            }
        });

        Thread.sleep(50);
        caller.interrupt();
        caller.join(TimeUnit.SECONDS.toMillis(2));

        assertEquals(List.of(60), released);
    }
}

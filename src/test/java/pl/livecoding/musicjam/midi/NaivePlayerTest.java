package pl.livecoding.musicjam.midi;

import pl.livecoding.musicjam.model.Drum;
import pl.livecoding.musicjam.model.Song;
import pl.livecoding.musicjam.model.DrumTrack;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NaivePlayerTest {

    @ParameterizedTest
    @EnumSource(NaivePlayer.ThreadKind.class)
    void schedulesEveryHitAndReportsObservedLateness(NaivePlayer.ThreadKind threadKind)
            throws Exception {
        var played = Collections.synchronizedList(new ArrayList<PlayedNote>());
        var song = new Song(1_000_000, 4, List.of(
                new DrumTrack(Drum.KICK, "X.o.", 1.0f)
        ));
        NoteOutput output = new NoteOutput() {
            @Override
            public void noteOn(int pitch, int velocity) {
                played.add(new PlayedNote(pitch, velocity));
            }

            @Override
            public void noteOff(int pitch) {
            }
        };

        try (var player = NaivePlayer.forOutput(output, 0L, threadKind)) {
            TimingReport report = player.play(song, 2);

            assertEquals(4, played.size());
            assertTrue(played.stream().allMatch(note -> note.pitch() == Drum.KICK.gmPercussionNote()));
            assertEquals(2, played.stream().filter(note -> note.velocity() == 127).count());
            assertEquals(2, played.stream().filter(note -> note.velocity() == 64).count());
            assertEquals(threadKind, report.threadKind());
            assertEquals(4, report.events());
            assertTrue(report.setupMicros() >= 0);
            assertTrue(report.minMicros() <= report.averageMicros());
            assertTrue(report.averageMicros() <= report.maxMicros());
        }
    }

    @org.junit.jupiter.api.Test
    void interruptedPlaybackWaitsForAnInFlightHitToFinish() throws Exception {
        var hitEntered = new CountDownLatch(1);
        var releaseHit = new CountDownLatch(1);
        NoteOutput blockingOutput = new NoteOutput() {
            @Override
            public void noteOn(int pitch, int velocity) {
                hitEntered.countDown();
                boolean released = false;
                while (!released) {
                    try {
                        releaseHit.await();
                        released = true;
                    } catch (InterruptedException ignored) {
                        // Simulates an output call which cannot be cancelled immediately.
                    }
                }
            }

            @Override
            public void noteOff(int pitch) {
            }
        };
        var song = new Song(120, 4, List.of(
                new DrumTrack(Drum.KICK, "X...", 1.0f)
        ));
        var failure = new AtomicReference<Throwable>();
        var player = NaivePlayer.forOutput(blockingOutput, 0L, NaivePlayer.ThreadKind.VIRTUAL);
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                player.play(song, 1);
            } catch (InterruptedException expected) {
                // Expected path exercised by this test.
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        try {
            hitEntered.await();
            caller.interrupt();
            Thread.sleep(50);

            assertTrue(caller.isAlive(), "play() returned before its output worker finished");
        } finally {
            releaseHit.countDown();
            caller.join(TimeUnit.SECONDS.toMillis(2));
            player.close();
        }
        assertFalse(caller.isAlive(), "play() did not finish after the in-flight hit was released");
        assertEquals(null, failure.get());
    }

    private record PlayedNote(int pitch, int velocity) {
    }
}

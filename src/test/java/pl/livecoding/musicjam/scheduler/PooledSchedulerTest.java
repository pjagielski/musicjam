package pl.livecoding.musicjam.scheduler;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The second half of step 3's exercise: handing the events to a pool. The pool wants a delay from
 * now, the event has an instant to hit — and the task has to be told which instant it was, because
 * that is what its lateness gets measured against.
 */
class PooledSchedulerTest {

    private static final long STARTUP = TimeUnit.MILLISECONDS.toNanos(20);

    @Test
    void everyTaskIsToldTheTargetItWasSubmittedFor() throws Exception {
        long[] offsets = {0, TimeUnit.MILLISECONDS.toNanos(40), TimeUnit.MILLISECONDS.toNanos(80)};
        List<long[]> runs = record(offsets);

        assertEquals(offsets.length, runs.size(), "every event has to run before awaitDone returns");
        runs.sort(Comparator.comparingLong(run -> run[0]));
        for (int i = 0; i < offsets.length; i++) {
            assertEquals(offsets[i] - offsets[0], runs.get(i)[0] - runs.get(0)[0],
                    "targets have to be one zero point plus each event's own offset");
        }
    }

    @Test
    void noTaskRunsBeforeTheTargetItWasSubmittedFor() throws Exception {
        long[] offsets = {0, TimeUnit.MILLISECONDS.toNanos(40), TimeUnit.MILLISECONDS.toNanos(80)};

        for (long[] run : record(offsets)) {
            long lateNanos = run[1] - run[0];
            assertTrue(lateNanos > -TimeUnit.MILLISECONDS.toNanos(2),
                    "woke up " + TimeUnit.NANOSECONDS.toMicros(-lateNanos) + " us early");
            assertTrue(lateNanos < TimeUnit.MILLISECONDS.toNanos(500),
                    "woke up " + TimeUnit.NANOSECONDS.toMillis(lateNanos) + " ms late");
        }
    }

    @Test
    void reportsFailureFromAScheduledTask() {
        try (PooledScheduler scheduler = new PooledScheduler(1)) {
            scheduler.begin(0);
            scheduler.submit(0, target -> {
                throw new UnsupportedOperationException("MidiNoteOutput.noteOn");
            });
            scheduler.submit(TimeUnit.SECONDS.toNanos(3), target -> {});

            UnsupportedOperationException failure = assertTimeout(Duration.ofMillis(500), () ->
                    assertThrows(UnsupportedOperationException.class, scheduler::awaitDone));
            assertEquals("MidiNoteOutput.noteOn", failure.getMessage());
        }
    }

    /** Runs the offsets through a pool and returns a {target, when it actually ran} pair per event. */
    private static List<long[]> record(long[] offsets) throws InterruptedException {
        List<long[]> runs = Collections.synchronizedList(new ArrayList<>());
        try (PooledScheduler scheduler = new PooledScheduler(2)) {
            scheduler.begin(STARTUP);
            for (long offset : offsets) {
                scheduler.submit(offset, target -> runs.add(new long[] {target, System.nanoTime()}));
            }
            scheduler.awaitDone();
        }
        return runs;
    }
}

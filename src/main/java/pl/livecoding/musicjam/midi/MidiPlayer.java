package pl.livecoding.musicjam.midi;

import pl.livecoding.musicjam.model.Note;
import pl.livecoding.musicjam.scheduler.EventScheduler;
import pl.livecoding.musicjam.scheduler.SchedulerKind;

import javax.sound.midi.MidiUnavailableException;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.concurrent.TimeUnit;

public final class MidiPlayer implements AutoCloseable {
    static final long DEFAULT_STARTUP_DELAY_NANOS = TimeUnit.MILLISECONDS.toNanos(250);

    private final NoteOutput output;
    private final SchedulerKind scheduler;
    private final long startupDelayNanos;
    private boolean closed;

    private MidiPlayer(NoteOutput output, SchedulerKind scheduler, long startupDelayNanos) {
        this.output = output;
        this.scheduler = scheduler;
        this.startupDelayNanos = startupDelayNanos;
    }

    public static MidiPlayer openMidi(int channel, int program, SchedulerKind scheduler)
            throws MidiUnavailableException {
        return new MidiPlayer(
                MidiNoteOutput.open(channel, program), scheduler, DEFAULT_STARTUP_DELAY_NANOS);
    }

    static MidiPlayer forOutput(NoteOutput output, SchedulerKind scheduler, long startupDelayNanos) {
        return new MidiPlayer(output, scheduler, startupDelayNanos);
    }

    public TimingReport play(List<Note> notes, double bpm, double patternLengthBeats, int loops)
            throws InterruptedException {
        if (closed) {
            throw new IllegalStateException("Player is closed");
        }
        List<ScheduledEvent> events = schedule(notes, bpm, patternLengthBeats, loops);
        Lateness lateness = new Lateness();

        long setupStarted = System.nanoTime();
        try (EventScheduler dispatcher = scheduler.newScheduler()) {
            if (events.isEmpty()) {
                return TimingReport.empty(dispatcher.label());
            }
            dispatcher.begin(startupDelayNanos);
            for (ScheduledEvent event : events) {
                dispatcher.submit(event.offsetNanos(), targetNanos -> {
                    lateness.wokeAt(targetNanos);
                    event.fire(output);
                });
            }
            long setupMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - setupStarted);
            dispatcher.awaitDone();
            return lateness.report(dispatcher.label(), setupMicros);
        }
    }

    static List<ScheduledEvent> schedule(
            List<Note> notes, double bpm, double patternLengthBeats, int loops) {
        if (loops <= 0) {
            throw new IllegalArgumentException("Loops must be positive");
        }
        // TODO(step-3): expand the notes into the events to fire: a note-on at note.beat() and a
        // TODO(step-3): note-off at note.beat() + note.durationBeats(), for every loop, with loop
        // TODO(step-3): number i starting at i * patternLengthBeats. Offsets are nanoseconds from
        // TODO(step-3): the start of playback, so every beat goes through beatToNanos.
        throw new UnsupportedOperationException("MidiPlayer.schedule");
    }

    static long beatToNanos(double beat, double bpm) {
        return Math.round(beat * 60_000_000_000.0 / bpm);
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            output.close();
        }
    }

    record ScheduledEvent(long offsetNanos, Type type, int pitch, int velocity) {

        enum Type {
            NOTE_ON,
            NOTE_OFF
        }

        static ScheduledEvent noteOn(long offsetNanos, int pitch, int velocity) {
            return new ScheduledEvent(offsetNanos, Type.NOTE_ON, pitch, velocity);
        }

        static ScheduledEvent noteOff(long offsetNanos, int pitch) {
            return new ScheduledEvent(offsetNanos, Type.NOTE_OFF, pitch, 0);
        }

        void fire(NoteOutput output) {
            switch (type) {
                case NOTE_ON -> output.noteOn(pitch, velocity);
                case NOTE_OFF -> output.noteOff(pitch);
            }
        }
    }

    private static final class Lateness {
        private final LongSummaryStatistics micros = new LongSummaryStatistics();

        void wokeAt(long targetNanos) {
            long lateNanos = Math.max(0L, System.nanoTime() - targetNanos);
            synchronized (micros) {
                micros.accept(TimeUnit.NANOSECONDS.toMicros(lateNanos));
            }
        }

        TimingReport report(String label, long setupMicros) {
            synchronized (micros) {
                return new TimingReport(
                        label,
                        micros.getCount(),
                        setupMicros,
                        micros.getMin(),
                        micros.getAverage(),
                        micros.getMax());
            }
        }
    }
}

package pl.livecoding.musicjam.midi;

import pl.livecoding.musicjam.model.Drum;
import pl.livecoding.musicjam.model.Note;
import pl.livecoding.musicjam.model.PatternCompiler;
import pl.livecoding.musicjam.model.Song;
import pl.livecoding.musicjam.model.Voice;

import javax.sound.midi.MidiUnavailableException;
import java.util.ArrayList;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class NaivePlayer implements AutoCloseable {
    private static final long DEFAULT_STARTUP_DELAY_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    private static final int PERCUSSION_CHANNEL = 9;

    private final NoteOutput output;
    private final long startupDelayNanos;
    private final ThreadKind threadKind;
    private boolean closed;

    private NaivePlayer(NoteOutput output, long startupDelayNanos, ThreadKind threadKind) {
        this.output = output;
        this.startupDelayNanos = startupDelayNanos;
        this.threadKind = threadKind;
    }

    public static NaivePlayer openMidi() throws MidiUnavailableException {
        return openMidi(ThreadKind.PLATFORM);
    }

    public static NaivePlayer openMidi(ThreadKind threadKind) throws MidiUnavailableException {
        return openMidi(threadKind, PERCUSSION_CHANNEL, 0);
    }

    public static NaivePlayer openMidi(ThreadKind threadKind, int channel, int program)
            throws MidiUnavailableException {
        return new NaivePlayer(MidiNoteOutput.open(channel, program), DEFAULT_STARTUP_DELAY_NANOS, threadKind);
    }

    static NaivePlayer forOutput(
            NoteOutput output,
            long startupDelayNanos,
            ThreadKind threadKind
    ) {
        return new NaivePlayer(output, startupDelayNanos, threadKind);
    }

    public TimingReport play(Song song, int loops) throws InterruptedException {
        return play(PatternCompiler.compile(song), song.bpm(), PatternCompiler.totalBeats(song), loops);
    }

    /**
     * One waiting thread per note: the naive, per-event scheduling this class exists to
     * demonstrate. Works on notes compiled from a pattern (all {@link Drum} voices) just as well
     * as notes read from a MIDI file (all {@link Voice.Pitch} voices), or a mix of both.
     */
    public TimingReport play(List<Note> notes, double bpm, double patternLengthBeats, int loops)
            throws InterruptedException {
        if (closed) {
            throw new IllegalStateException("Player is closed");
        }
        if (loops <= 0) {
            throw new IllegalArgumentException("Loops must be positive");
        }

        int eventCount = Math.multiplyExact(notes.size(), loops);
        if (eventCount == 0) {
            return TimingReport.empty(threadKind);
        }

        var workers = new ArrayList<Thread>(eventCount);
        var ready = new CountDownLatch(eventCount);
        var startGate = new CountDownLatch(1);
        var finished = new CountDownLatch(eventCount);
        var latenessMicros = new LongSummaryStatistics();
        var failure = new AtomicReference<RuntimeException>();
        var startNanos = new AtomicLong();
        long setupStarted = System.nanoTime();

        try {
            for (int loop = 0; loop < loops; loop++) {
                for (Note note : notes) {
                    int midiNote = midiNoteOf(note.voice());
                    double absoluteBeat = loop * patternLengthBeats + note.beat();
                    long offsetNanos = beatToNanos(absoluteBeat, bpm);
                    int workerNumber = workers.size();

                    Thread worker = threadKind.unstarted(() -> {
                        try {
                            ready.countDown();
                            startGate.await();
                            long targetNanos = startNanos.get() + offsetNanos;
                            sleepUntil(targetNanos);
                            long late = Math.max(0L, System.nanoTime() - targetNanos);
                            synchronized (latenessMicros) {
                                latenessMicros.accept(TimeUnit.NANOSECONDS.toMicros(late));
                            }
                            output.noteOn(midiNote, Math.round(note.velocity() * 127.0f));
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            failure.compareAndSet(null,
                                    new IllegalStateException("Playback worker interrupted", exception));
                        } catch (RuntimeException exception) {
                            failure.compareAndSet(null, exception);
                        } finally {
                            finished.countDown();
                        }
                    }, workerNumber);
                    workers.add(worker);
                    worker.start();
                }
            }

            ready.await();
            long setupMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - setupStarted);
            startNanos.set(System.nanoTime() + startupDelayNanos);
            startGate.countDown();
            finished.await();
            for (Thread worker : workers) {
                worker.join();
            }
            RuntimeException playbackFailure = failure.get();
            if (playbackFailure != null) {
                throw playbackFailure;
            }
            return new TimingReport(
                    threadKind,
                    latenessMicros.getCount(),
                    setupMicros,
                    latenessMicros.getMin(),
                    latenessMicros.getAverage(),
                    latenessMicros.getMax()
            );
        } finally {
            startGate.countDown();
            stopAndJoin(workers);
        }
    }

    private static int midiNoteOf(Voice voice) {
        return switch (voice) {
            case Drum drum -> drum.gmPercussionNote();
            case Voice.Pitch pitch -> pitch.midiNote();
        };
    }

    private static long beatToNanos(double beat, double bpm) {
        return Math.round(beat * 60_000_000_000.0 / bpm);
    }

    private static void sleepUntil(long targetNanos) throws InterruptedException {
        long remaining;
        while ((remaining = targetNanos - System.nanoTime()) > 0L) {
            TimeUnit.NANOSECONDS.sleep(remaining);
        }
    }

    private static void stopAndJoin(ArrayList<Thread> workers) {
        boolean restoreInterrupt = Thread.interrupted();
        for (Thread worker : workers) {
            if (worker.isAlive()) {
                worker.interrupt();
            }
        }
        for (Thread worker : workers) {
            while (worker.isAlive()) {
                try {
                    worker.join();
                } catch (InterruptedException exception) {
                    restoreInterrupt = true;
                    worker.interrupt();
                }
            }
        }
        if (restoreInterrupt) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            output.close();
        }
    }

    public enum ThreadKind {
        PLATFORM,
        VIRTUAL;

        private Thread unstarted(Runnable task, int number) {
            return switch (this) {
                case PLATFORM -> Thread.ofPlatform()
                        .daemon(true)
                        .name("naive-platform-" + number)
                        .unstarted(task);
                case VIRTUAL -> Thread.ofVirtual()
                        .name("naive-virtual-" + number)
                        .unstarted(task);
            };
        }
    }
}

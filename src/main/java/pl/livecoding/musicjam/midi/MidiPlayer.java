package pl.livecoding.musicjam.midi;

import pl.livecoding.musicjam.model.Drum;
import pl.livecoding.musicjam.model.Note;
import pl.livecoding.musicjam.model.Voice;

import javax.sound.midi.MidiUnavailableException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

public final class MidiPlayer implements AutoCloseable {

    private final NoteOutput output;
    private boolean closed;

    private MidiPlayer(NoteOutput output) {
        this.output = output;
    }

    public static MidiPlayer openMidi(int channel, int program) throws MidiUnavailableException {
        return new MidiPlayer(MidiNoteOutput.open(channel, program));
    }

    public static MidiPlayer openDevice(String deviceNameContains, int channel, int program)
            throws MidiUnavailableException {
        return new MidiPlayer(ExternalMidiOutput.open(deviceNameContains, channel, program));
    }

    static MidiPlayer forOutput(NoteOutput output) {
        return new MidiPlayer(output);
    }

    public void playLoop(
            List<Note> notes,
            double bpm,
            double patternLengthBeats,
            int loops
    ) throws InterruptedException {
        long startNanos = System.nanoTime();
        playLoop(notes, bpm, patternLengthBeats, loops, () -> System.nanoTime() - startNanos, 0L);
    }

    /**
     * Plays in time with audio playing somewhere else. At the start of every loop it asks
     * {@code heardNanos} how much of that audio has been heard, works out from that when the audio
     * started, and schedules the loop's notes from there - {@code latencyNanos} early, for the synth
     * to sound them. Audio that stalls or starts late is caught up with at the next loop.
     */
    public void playLoop(
            List<Note> notes,
            double bpm,
            double patternLengthBeats,
            int loops,
            LongSupplier heardNanos,
            long latencyNanos
    ) throws InterruptedException {
        if (closed) {
            throw new IllegalStateException("Player is closed");
        }
        if (loops <= 0) {
            throw new IllegalArgumentException("Loops must be positive");
        }
        if (notes.isEmpty()) {
            return;
        }

        List<Event> oneLoop = buildLoop(notes, bpm, patternLengthBeats);
        long loopNanos = beatToNanos(patternLengthBeats, bpm);
        Set<Integer> activePitches = new HashSet<>();
        try {
            for (int loop = 0; loop < loops; loop++) {
                long audioStartNanos = System.nanoTime() - heardNanos.getAsLong();
                long loopStartNanos = audioStartNanos + loop * loopNanos - latencyNanos;
                for (Event event : oneLoop) {
                    sleepUntil(loopStartNanos + event.atNanos());
                    switch (event.type()) {
                        case ON -> {
                            output.noteOn(event.pitch(), event.velocity());
                            activePitches.add(event.pitch());
                        }
                        case OFF -> {
                            output.noteOff(event.pitch());
                            activePitches.remove(event.pitch());
                        }
                    }
                }
            }
        } finally {
            for (int pitch : activePitches) {
                output.noteOff(pitch);
            }
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            output.close();
        }
    }

    /** One loop's events. A note still sounding when the loop ends is released there. */
    private static List<Event> buildLoop(List<Note> notes, double bpm, double patternLengthBeats) {
        var events = new ArrayList<Event>(notes.size() * 2);
        for (Note note : notes) {
            int midiNote = midiNoteOf(note.voice());
            int velocity127 = Math.round(note.velocity() * 127.0f);
            double offBeat = Math.min(note.beat() + note.durationBeats(), patternLengthBeats);
            events.add(new Event(beatToNanos(note.beat(), bpm), EventType.ON, midiNote, velocity127));
            events.add(new Event(beatToNanos(offBeat, bpm), EventType.OFF, midiNote, 0));
        }
        events.sort(
                Comparator.comparingLong(Event::atNanos)
                        .thenComparing(Event::type)
        );
        return events;
    }

    private static int midiNoteOf(Voice voice) {
        return switch (voice) {
            case Voice.Pitch pitch -> pitch.midiNote();
            case Drum drum -> drum.gmPercussionNote();
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

    private enum EventType {
        OFF,
        ON
    }

    private record Event(long atNanos, EventType type, int pitch, int velocity) {
    }
}

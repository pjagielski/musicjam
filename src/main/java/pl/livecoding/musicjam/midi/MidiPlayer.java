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
        if (closed) {
            throw new IllegalStateException("Player is closed");
        }
        if (loops <= 0) {
            throw new IllegalArgumentException("Loops must be positive");
        }
        if (notes.isEmpty()) {
            return;
        }

        List<Event> timeline = buildTimeline(notes, bpm, patternLengthBeats, loops);
        Set<Integer> activePitches = new HashSet<>();
        long startNanos = System.nanoTime();
        try {
            for (Event event : timeline) {
                sleepUntil(startNanos + event.atNanos());
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

    private static List<Event> buildTimeline(
            List<Note> notes,
            double bpm,
            double patternLengthBeats,
            int loops
    ) {
        var timeline = new ArrayList<Event>(notes.size() * loops * 2);
        for (int loop = 0; loop < loops; loop++) {
            double loopOffsetBeats = loop * patternLengthBeats;
            for (Note note : notes) {
                int midiNote = midiNoteOf(note.voice());
                int velocity127 = Math.round(note.velocity() * 127.0f);
                long onNanos = beatToNanos(loopOffsetBeats + note.beat(), bpm);
                long offNanos = beatToNanos(
                        loopOffsetBeats + note.beat() + note.durationBeats(), bpm);
                timeline.add(new Event(onNanos, EventType.ON, midiNote, velocity127));
                timeline.add(new Event(offNanos, EventType.OFF, midiNote, 0));
            }
        }
        timeline.sort(
                Comparator.comparingLong(Event::atNanos)
                        .thenComparing(Event::type)
        );
        return timeline;
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

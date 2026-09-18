package pl.livecoding.musicjam.midi;

import org.junit.jupiter.api.Test;
import pl.livecoding.musicjam.model.Note;
import pl.livecoding.musicjam.model.Voice;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The first half of step 3's exercise: turning notes into the events a scheduler fires. At 120 BPM
 * one beat is half a second, which is what every expected number below is counted in. Nothing here
 * opens a synthesizer or waits for a clock.
 */
class MidiPlayerTest {

    private static final double BPM = 120.0;
    private static final long BEAT = TimeUnit.MILLISECONDS.toNanos(500);

    @Test
    void aNoteBecomesANoteOnAtItsBeatAndANoteOffWhereItEnds() {
        List<MidiPlayer.ScheduledEvent> events =
                MidiPlayer.schedule(List.of(note(1.0, 60, 0.5)), BPM, 4.0, 1);

        assertEquals(2, events.size());
        MidiPlayer.ScheduledEvent on = eventAt(events, BEAT);
        MidiPlayer.ScheduledEvent off = eventAt(events, BEAT + BEAT / 2);
        assertEquals(MidiPlayer.ScheduledEvent.Type.NOTE_ON, on.type());
        assertEquals(MidiPlayer.ScheduledEvent.Type.NOTE_OFF, off.type());
        assertEquals(60, on.pitch());
        assertEquals(60, off.pitch());
    }

    @Test
    void velocityBecomesAMidiValueBetween0And127() {
        List<MidiPlayer.ScheduledEvent> events =
                MidiPlayer.schedule(List.of(note(0.0, 60, 1.0, 0.8f)), BPM, 4.0, 1);

        assertEquals(102, eventAt(events, 0).velocity(), "0.8 of 127, rounded");
    }

    @Test
    void everyLoopStartsOnePatternLengthAfterTheOneBefore() {
        List<MidiPlayer.ScheduledEvent> events =
                MidiPlayer.schedule(List.of(note(0.0, 60, 1.0)), BPM, 2.0, 3);

        assertEquals(6, events.size(), "two events per note per loop");
        assertEquals(MidiPlayer.ScheduledEvent.Type.NOTE_ON, eventAt(events, 0).type());
        assertEquals(MidiPlayer.ScheduledEvent.Type.NOTE_ON, eventAt(events, 2 * BEAT).type());
        assertEquals(MidiPlayer.ScheduledEvent.Type.NOTE_ON, eventAt(events, 4 * BEAT).type());
    }

    @Test
    void everyOffsetIsCountedFromTheStartSoTheyCannotDriftApart() {
        // a triplet: three notes to a beat, none of which lands on a whole number of nanoseconds
        List<Note> triplet = List.of(note(0.0, 60, 0.33), note(1 / 3.0, 62, 0.33), note(2 / 3.0, 64, 0.33));

        List<MidiPlayer.ScheduledEvent> events = MidiPlayer.schedule(triplet, BPM, 1.0, 8);

        long lastLoopStart = 7 * BEAT;
        assertEquals(MidiPlayer.ScheduledEvent.Type.NOTE_ON, eventAt(events, lastLoopStart).type());
        assertEquals(MidiPlayer.ScheduledEvent.Type.NOTE_ON,
                eventAt(events, lastLoopStart + Math.round(BEAT / 3.0)).type(),
                "the eighth loop's middle note still lands where its beat says");
    }

    @Test
    void refusesToPlayNothing() {
        assertThrows(IllegalArgumentException.class,
                () -> MidiPlayer.schedule(List.of(note(0.0, 60, 1.0)), BPM, 4.0, 0));
    }

    private static MidiPlayer.ScheduledEvent eventAt(List<MidiPlayer.ScheduledEvent> events, long offsetNanos) {
        return events.stream()
                .filter(event -> event.offsetNanos() == offsetNanos)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No event at " + offsetNanos + " ns, only "
                        + events.stream().map(MidiPlayer.ScheduledEvent::offsetNanos).toList()));
    }

    private static Note note(double beat, int pitch, double durationBeats) {
        return note(beat, pitch, durationBeats, 1.0f);
    }

    private static Note note(double beat, int pitch, double durationBeats, float velocity) {
        return new Note(beat, new Voice.Pitch(pitch), durationBeats, velocity);
    }
}

package pl.livecoding.musicjam.studio;

import pl.livecoding.musicjam.model.Drum;
import pl.livecoding.musicjam.model.Note;
import pl.livecoding.musicjam.model.Voice;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PianoRollTest {

    @Test
    void theRollSpansTheNotesWithRoomAroundThem() {
        List<Note> wide = List.of(pitch(40), pitch(71), pitch(55));

        assertEquals(new PianoRoll.Keys(38, 73), PianoRoll.Keys.of(wide));
    }

    @Test
    void aNarrowLineStillGetsAnOctaveCentredOnIt() {
        List<Note> narrow = List.of(pitch(60), pitch(62));

        PianoRoll.Keys keys = PianoRoll.Keys.of(narrow);

        assertEquals(new PianoRoll.Keys(55, 67), keys);
        assertEquals(13, keys.rows());
    }

    @Test
    void drumsTakeNoKeysAndNothingToShowGetsTheOctaveAroundMiddleC() {
        PianoRoll.Keys keys = PianoRoll.Keys.of(List.of(new Note(0, Drum.KICK, 1, 1)));

        assertEquals(new PianoRoll.Keys(54, 66), keys);
    }

    @Test
    void theRollStopsAtTheEndsOfTheMidiRange() {
        assertEquals(0, PianoRoll.Keys.of(List.of(pitch(1), pitch(20))).low());
        assertEquals(127, PianoRoll.Keys.of(List.of(pitch(126), pitch(100))).high());
    }

    @Test
    void middleCIsC4() {
        assertEquals("C4", PianoRoll.name(60));
        assertEquals("C-1", PianoRoll.name(0));
    }

    private static Note pitch(int midiNote) {
        return new Note(0, new Voice.Pitch(midiNote), 1, 0.8f);
    }
}

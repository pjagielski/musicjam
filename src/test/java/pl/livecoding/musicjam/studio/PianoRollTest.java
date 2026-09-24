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
    void aLongLoopIsShownFourBarsAtATime() {
        PianoRoll.Pages pages = PianoRoll.Pages.of(32, 4);

        assertEquals(16, pages.pageBeats());
        assertEquals(2, pages.count(), "eight bars, two pages, not a sliver of a third");
        assertEquals(0, pages.of(15.99));
        assertEquals(1, pages.of(16));
        assertEquals(16, pages.start(1));
    }

    @Test
    void aShortLoopIsOnePageAsLongAsItIs() {
        PianoRoll.Pages pages = PianoRoll.Pages.of(8, 4);

        assertEquals(8, pages.pageBeats());
        assertEquals(1, pages.count());
        assertEquals(0, pages.of(7.5));
    }

    @Test
    void aLoopThatDoesNotFillItsLastPageStillHasOne() {
        PianoRoll.Pages pages = PianoRoll.Pages.of(24, 4);

        assertEquals(2, pages.count(), "six bars: four, then two");
        assertEquals(1, pages.of(23.9));
        assertEquals(1, pages.of(40), "never past the last page");
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

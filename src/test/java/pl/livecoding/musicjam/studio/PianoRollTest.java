package pl.livecoding.musicjam.studio;

import pl.livecoding.musicjam.model.Drum;
import pl.livecoding.musicjam.model.Note;
import pl.livecoding.musicjam.model.Voice;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PianoRollTest {

    @Test
    void theRollSpansTheNotesWithRoomAroundThem() {
        List<Note> wide = List.of(pitch(40), pitch(71), pitch(55));

        assertEquals(new PianoRoll.Keys(38, 73), PianoRoll.Keys.of(wide, 0));
        assertEquals(36, PianoRoll.Keys.rowsFor(wide), "what a roll must have room for");
    }

    @Test
    void aShortLineGetsEveryRowTheRollHasRoomFor() {
        List<Note> narrow = List.of(pitch(60), pitch(62));

        PianoRoll.Keys keys = PianoRoll.Keys.of(narrow, 30);

        assertEquals(30, keys.rows(), "octaves to write in, not a band of five keys");
        assertTrue(keys.low() < 58 && keys.high() > 64, "and the notes in the middle of them: " + keys);
    }

    @Test
    void aLineNeedingMoreRowsThanAskedForGetsThem() {
        PianoRoll.Keys keys = PianoRoll.Keys.of(List.of(pitch(40), pitch(90)), 12);

        assertEquals(new PianoRoll.Keys(38, 92), keys);
    }

    @Test
    void drumsTakeNoKeysAndNothingToShowGetsTheRowsAroundMiddleC() {
        PianoRoll.Keys keys = PianoRoll.Keys.of(List.of(new Note(0, Drum.KICK, 1, 1)), 12);

        assertEquals(12, keys.rows());
        assertTrue(keys.low() <= 60 && keys.high() >= 60, "middle C among them: " + keys);
    }

    @Test
    void theRollStopsAtTheEndsOfTheMidiRange() {
        PianoRoll.Keys low = PianoRoll.Keys.of(List.of(pitch(1), pitch(20)), 40);
        assertEquals(0, low.low());
        assertEquals(40, low.rows(), "what one end gives up the other takes");

        PianoRoll.Keys high = PianoRoll.Keys.of(List.of(pitch(126), pitch(100)), 40);
        assertEquals(127, high.high());
        assertEquals(40, high.rows());
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

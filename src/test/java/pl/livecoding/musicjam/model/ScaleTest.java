package pl.livecoding.musicjam.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScaleTest {

    @Test
    void cMajorHoldsTheWhiteNotesAndNothingBetweenThem() {
        Scale cMajor = new Scale(0, Scale.Mode.MAJOR);

        assertEquals(List.of("C4", "D4", "E4", "F4", "G4", "A4", "B4", "C5"),
                held(cMajor, 60, 72));
    }

    @Test
    void aKeyHoldsItsNotesInEveryOctave() {
        Scale eMinor = new Scale(4, Scale.Mode.MINOR);

        assertTrue(eMinor.holds(4), "E at the bottom of the keyboard");
        assertTrue(eMinor.holds(64), "and E four octaves up");
        assertTrue(eMinor.holds(67), "G belongs to E minor");
        assertFalse(eMinor.holds(68), "G# does not");
    }

    @Test
    void theRootIsTheNoteAKeyIsNamedAfter() {
        Scale fMinor = new Scale(5, Scale.Mode.MINOR);

        assertTrue(fMinor.isRoot(53));
        assertTrue(fMinor.isRoot(65), "an octave up, still the root");
        assertFalse(fMinor.isRoot(60));
        assertEquals("F", fMinor.rootName());
        assertEquals("F minor", fMinor.toString());
    }

    @Test
    void aJamWithNoKeyChosenHoldsEveryNote() {
        assertTrue(IntStream.rangeClosed(0, 127).allMatch(Scale.ANY::holds));
        assertFalse(Scale.ANY.chosen());
        assertFalse(Scale.ANY.isRoot(60), "with no key there is no note to come home to");
    }

    @Test
    void thePentatonicsLeaveOutWhatTheFullScaleKeeps() {
        Scale aMinor = new Scale(9, Scale.Mode.MINOR);
        Scale aPentatonic = new Scale(9, Scale.Mode.MINOR_PENTATONIC);

        assertTrue(aMinor.holds(71), "B belongs to A minor");
        assertFalse(aPentatonic.holds(71), "but not to its pentatonic");
        assertEquals(5, (int) IntStream.range(60, 72).filter(aPentatonic::holds).count());
    }

    private static List<String> held(Scale scale, int from, int to) {
        return IntStream.rangeClosed(from, to).filter(scale::holds)
                .mapToObj(ScaleTest::name).toList();
    }

    /** The note's name, for a readable assertion: C4 is middle C. */
    private static String name(int midiNote) {
        return Scale.ROOTS.get(Math.floorMod(midiNote, 12)) + (midiNote / 12 - 1);
    }
}

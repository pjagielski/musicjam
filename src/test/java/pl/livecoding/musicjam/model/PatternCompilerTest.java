package pl.livecoding.musicjam.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Step 5's pattern compiler. What one character of a pattern means comes finished, so its four
 * tests are green from the start; turning a bar of characters into notes is yours.
 */
class PatternCompilerTest {

    @Test
    void aCapitalXIsAHitAtFullVolume() {
        assertEquals(Optional.of(1.0f), PatternCompiler.parse('X'));
    }

    @Test
    void aSmallXIsSofterAndAnOIsHalfAsLoud() {
        assertEquals(Optional.of(0.8f), PatternCompiler.parse('x'));
        assertEquals(Optional.of(0.5f), PatternCompiler.parse('o'));
    }

    @Test
    void aDotADashAndASpaceAreRestsWithNoAccentAtAll() {
        assertEquals(Optional.empty(), PatternCompiler.parse('.'));
        assertEquals(Optional.empty(), PatternCompiler.parse('-'));
        assertEquals(Optional.empty(), PatternCompiler.parse(' '));
    }

    @Test
    void aCharacterThatIsNotAStepIsAMistake() {
        assertThrows(IllegalArgumentException.class, () -> PatternCompiler.parse('?'));
    }

    @Test
    void everyStepThatIsNotARestBecomesANote() {
        List<Note> notes = PatternCompiler.compile(List.of(new DrumTrack(Drum.KICK, "X.x.o...", 1.0f)), 4);

        assertEquals(List.of(
                new Note(0.0, Drum.KICK, 0.5, 1.0f),
                new Note(1.0, Drum.KICK, 0.5, 0.8f),
                new Note(2.0, Drum.KICK, 0.5, 0.5f)), notes);
    }

    @Test
    void theLengthOfAPatternSetsHowFinelyItDividesTheBar() {
        List<Note> sixteenths = PatternCompiler.compile(List.of(new DrumTrack(Drum.CLOSED_HAT, "X...X...X...X...", 1.0f)), 4);
        List<Note> quarters = PatternCompiler.compile(List.of(new DrumTrack(Drum.CLOSED_HAT, "XXXX", 1.0f)), 4);

        assertEquals(List.of(0.0, 1.0, 2.0, 3.0), sixteenths.stream().map(Note::beat).toList());
        assertEquals(0.25, sixteenths.getFirst().durationBeats());
        assertEquals(List.of(0.0, 1.0, 2.0, 3.0), quarters.stream().map(Note::beat).toList());
        assertEquals(1.0, quarters.getFirst().durationBeats());
    }

    @Test
    void aTracksGainScalesItsAccents() {
        List<Note> notes = PatternCompiler.compile(List.of(new DrumTrack(Drum.SNARE, "Xx", 0.5f)), 4);

        assertEquals(List.of(0.5f, 0.4f), notes.stream().map(Note::velocity).toList());
    }

    @Test
    void everyTrackGoesIntoOneListInTheOrderTheHitsAreHeard() {
        List<Note> notes = PatternCompiler.compile(List.of(
                new DrumTrack(Drum.SNARE, ".X..", 1.0f),
                new DrumTrack(Drum.KICK, "X...", 1.0f)), 4);

        assertEquals(List.of(Drum.KICK, Drum.SNARE), notes.stream().map(Note::voice).toList());
    }
}

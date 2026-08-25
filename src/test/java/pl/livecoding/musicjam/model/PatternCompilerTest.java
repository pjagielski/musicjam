package pl.livecoding.musicjam.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PatternCompilerTest {

    @Test
    void compilesPatternCharactersToMusicalTimeAndVelocity() {
        var song = new Song(120, 4, List.of(
                new DrumTrack(Drum.KICK, "X.o.", 0.5f)
        ));

        var notes = PatternCompiler.compile(song);

        assertEquals(List.of(
                new Note(0.0, Drum.KICK, 1.0, 0.5f),
                new Note(2.0, Drum.KICK, 1.0, 0.25f)
        ), notes);
    }

    @Test
    void tilesADrumTrackToMatchALongerMelodyTrack() {
        var pitch = new Voice.Pitch(60);
        var song = new Song(120, 4, List.of(
                new DrumTrack(Drum.KICK, "X...", 1.0f),
                new MelodyTrack(List.of(new Note(0.0, pitch, 1.0, 1.0f)), 8.0, 1.0f)
        ));

        assertEquals(8.0, PatternCompiler.totalBeats(song));

        var notes = PatternCompiler.compile(song);

        assertEquals(List.of(
                new Note(0.0, Drum.KICK, 1.0, 1.0f),
                new Note(0.0, pitch, 1.0, 1.0f),
                new Note(4.0, Drum.KICK, 1.0, 1.0f)
        ), notes);
    }
}

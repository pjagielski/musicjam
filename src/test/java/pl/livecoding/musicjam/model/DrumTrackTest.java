package pl.livecoding.musicjam.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class DrumTrackTest {

    @Test
    void rejectsNonFiniteGain() {
        assertThrows(IllegalArgumentException.class,
                () -> new DrumTrack(Drum.KICK, "X...", Float.NaN));
    }
}

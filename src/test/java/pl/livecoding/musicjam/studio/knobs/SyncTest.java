package pl.livecoding.musicjam.studio.knobs;

import org.junit.jupiter.api.Test;

import static pl.livecoding.musicjam.studio.knobs.SynthControls.Sync;
import static pl.livecoding.musicjam.studio.knobs.SynthControls.millisFor;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SyncTest {

    @Test
    void aDivisionIsAsLongAsTheTempoSaysItIs() {
        assertEquals(2_000, millisFor(Sync.QUARTER, 30), 1e-9);
        assertEquals(500, millisFor(Sync.QUARTER, 120), 1e-9);
        assertEquals(250, millisFor(Sync.EIGHTH, 120), 1e-9);
        assertEquals(375, millisFor(Sync.DOTTED_EIGHTH, 120), 1e-9);
        assertEquals(125, millisFor(Sync.SIXTEENTH, 120), 1e-9);
    }

    @Test
    void aTripletIsThreeToTheBeat() {
        // three eighth-note triplets fill one beat, so each is a third of it
        assertEquals(millisFor(Sync.QUARTER, 120) / 3, millisFor(Sync.TRIPLET_EIGHTH, 120), 1e-9);
    }
}

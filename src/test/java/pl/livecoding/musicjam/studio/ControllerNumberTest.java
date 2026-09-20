package pl.livecoding.musicjam.studio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ControllerNumberTest {

    @Test
    void aTypedNumberIsTakenAsIs() {
        assertEquals(7, BeatStudio.controllerFrom("7", 74));
        assertEquals(74, BeatStudio.controllerFrom(" 74 ", 1));
    }

    @Test
    void oneOutsideTheMidiRangeIsBroughtBackIn() {
        assertEquals(127, BeatStudio.controllerFrom("500", 74));
        assertEquals(0, BeatStudio.controllerFrom("-3", 74));
    }

    @Test
    void anythingElseLeavesTheValueAlone() {
        assertEquals(74, BeatStudio.controllerFrom("", 74));
        assertEquals(74, BeatStudio.controllerFrom("cutoff", 74));
    }
}

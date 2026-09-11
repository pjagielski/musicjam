package pl.livecoding.musicjam.studio;

import pl.livecoding.musicjam.livecode.LiveCode;
import pl.livecoding.musicjam.model.Drum;
import pl.livecoding.musicjam.model.DrumTrack;
import pl.livecoding.musicjam.model.Envelope;
import pl.livecoding.musicjam.model.PatternCompiler;
import pl.livecoding.musicjam.model.Song;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GridRowTest {

    private static final String EXAMPLE = """
            $: stack(
                s("bd(3,8,5)"),
                s("[~ sd]*2").gain(1.25),
                s("hh*16").gain("[0.2 0.1]*8"),
                s("[~ oh ~ oh]*2").gain(0.45).rel(0.1).dec(0.2)
              )
            """;

    @Test
    void drawsEachLayerOfTheCodeAsARowOfSixteenSteps() {
        List<GridRow> rows = GridRow.fromCode(LiveCode.parse(EXAMPLE));

        assertEquals(List.of("1 bd", "2 sd", "3 hh", "4 oh"), rows.stream().map(GridRow::label).toList());
        assertArrayEquals(accents(16, 1.0f, 2, 6, 12), rows.get(0).accents());
        assertArrayEquals(accents(16, 1.0f, 4, 12), rows.get(1).accents());
        float[] hats = new float[16];
        for (int step = 0; step < 16; step++) {
            hats[step] = step % 2 == 0 ? 0.2f : 0.1f;
        }
        assertArrayEquals(hats, rows.get(2).accents());
        assertArrayEquals(accents(16, 0.45f, 2, 6, 10, 14), rows.get(3).accents());
        assertEquals(new Envelope(0.2, 0.1), rows.get(3).envelope());
    }

    @Test
    void theGridPlaysExactlyWhatTheCodeWouldHavePlayed() {
        LiveCode code = LiveCode.parse(EXAMPLE);

        assertEquals(code.notes(4.0, 8.0), GridRow.notes(GridRow.fromCode(code), 4.0, 8.0));
    }

    @Test
    void aSubdivisionThatDoesNotFitSixteenStepsGetsItsOwnStepCount() {
        List<GridRow> rows = GridRow.fromCode(LiveCode.parse("s(\"bd [sd sd sd]\")"));

        assertEquals(16, rows.get(0).accents().length);
        assertArrayEquals(accents(6, 1.0f, 3, 4, 5), rows.get(1).accents());
    }

    @Test
    void aPresetRowPlaysLikeTheDrumTrackItCameFrom() {
        DrumTrack track = new DrumTrack(Drum.KICK, "X.o.x...", 0.5f);

        assertEquals(PatternCompiler.compile(new Song(120, 4, List.of(track))),
                GridRow.notes(List.of(GridRow.fromTrack(track)), 4.0, 4.0));
    }

    @Test
    void changingOneStepLeavesTheOriginalRowAlone() {
        GridRow row = GridRow.fromTrack(new DrumTrack(Drum.KICK, "X...", 1.0f));

        GridRow changed = row.withAccent(1, 0.8f);

        assertArrayEquals(new float[]{1.0f, 0.8f, 0.0f, 0.0f}, changed.accents());
        assertArrayEquals(new float[]{1.0f, 0.0f, 0.0f, 0.0f}, row.accents());
    }

    private static float[] accents(int steps, float accent, int... hits) {
        float[] accents = new float[steps];
        for (int hit : hits) {
            accents[hit] = accent;
        }
        return accents;
    }
}

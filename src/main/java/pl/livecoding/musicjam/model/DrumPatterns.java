package pl.livecoding.musicjam.model;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/** The drum patterns a config can name with {@code drums=}: one bar each, one for every fixture. */
public final class DrumPatterns {

    private static final Map<String, List<DrumTrack>> PATTERNS = Map.of(
            // from the "Electric Drum Kit" track of song_shape.mid, bar 3 on, once the groove kicks in
            "shape", List.of(
                    new DrumTrack(Drum.KICK, "o..o....o..o....", 1.00f),
                    new DrumTrack(Drum.CLOSED_HAT, "o.ooo.o.o.o.oooo", 0.60f),
                    new DrumTrack(Drum.SNARE, "......o.......o.", 0.80f)),
            // from the "Drums" track of song_child.mid, bar 40: the drop
            "worry", List.of(
                    new DrumTrack(Drum.KICK, "X...X...X...X...", 1.00f),
                    new DrumTrack(Drum.SNARE, "....X.......X...", 0.80f),
                    new DrumTrack(Drum.CLOSED_HAT, "...X..X....X..X.", 0.55f),
                    new DrumTrack(Drum.OPEN_HAT, "..X...X...X...X.", 0.45f)),
            // from the "Da Dope Beat" track of song_still_dre.mid, bar 10
            "dre", List.of(
                    new DrumTrack(Drum.KICK, "X.....X.X.......", 1.00f),
                    new DrumTrack(Drum.SNARE, "....X.......X...", 0.85f),
                    new DrumTrack(Drum.CLOSED_HAT, "....X.......X...", 0.45f),
                    new DrumTrack(Drum.OPEN_HAT, "..X.......X.....", 0.55f)),
            // song_giorgioby.mid has no drum track at all, so this one is a plain four-on-the-floor
            "giorgio", List.of(
                    new DrumTrack(Drum.KICK, "X...X...X...X...", 1.00f),
                    new DrumTrack(Drum.CLOSED_HAT, "x.x.x.x.x.x.x.x.", 0.50f),
                    new DrumTrack(Drum.CLOSED_HAT, ".x.x.x.x.x.x.x.x", 0.25f)),
            // song_insomnia.mid has no drums either: a house groove, the clap on 2 and 4, the open hat
            // on every off-beat - at half the level of the others, so that a synth melody on top of
            // it does not clip
            "insomnia", List.of(
                    new DrumTrack(Drum.KICK, "X...X...X...X...", 0.50f),
                    new DrumTrack(Drum.CLAP, "....X.......X..x", 0.50f),
                    new DrumTrack(Drum.SNARE, "....X.......X...", 0.38f),
                    new DrumTrack(Drum.OPEN_HAT, "..x...o...x...o.", 0.25f),
                    new DrumTrack(Drum.CLOSED_HAT, ".o.o.o.o.o.o.o.o", 0.20f)));

    private DrumPatterns() {
    }

    public static List<DrumTrack> named(String name) {
        List<DrumTrack> pattern = PATTERNS.get(name.toLowerCase(Locale.ROOT));
        if (pattern == null) {
            throw new IllegalArgumentException(
                    "Unknown drums \"" + name + "\", expected one of " + new TreeSet<>(PATTERNS.keySet()));
        }
        return pattern;
    }
}

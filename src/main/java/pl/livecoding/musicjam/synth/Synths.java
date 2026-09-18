package pl.livecoding.musicjam.synth;

import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/** The synths a config can name with {@code synth=}: patches ported from novasaw. */
public final class Synths {

    private static final Map<String, PitchSynth> SYNTHS = Map.of(
            "anthem", new AnthemLeadSynth(),
            "pluck", new TrancePluckSynth(),
            "pad", new WidePadSynth());

    private Synths() {
    }

    public static PitchSynth named(String name) {
        PitchSynth synth = SYNTHS.get(name.toLowerCase(Locale.ROOT));
        if (synth == null) {
            throw new IllegalArgumentException(
                    "Unknown synth \"" + name + "\", expected one of " + new TreeSet<>(SYNTHS.keySet()));
        }
        return synth;
    }
}

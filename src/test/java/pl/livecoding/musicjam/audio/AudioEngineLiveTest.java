package pl.livecoding.musicjam.audio;

import pl.livecoding.musicjam.model.Drum;
import pl.livecoding.musicjam.model.DrumTrack;
import pl.livecoding.musicjam.model.Envelope;
import pl.livecoding.musicjam.model.MelodyTrack;
import pl.livecoding.musicjam.model.Note;
import pl.livecoding.musicjam.model.Song;
import pl.livecoding.musicjam.model.Voice;
import pl.livecoding.musicjam.synth.PitchSynth;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AudioEngineLiveTest {
    private static final int BLOCK = 128;
    // 31 blocks = 3968 frames: two whole 2000-frame loops at 120 BPM, and not a frame of the third.
    private static final int BLOCKS = 31;
    private static final PitchSynth NO_SYNTH = (midiNote, frameCount, sampleRate) -> {
        throw new AssertionError("melody should not be rendered natively");
    };

    @Test
    void anEditLandsOnTheNextLoopBoundary() {
        Song before = new Song(120, 4, List.of(new DrumTrack(Drum.KICK, "X...", 0.5f)));
        Song after = new Song(120, 4, List.of(new DrumTrack(Drum.SNARE, ".X..", 0.25f)));
        var renderer = engine().liveRenderer(firstThen(jam(before), jam(after)), null);

        float[] left = render(renderer, BLOCKS);

        assertEquals(0.5f, left[0]);
        assertEquals(0.0f, left[500]);
        assertEquals(0.0f, left[2_000]);
        assertEquals(0.25f, left[2_500]);
    }

    @Test
    void aNewTempoStartsExactlyWhereThePreviousLoopEnded() {
        Song fast = new Song(120, 4, List.of(new DrumTrack(Drum.KICK, "X...", 1.0f)));
        Song slow = new Song(60, 4, List.of(new DrumTrack(Drum.KICK, "XX..", 1.0f)));
        var renderer = engine().liveRenderer(firstThen(jam(fast), jam(slow)), null);

        float[] left = render(renderer, BLOCKS);

        assertEquals(1.0f, left[2_000]);
        assertEquals(0.0f, left[2_500]);
        assertEquals(1.0f, left[3_000]);
        var position = renderer.positionAt(3_000);
        assertEquals(slow, position.song());
        assertEquals(1.0, position.beat(), 1e-9);
        assertEquals(4.0, position.lengthBeats());
    }

    @Test
    void aLoopShorterThanABarPlaysOnlyTheStartOfThePattern() {
        Song halfBar = new Song(120, 4, List.of(
                new DrumTrack(Drum.KICK, "X..X", 1.0f),
                new MelodyTrack(List.of(), 2.0, 1.0f)));
        var renderer = engine().liveRenderer(() -> jam(halfBar), null);

        float[] left = render(renderer, BLOCKS);

        assertEquals(1.0f, left[0]);
        assertEquals(0.0f, left[1_500]);
        assertEquals(1.0f, left[1_000]);
        assertEquals(1.0f, left[3_000]);
    }

    @Test
    void aDrumNoteWithAnEnvelopePlaysAShapedCopyOfItsSample() {
        var samples = SampleBank.from(Map.of(Drum.OPEN_HAT, Sample.mono(1.0f, 1.0f, 1.0f, 1.0f)));
        var engine = new AudioEngine(samples, 1_000, BLOCK, 8);
        var hit = new Note(0.0, Drum.OPEN_HAT, 1.0, 1.0f, new Envelope(0.002, 0.0));
        Song song = new Song(120, 4, List.of(new MelodyTrack(List.of(hit), 4.0, 1.0f)));

        float[] left = render(engine.liveRenderer(() -> jam(song), null), BLOCKS);

        assertEquals(1.0f, left[0]);
        assertEquals((float) Math.pow(1000, -0.5), left[1], 1e-6f);
        assertEquals(0.0f, left[2]);
    }

    @Test
    void externalMelodyIsHandedOnWithAbsoluteFramesInsteadOfRendered() {
        var melody = new MelodyTrack(List.of(new Note(1.0, new Voice.Pitch(60), 0.5, 100 / 127f)), 4.0, 1.0f);
        Song song = new Song(120, 4, List.of(melody));
        var sent = new ArrayList<AudioEngine.ExternalNote>();
        var renderer = engine().liveRenderer(() -> jam(song), sent::add);

        float[] left = render(renderer, BLOCKS);

        assertEquals(List.of(
                new AudioEngine.ExternalNote(500, true, 60, 100),
                new AudioEngine.ExternalNote(750, false, 60, 0),
                new AudioEngine.ExternalNote(2_500, true, 60, 100),
                new AudioEngine.ExternalNote(2_750, false, 60, 0)
        ), sent);
        for (float sample : left) {
            assertEquals(0.0f, sample);
        }
    }

    @Test
    void aMutedMelodySendsNothing() {
        var melody = new MelodyTrack(List.of(new Note(1.0, new Voice.Pitch(60), 0.5, 1.0f)), 4.0, 0.0f);
        Song song = new Song(120, 4, List.of(melody));
        var sent = new ArrayList<AudioEngine.ExternalNote>();
        var renderer = engine().liveRenderer(() -> jam(song), sent::add);

        render(renderer, BLOCKS);

        assertEquals(List.of(), sent);
    }

    private static AudioEngine engine() {
        var samples = SampleBank.from(Map.of(
                Drum.KICK, Sample.mono(1.0f),
                Drum.SNARE, Sample.mono(1.0f)
        ));
        return new AudioEngine(samples, 1_000, BLOCK, 8);
    }

    private static AudioEngine.Jam jam(Song song) {
        return new AudioEngine.Jam(song, NO_SYNTH);
    }

    private static <T> Supplier<T> firstThen(T first, T rest) {
        var calls = new AtomicInteger();
        return () -> calls.getAndIncrement() == 0 ? first : rest;
    }

    private static float[] render(AudioEngine.LiveRenderer renderer, int blocks) {
        float[] left = new float[blocks * BLOCK];
        float[] mix = new float[BLOCK * 2];
        for (int block = 0; block < blocks; block++) {
            renderer.renderNext(mix);
            for (int frame = 0; frame < BLOCK; frame++) {
                left[block * BLOCK + frame] = mix[frame * 2];
            }
        }
        return left;
    }
}

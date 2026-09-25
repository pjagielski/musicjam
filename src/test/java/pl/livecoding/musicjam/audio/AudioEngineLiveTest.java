package pl.livecoding.musicjam.audio;

import pl.livecoding.musicjam.model.Drum;
import pl.livecoding.musicjam.model.DrumTrack;
import pl.livecoding.musicjam.model.Envelope;
import pl.livecoding.musicjam.model.MelodyTrack;
import pl.livecoding.musicjam.model.Note;
import pl.livecoding.musicjam.model.Song;
import pl.livecoding.musicjam.model.Voice;
import pl.livecoding.musicjam.synth.LivePitchSynth;
import pl.livecoding.musicjam.synth.PitchSynth;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        var renderer = engine().liveRenderer(firstThen(jam(before), jam(after)), false);

        float[] left = render(renderer, BLOCKS);

        assertEquals(0.5f, left[0]);
        assertEquals(0.0f, left[500]);
        assertEquals(0.0f, left[2_000]);
        assertEquals(0.25f, left[2_500]);
    }

    @Test
    void aNewTempoIsTakenUpAtOnceInsideTheLoop() {
        Song fast = new Song(120, 4, List.of(new DrumTrack(Drum.KICK, "XXXX", 1.0f)));
        Song slow = new Song(60, 4, List.of(new DrumTrack(Drum.KICK, "XXXX", 1.0f)));
        var jam = new AtomicReference<>(jam(fast));
        var renderer = engine().liveRenderer(jam::get, false);

        float[] before = render(renderer, 5);
        jam.set(jam(slow));
        float[] after = render(renderer, 26);

        // beat 1 at 120 BPM; the change comes at frame 640, beat 1.28, and beat 2 is 720 frames on
        assertEquals(1.0f, before[500]);
        assertEquals(0.0f, after[1_000 - 640], "no longer 500 frames a beat");
        assertEquals(1.0f, after[1_360 - 640]);
        assertEquals(1.0f, after[2_360 - 640]);
        var position = renderer.positionAt(1_360);
        assertEquals(2.0, position.beat(), 1e-9);
        assertEquals(60.0, position.bpm(), 1e-9);
    }

    @Test
    void aShorterLoopWrapsAtTheFirstMultipleOfItsLengthStillAhead() {
        Song twoBars = new Song(120, 4, List.of(
                new DrumTrack(Drum.KICK, "X...", 1.0f), new MelodyTrack(List.of(), 8.0, 1.0f)));
        Song oneBar = new Song(120, 4, List.of(
                new DrumTrack(Drum.SNARE, ".X..", 0.25f), new MelodyTrack(List.of(), 4.0, 1.0f)));
        var jam = new AtomicReference<>(jam(twoBars));
        var renderer = engine().liveRenderer(jam::get, false);

        render(renderer, 5);
        jam.set(jam(oneBar));
        float[] left = render(renderer, 26);

        // set in the first bar: the loop ends after it, and the second bar's kick never plays
        assertEquals(0.0f, left[2_000 - 640]);
        assertEquals(0.25f, left[2_500 - 640], "the new loop, from frame 2000");
        assertEquals(4.0, renderer.positionAt(2_500).lengthBeats());
    }

    @Test
    void aLongerLoopRunsOnPastTheOldEndWithTheLongerSongsHits() {
        Song oneBar = new Song(120, 4, List.of(
                new DrumTrack(Drum.KICK, "X...", 1.0f), new MelodyTrack(List.of(), 4.0, 1.0f)));
        Song twoBars = new Song(120, 4, List.of(
                new MelodyTrack(List.of(new Note(5.0, Drum.SNARE, 0.25, 0.25f)), 8.0, 1.0f)));
        var jam = new AtomicReference<>(jam(oneBar));
        var renderer = engine().liveRenderer(jam::get, false);

        render(renderer, 5);
        jam.set(jam(twoBars));
        float[] left = render(renderer, 26);

        assertEquals(0.25f, left[2_500 - 640], "beat 5 of the loop that was playing");
        var position = renderer.positionAt(2_500);
        assertEquals(5.0, position.beat(), 1e-9);
        assertEquals(8.0, position.lengthBeats());
    }

    @Test
    void externalNotesNotYetSentFollowANewTempo() {
        var melody = new MelodyTrack(List.of(new Note(3.0, new Voice.Pitch(60), 0.5, 100 / 127f)), 4.0, 1.0f);
        var jam = new AtomicReference<>(outward(new Song(120, 4, List.of(melody))));
        var renderer = engine().liveRenderer(jam::get, true);

        render(renderer, 5);
        assertEquals(1_500, renderer.frameAt(3.0));
        jam.set(outward(new Song(60, 4, List.of(melody))));
        render(renderer, 1);

        assertEquals(2_360, renderer.frameAt(3.0));
    }

    @Test
    void aShorterLoopTakesBackTheExternalNotesPastItsEnd() {
        var early = new Note(1.0, new Voice.Pitch(60), 0.5, 100 / 127f);
        var late = new Note(6.0, new Voice.Pitch(64), 0.5, 100 / 127f);
        var jam = new AtomicReference<>(outward(new Song(120, 4, List.of(new MelodyTrack(List.of(early, late), 8.0, 1.0f)))));
        var renderer = engine().liveRenderer(jam::get, true);

        render(renderer, 1);
        jam.set(outward(new Song(120, 4, List.of(new MelodyTrack(List.of(early), 4.0, 1.0f)))));
        render(renderer, 1);

        assertEquals(List.of(
                new AudioEngine.ExternalNote(1.0, true, 60, 100, 1.0, 0, 0),
                new AudioEngine.ExternalNote(1.5, false, 60, 0, 1.0, 0, 0)
        ), drain(renderer));
    }

    @Test
    void aLoopShorterThanABarPlaysOnlyTheStartOfThePattern() {
        Song halfBar = new Song(120, 4, List.of(
                new DrumTrack(Drum.KICK, "X..X", 1.0f),
                new MelodyTrack(List.of(), 2.0, 1.0f)));
        var renderer = engine().liveRenderer(() -> jam(halfBar), false);

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

        float[] left = render(engine.liveRenderer(() -> jam(song), false), BLOCKS);

        assertEquals(1.0f, left[0]);
        assertEquals((float) Math.pow(1000, -0.5), left[1], 1e-6f);
        assertEquals(0.0f, left[2]);
    }

    @Test
    void externalMelodyIsHandedOnInBeatsInsteadOfRendered() {
        var melody = new MelodyTrack(List.of(new Note(1.0, new Voice.Pitch(60), 0.5, 100 / 127f)), 4.0, 1.0f);
        Song song = new Song(120, 4, List.of(melody));
        var renderer = engine().liveRenderer(() -> outward(song), true);

        float[] left = render(renderer, BLOCKS);

        assertEquals(List.of(
                new AudioEngine.ExternalNote(1.0, true, 60, 100, 1.0, 0, 0),
                new AudioEngine.ExternalNote(1.5, false, 60, 0, 1.0, 0, 0),
                new AudioEngine.ExternalNote(5.0, true, 60, 100, 5.0, 0, 0),
                new AudioEngine.ExternalNote(5.5, false, 60, 0, 5.0, 0, 0)
        ), drain(renderer));
        assertEquals(2_500, renderer.frameAt(5.0));
        for (float sample : left) {
            assertEquals(0.0f, sample);
        }
    }

    @Test
    void eachTrackGoesOutOnItsOwnChannelOrIsPlayedHere() {
        var level = new AtomicReference<>(0.25f);
        var here = new MelodyTrack(List.of(new Note(0.0, new Voice.Pitch(60), 0.5, 1.0f)), 4.0, 1.0f);
        var out = new MelodyTrack(List.of(new Note(1.0, new Voice.Pitch(64), 0.5, 100 / 127f)), 4.0, 1.0f);
        var bass = new MelodyTrack(List.of(new Note(2.0, new Voice.Pitch(36), 0.5, 100 / 127f)), 4.0, 1.0f);
        Song song = new Song(120, 4, List.of(here, out, bass));
        LivePitchSynth synth = constantLevelSynth(level);
        var renderer = engine().liveRenderer(
                () -> new AudioEngine.Jam(song, List.of(synth), List.of(AudioEngine.Jam.HERE, 2, 5)), true);

        // eight blocks, 1024 frames: past the second track's note at frame 500, short of the next loop
        float[] left = render(renderer, 8);

        assertEquals(0.25f, left[100], 1e-6f, "the first track, played here");
        assertEquals(List.of(
                new AudioEngine.ExternalNote(1.0, true, 64, 100, 1.0, 1, 2),
                new AudioEngine.ExternalNote(1.5, false, 64, 0, 1.0, 1, 2),
                new AudioEngine.ExternalNote(2.0, true, 36, 100, 2.0, 2, 5),
                new AudioEngine.ExternalNote(2.5, false, 36, 0, 2.0, 2, 5)
        ), drain(renderer), "the other two, each on its own channel");
        assertEquals(0.0f, left[500 + 100], "and not played here as well");
    }

    @Test
    void aMutedMelodySendsNothingAndAQuieterOneSendsSofterNotes() {
        var melody = new MelodyTrack(List.of(new Note(1.0, new Voice.Pitch(60), 0.5, 1.0f)), 4.0, 0.0f);
        var jam = new AtomicReference<>(outward(new Song(120, 4, List.of(melody))));
        var renderer = engine().liveRenderer(jam::get, true);

        render(renderer, 1);

        // queued all the same, so that unmuting it later in the loop is heard at once
        AudioEngine.ExternalNote noteOn = drain(renderer).getFirst();
        assertEquals(127, noteOn.velocity());
        assertEquals(0, AudioEngine.sentVelocity(noteOn, renderer.trackGain(noteOn.track())), "muted: not sent");
        jam.set(outward(new Song(120, 4, List.of(new MelodyTrack(melody.notes(), 4.0, 0.5f)))));
        render(renderer, 1);
        assertEquals(64, AudioEngine.sentVelocity(noteOn, renderer.trackGain(noteOn.track())));
    }

    @Test
    void aMuteIsHeardAtOnceRatherThanAtTheNextLoop() {
        Song playing = new Song(120, 4, List.of(new DrumTrack(Drum.KICK, "XXXX", 1.0f),
                new DrumTrack(Drum.SNARE, "X...", 0.5f)));
        Song muted = new Song(120, 4, List.of(new DrumTrack(Drum.KICK, "XXXX", 0.0f),
                new DrumTrack(Drum.SNARE, "X...", 0.5f)));
        var jam = new AtomicReference<>(jam(playing));
        var renderer = engine().liveRenderer(jam::get, false);

        float[] before = render(renderer, 5);
        jam.set(jam(muted));
        float[] after = render(renderer, 26);

        assertEquals(1.0f, before[500]);
        assertEquals(0.0f, after[1_000 - 640], "the next kick, in the same loop");
        assertEquals(0.0f, after[1_500 - 640]);
        assertEquals(0.5f, after[2_000 - 640], "the other track plays on");
    }

    @Test
    void aMutedNoteFallsSilentWhileItSoundsWithoutAClick() {
        var level = new AtomicReference<>(0.25f);
        var note = new MelodyTrack(List.of(new Note(0.0, new Voice.Pitch(60), 4.0, 1.0f)), 4.0, 1.0f);
        var jam = new AtomicReference<>(new AudioEngine.Jam(new Song(120, 4, List.of(note)), constantLevelSynth(level)));
        var renderer = engine().liveRenderer(jam::get, false);

        float[] before = render(renderer, 2);
        jam.set(new AudioEngine.Jam(new Song(120, 4, List.of(new MelodyTrack(note.notes(), 4.0, 0.0f))),
                jam.get().synthFor(0)));
        float[] fading = render(renderer, 1);
        float[] after = render(renderer, 1);

        assertEquals(0.25f, before[200], 1e-6f);
        // one block's glide down, not a step: halfway through it, half the level
        assertEquals(0.125f, fading[BLOCK / 2], 1e-3f);
        assertTrue(fading[BLOCK - 1] < 0.01f);
        assertEquals(0.0f, after[10], "the note still held, but silent");
    }

    @Test
    void aLiveSynthIsHeardChangingInsideANoteThatIsAlreadySounding() {
        var level = new AtomicReference<>(0.25f);
        Song song = new Song(120, 4, List.of(new MelodyTrack(
                List.of(new Note(0.0, new Voice.Pitch(60), 4.0, 1.0f)), 4.0, 1.0f)));
        var renderer = engine().liveRenderer(
                () -> new AudioEngine.Jam(song, constantLevelSynth(level)), false);
        float[] mix = new float[BLOCK * 2];

        renderer.renderNext(mix);
        assertEquals(0.25f, mix[0]);

        level.set(0.75f);
        renderer.renderNext(mix);

        // the same note is still sounding: the new value is heard without waiting for the next loop
        assertEquals(0.75f, mix[0]);
    }

    @Test
    void afterSchedulingStopsTheTailPlaysOnButNoNewNoteStarts() {
        Song song = new Song(120, 4, List.of(new DrumTrack(Drum.KICK, "X...", 1.0f)));
        var renderer = engine().liveRenderer(() -> jam(song), false);
        float[] mix = new float[BLOCK * 2];

        renderer.renderNext(mix);
        assertEquals(1.0f, mix[0], "the loop's first kick");

        renderer.stopScheduling();
        float[] left = render(renderer, BLOCKS);

        // the next loop would have put a kick at frame 2000 of this stretch; nothing should arrive
        for (float value : left) {
            assertEquals(0.0f, value);
        }
    }

    @Test
    void aStutterRepeatsTheMixAndStoppingLetsGoOfIt() {
        Song song = new Song(120, 4, List.of(new DrumTrack(Drum.KICK, "X...", 1.0f)));
        var renderer = engine().liveRenderer(() -> jam(song), false);
        renderer.stutter(0.5);

        float[] held = render(renderer, 4);
        assertEquals(1.0f, held[0], 1e-6f, "the kick");
        assertEquals(1.0f, held[250], 1e-6f, "the kick again, an eighth later, from the repeat");

        renderer.stopScheduling();
        float[] after = render(renderer, BLOCKS);
        for (int frame = 16; frame < after.length; frame++) {
            assertEquals(0.0f, after[frame], 1e-6f, "a stopped jam should not keep repeating");
        }
    }

    @Test
    void aStutterPlaysItsNotesAgainThroughTheSynthAsItIsNow() {
        var level = new AtomicReference<>(0.25f);
        Song song = new Song(120, 4, List.of(new MelodyTrack(
                List.of(new Note(0.0, new Voice.Pitch(60), 0.25, 1.0f)), 4.0, 1.0f)));
        var renderer = engine().liveRenderer(
                () -> new AudioEngine.Jam(song, constantLevelSynth(level)), false);
        renderer.stutter(0.5);
        float[] mix = new float[BLOCK * 2];

        renderer.renderNext(mix);
        assertEquals(0.25f, mix[0], "the note as the song plays it");

        level.set(0.75f);
        float[] left = render(renderer, 3);

        // frame 250 of the render is frame 122 here: the slice's first repeat, with the new level
        assertEquals(0.75f, left[250 - BLOCK], 1e-6f);
        assertEquals(0.0f, left[200 - BLOCK], 1e-6f, "a repeat is cut to the slice, not smeared over it");
    }

    @Test
    void aHeldStutterKeepsItsSliceWhileTheSongMovesOnUnderneath() {
        Song kicks = new Song(120, 4, List.of(new DrumTrack(Drum.KICK, "X...", 0.5f)));
        // at the same gain as the kicks: a repeat plays at the gain of its track as it is now
        Song snares = new Song(120, 4, List.of(new DrumTrack(Drum.SNARE, ".X..", 0.5f)));
        var renderer = engine().liveRenderer(firstThen(jam(kicks), jam(snares)), false);
        renderer.stutter(0.5);

        float[] left = render(renderer, BLOCKS);

        assertEquals(0.5f, left[2_000], 1e-6f, "still the first loop's kick, in the second loop");
        assertEquals(0.5f, left[2_500], 1e-6f, "and the second loop's snare is not played over it");
    }

    @Test
    void aStutterHeldForManyBarsKeepsRepeating() {
        Song song = new Song(120, 4, List.of(new DrumTrack(Drum.KICK, "X...", 0.5f)));
        var renderer = engine().liveRenderer(() -> jam(song), false);
        renderer.stutter(0.5);

        // twenty seconds at 1000 frames a second: far longer than the song is remembered for
        float[] left = render(renderer, 160);

        assertEquals(0.5f, left[19_750], 1e-6f, "the slice's kick, still repeating an eighth apart");
    }

    @Test
    void lettingGoOfAStutterPicksTheSongUpWhereItHasGot() {
        Song song = new Song(120, 4, List.of(
                new DrumTrack(Drum.KICK, "X...", 0.5f), new DrumTrack(Drum.SNARE, "..X.", 0.25f)));
        var renderer = engine().liveRenderer(() -> jam(song), false);
        renderer.stutter(0.5);
        float[] held = render(renderer, 4);
        renderer.stutter(0);

        float[] after = render(renderer, 12);

        assertEquals(0.5f, held[250], 1e-6f, "repeating while held");
        assertEquals(0.25f, after[1_000 - 4 * BLOCK], 1e-6f, "the song's snare, on time, after letting go");
        assertEquals(0.0f, after[750 - 4 * BLOCK], 1e-6f, "and no more repeats");
    }

    @Test
    void aPerformanceEffectIsPlayedOverTheDrumsTooAndStopLetsGoOfIt() {
        Song song = new Song(120, 4, List.of(new DrumTrack(Drum.KICK, "X...", 1.0f)));
        var renderer = engine().liveRenderer(() -> jam(song), false);
        renderer.performance().filter(0.0);

        float[] left = render(renderer, BLOCKS);

        // a low-pass smears the one-frame kick out over the frames after it
        assertTrue(left[2_000] < 0.5f, "the kick itself, softened: " + left[2_000]);
        assertTrue(left[2_001] > 0.05f, "and ringing on after it: " + left[2_001]);

        renderer.stopScheduling();
        float[] after = render(renderer, 2);
        assertEquals(0.0f, after[after.length - 1], 1e-6f);
    }

    @Test
    void effectsSitAcrossTheSynthOnly() {
        var level = new AtomicReference<>(0.25f);
        Song song = new Song(120, 4, List.of(
                new DrumTrack(Drum.KICK, "X...", 1.0f),
                new MelodyTrack(List.of(new Note(0.0, new Voice.Pitch(60), 4.0, 1.0f)), 4.0, 1.0f)));
        LivePitchSynth synth = constantLevelSynth(level);
        var renderer = engine().liveRenderer(() -> new AudioEngine.Jam(song, new LivePitchSynth() {
            @Override
            public Sample render(int midiNote, int frameCount, int sampleRate) {
                return synth.render(midiNote, frameCount, sampleRate);
            }

            @Override
            public VoiceSource voice(int midiNote, int heldFrames, int sampleRate) {
                return synth.voice(midiNote, heldFrames, sampleRate);
            }

            @Override
            public AudioEffect effects(int sampleRate) {
                return (input, stereoOut) -> {
                    stereoOut[0] = input * 2;
                    stereoOut[1] = input * 2;
                };
            }
        }), false);
        float[] mix = new float[BLOCK * 2];

        renderer.renderNext(mix);

        // the kick (1.0) went straight to the mix; only the voice's 0.25 was doubled
        assertEquals(1.5f, mix[0], 1e-6f);
        assertEquals(0.5f, mix[2], 1e-6f);
    }

    @Test
    void everyKickReachesTheSynthsEffectsOnItsOwnFrame() {
        var level = new AtomicReference<>(0.25f);
        Song song = new Song(120, 4, List.of(
                new DrumTrack(Drum.KICK, "X.X.", 1.0f),
                new DrumTrack(Drum.SNARE, ".X.X", 1.0f),
                new MelodyTrack(List.of(new Note(0.0, new Voice.Pitch(60), 4.0, 1.0f)), 4.0, 1.0f)));
        LivePitchSynth synth = constantLevelSynth(level);
        var ducks = new ArrayList<Integer>();
        var processed = new AtomicInteger();
        // one synth for the whole session, as a player hands it: a new one would get a channel of its own
        LivePitchSynth ducking = new LivePitchSynth() {
            @Override
            public Sample render(int midiNote, int frameCount, int sampleRate) {
                return synth.render(midiNote, frameCount, sampleRate);
            }

            @Override
            public VoiceSource voice(int midiNote, int heldFrames, int sampleRate) {
                return synth.voice(midiNote, heldFrames, sampleRate);
            }

            @Override
            public AudioEffect effects(int sampleRate) {
                return new AudioEffect() {
                    @Override
                    public void process(float input, float[] stereoOut) {
                        processed.incrementAndGet();
                        stereoOut[0] = input;
                        stereoOut[1] = input;
                    }

                    @Override
                    public void duck() {
                        ducks.add(processed.get());
                    }
                };
            }
        };
        var renderer = engine().liveRenderer(() -> new AudioEngine.Jam(song, ducking), false);

        render(renderer, BLOCKS);

        // the kicks only, never the snares, each one just before the frame it lands on
        assertEquals(List.of(0, 1_000, 2_000, 3_000), ducks);
    }

    @Test
    void everyTrackIsPlayedByItsOwnSynthThroughItsOwnEffects() {
        LivePitchSynth lead = amplifiedSynth(0.25f, 2);
        LivePitchSynth bass = amplifiedSynth(0.125f, 4);
        Song song = new Song(120, 4, List.of(
                new MelodyTrack(List.of(new Note(0.0, new Voice.Pitch(72), 1.0, 1.0f)), 4.0, 1.0f),
                new MelodyTrack(List.of(new Note(2.0, new Voice.Pitch(36), 1.0, 1.0f)), 4.0, 1.0f)));
        var renderer = engine().liveRenderer(() -> new AudioEngine.Jam(song, List.of(lead, bass)), false);

        float[] left = render(renderer, BLOCKS);

        assertEquals(0.5f, left[200], 1e-6f, "the lead, doubled by its own effects");
        assertEquals(0.5f, left[1_200], 1e-6f, "the bass, four times over by its own, not the lead's");
    }

    @Test
    void aKeyPressedWhileTheJamIsStoppedIsHeardThroughItsSynthsOwnEffects() {
        LivePitchSynth synth = amplifiedSynth(0.25f, 2);
        Song song = new Song(120, 4, List.of(
                new MelodyTrack(List.of(new Note(0.0, new Voice.Pitch(60), 1.0, 1.0f)), 4.0, 1.0f)));
        var renderer = engine().idleRenderer(() -> new AudioEngine.Jam(song, synth));

        float[] quiet = render(renderer, 3);
        renderer.audition(synth, 72, 0.2, 1.0f);
        float[] heard = render(renderer, 3);

        assertEquals(0.0f, quiet[100], "an idle renderer plays no loop of its own");
        assertEquals(0.5f, heard[10], 1e-6f, "the key, doubled by that synth's own effects");
    }

    @Test
    void anAuditionedNoteIsHeardOverAJamThatIsPlaying() {
        LivePitchSynth synth = amplifiedSynth(0.25f, 1);
        Song song = new Song(120, 4, List.of(
                new MelodyTrack(List.of(new Note(0.0, new Voice.Pitch(60), 0.25, 1.0f)), 4.0, 0.0f)));
        var renderer = engine().liveRenderer(() -> new AudioEngine.Jam(song, synth), false);

        float[] muted = render(renderer, 2);
        renderer.audition(synth, 72, 0.2, 1.0f);
        float[] heard = render(renderer, 2);

        assertEquals(0.0f, muted[100], "the track is muted, so the loop itself is silent");
        assertEquals(0.25f, heard[10], 1e-6f, "a key is listened to, whatever the fader says");
    }

    @Test
    void aSynthTheJamHasLetGoOfClosesItsChannelOnceItsTailIsOver() {
        LivePitchSynth first = amplifiedSynth(0.25f, 1);
        LivePitchSynth second = amplifiedSynth(0.25f, 1);
        Song song = new Song(120, 4, List.of(
                new MelodyTrack(List.of(new Note(0.0, new Voice.Pitch(60), 0.5, 1.0f)), 4.0, 1.0f)));
        var jam = new AtomicReference<>(new AudioEngine.Jam(song, first));
        var renderer = engine().liveRenderer(jam::get, false);

        render(renderer, 5);
        jam.set(new AudioEngine.Jam(song, second));
        render(renderer, 20);

        // the second loop, from frame 2000, is the new synth's; the first falls quiet at 250 and goes
        assertEquals(1, renderer.busCount());
    }

    /** A synth whose voices play a constant {@code level}, through effects that multiply it by {@code factor}. */
    private static LivePitchSynth amplifiedSynth(float level, float factor) {
        return new LivePitchSynth() {
            @Override
            public Sample render(int midiNote, int frameCount, int sampleRate) {
                throw new AssertionError("a live synth's melody should not be rendered in advance");
            }

            @Override
            public VoiceSource voice(int midiNote, int heldFrames, int sampleRate) {
                return new VoiceSource() {
                    private int frame;

                    @Override
                    public float next() {
                        frame++;
                        return level;
                    }

                    @Override
                    public boolean finished() {
                        return frame >= heldFrames;
                    }
                };
            }

            @Override
            public AudioEffect effects(int sampleRate) {
                return (input, stereoOut) -> {
                    stereoOut[0] = input * factor;
                    stereoOut[1] = input * factor;
                };
            }
        };
    }

    /** A synth whose voices simply play whatever {@code level} says at that frame. */
    private static LivePitchSynth constantLevelSynth(AtomicReference<Float> level) {
        return new LivePitchSynth() {
            @Override
            public Sample render(int midiNote, int frameCount, int sampleRate) {
                throw new AssertionError("a live synth's melody should not be rendered in advance");
            }

            @Override
            public VoiceSource voice(int midiNote, int heldFrames, int sampleRate) {
                return new VoiceSource() {
                    private int frame;

                    @Override
                    public float next() {
                        frame++;
                        return level.get();
                    }

                    @Override
                    public boolean finished() {
                        return frame >= heldFrames;
                    }
                };
            }
        };
    }

    private static AudioEngine engine() {
        var samples = SampleBank.from(Map.of(
                Drum.KICK, Sample.mono(1.0f),
                Drum.SNARE, Sample.mono(1.0f)
        ));
        return new AudioEngine(samples, 1_000, BLOCK, 8);
    }

    /** A jam whose tracks all go out to an external synth, on channel 1. */
    private static AudioEngine.Jam outward(Song song) {
        return new AudioEngine.Jam(song, List.of(NO_SYNTH), List.of(0));
    }

    private static AudioEngine.Jam jam(Song song) {
        return new AudioEngine.Jam(song, NO_SYNTH);
    }

    private static <T> Supplier<T> firstThen(T first, T rest) {
        var calls = new AtomicInteger();
        return () -> calls.getAndIncrement() == 0 ? first : rest;
    }

    /** What the renderer has queued for an external synth, in the order it would be sent. */
    private static List<AudioEngine.ExternalNote> drain(AudioEngine.LiveRenderer renderer) {
        var sent = new ArrayList<AudioEngine.ExternalNote>();
        AudioEngine.ExternalNote note;
        while ((note = renderer.externalMelody().poll()) != null) {
            sent.add(note);
        }
        return sent;
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

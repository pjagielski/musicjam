package pl.livecoding.musicjam;

import pl.livecoding.musicjam.audio.AudioEngine;
import pl.livecoding.musicjam.audio.SampleBank;
import pl.livecoding.musicjam.midi.MidiFileReader;
import pl.livecoding.musicjam.midi.MidiPlayer;
import pl.livecoding.musicjam.model.Drum;
import pl.livecoding.musicjam.model.DrumTrack;
import pl.livecoding.musicjam.model.MelodyTrack;
import pl.livecoding.musicjam.model.Note;
import pl.livecoding.musicjam.model.PatternCompiler;
import pl.livecoding.musicjam.model.Song;
import pl.livecoding.musicjam.model.Track;
import pl.livecoding.musicjam.model.Voice;
import pl.livecoding.musicjam.synth.AnthemLeadSynth;
import pl.livecoding.musicjam.synth.PitchSynth;
import pl.livecoding.musicjam.synth.TrancePluckSynth;
import pl.livecoding.musicjam.synth.WidePadSynth;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Plays one MIDI phrase and a drum pattern together as a single {@link Song} — a
 * {@link DrumTrack} tiles to match a {@link MelodyTrack}'s length, so both layers compile and
 * render (or play out over MIDI) as one thing. See {@code CONTEXT.md} and the "Suggested
 * workshop path" in README.md for how the rest of this codebase builds up to this.
 */
public final class BeatApp {
    private static final double BEATS_PER_BAR = 4.0;
    private static final Path SAMPLE_DIRECTORY = Path.of("samples");
    private static final Map<String, PitchSynth> SYNTHS = Map.of(
            "anthem", new AnthemLeadSynth(),
            "pad", new WidePadSynth(),
            "pluck", new TrancePluckSynth()
    );
    private static final Map<String, List<DrumTrack>> DRUM_PATTERNS = Map.of(
            "shape", shapeDrumTracks(),
            "worry", worryDrumTracks(),
            "dre", dreDrumTracks(),
            "giorgio", giorgioDrumTracks()
    );

    private BeatApp() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "--help".equals(args[0])) {
            printHelp();
            return;
        }
        if ("--config".equals(args[0])) {
            requireArg(args, "--config <file.properties>");
            playJam(PhraseRequest.fromPropertiesFile(Path.of(args[1])));
            return;
        }
        playJam(requestFromArgs(args));
    }

    private static PhraseRequest requestFromArgs(String[] args) {
        PhraseRequest.Builder builder = PhraseRequest.forFile(args[0]);
        if (args.length > 1) {
            builder.track(Integer.parseInt(args[1]));
        }
        if (args.length > 2) {
            builder.fromBar(Integer.parseInt(args[2]));
        }
        if (args.length > 3) {
            builder.bars(Integer.parseInt(args[3]));
        }
        if (args.length > 4) {
            builder.loops(Integer.parseInt(args[4]));
        }
        if (args.length > 5) {
            builder.synth(args[5]);
        }
        if (args.length > 6) {
            builder.midiDevice(args[6]);
        }
        return builder.build();
    }

    private static void requireArg(String[] args, String usage) {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: " + usage);
        }
    }

    static void playJam(PhraseRequest request) throws Exception {
        Sequence sequence = MidiSystem.getSequence(request.file().toFile());
        double bpm = MidiFileReader.readTempo(sequence);
        int program = MidiFileReader.readProgram(sequence, request.trackIndex());
        MelodyTrack melodyTrack = loadMelodyTrack(sequence, request);

        List<Track> tracks = new ArrayList<>(drumTracksFor(request.drums()));
        tracks.add(melodyTrack);
        Song song = new Song(bpm, (int) BEATS_PER_BAR, tracks);

        System.out.printf(
                "Jam: %s (sciezka %d, takty %d-%d, %.1f BPM), petla x%d%n",
                request.file().getFileName(), request.trackIndex(), request.startBar() + 1,
                request.startBar() + request.bars(), bpm, request.loops());
        System.out.printf("  %d nut melodii w petli (%.1f beatow)%n",
                melodyTrack.notes().size(), melodyTrack.patternLengthBeats());

        if (request.midiDevice() != null) {
            playJamWithExternalMelody(song, bpm, program, request);
            return;
        }

        PitchSynth synth = resolveSynth(request.synth());
        SampleBank samples = SampleBank.load(SAMPLE_DIRECTORY, AudioEngine.DEFAULT_SAMPLE_RATE);
        AudioEngine engine = new AudioEngine(samples);
        engine.play(song, request.loops(), synth);
    }

    /**
     * Only the melody layer goes to the external device — {@code Drum.gmPercussionNote()} values
     * (36, 38, 42, ...) only sound like drums on GM channel 10; sent on the melody's channel with
     * the melody's patch they're just very low pitched notes of that patch. So drums keep playing
     * natively through {@link AudioEngine}, on their own thread, alongside the MIDI melody.
     */
    private static void playJamWithExternalMelody(Song song, double bpm, int program, PhraseRequest request)
            throws Exception {
        List<Note> notes = PatternCompiler.compile(song);
        double totalBeats = PatternCompiler.totalBeats(song);
        List<Note> melodyNotes = notes.stream().filter(note -> note.voice() instanceof Voice.Pitch).toList();
        List<Note> drumNotes = notes.stream().filter(note -> note.voice() instanceof Drum).toList();

        System.out.printf("  wyjscie: MIDI urzadzenie \"%s\" (melodia), perkusja lokalnie%n", request.midiDevice());

        AtomicReference<Exception> drumFailure = new AtomicReference<>();
        Thread drumThread = Thread.ofVirtual().start(() -> {
            try {
                SampleBank samples = SampleBank.load(SAMPLE_DIRECTORY, AudioEngine.DEFAULT_SAMPLE_RATE);
                new AudioEngine(samples).play(drumNotes, bpm, totalBeats, request.loops());
            } catch (Exception exception) {
                drumFailure.set(exception);
            }
        });

        try (MidiPlayer player = MidiPlayer.openDevice(request.midiDevice(), 0, program)) {
            player.playLoop(melodyNotes, bpm, totalBeats, request.loops());
        } finally {
            drumThread.join();
        }
        if (drumFailure.get() != null) {
            throw drumFailure.get();
        }
    }

    private static PitchSynth resolveSynth(String name) {
        PitchSynth synth = SYNTHS.get(name.toLowerCase());
        if (synth == null) {
            throw new IllegalArgumentException("Unknown synth \"" + name + "\", expected one of " + SYNTHS.keySet());
        }
        return synth;
    }

    private static MelodyTrack loadMelodyTrack(Sequence sequence, PhraseRequest request) {
        double startBeat = request.startBar() * BEATS_PER_BAR;
        double patternLength = request.bars() * BEATS_PER_BAR;
        double endBeat = startBeat + patternLength;
        List<Note> notes = MidiFileReader.readTrack(sequence, request.trackIndex()).stream()
                .filter(note -> note.beat() >= startBeat && note.beat() < endBeat)
                .map(note -> new Note(note.beat() - startBeat, note.voice(), note.durationBeats(), note.velocity()))
                .toList();
        return new MelodyTrack(notes, patternLength, 1.0f);
    }

    private static List<DrumTrack> drumTracksFor(String name) {
        List<DrumTrack> pattern = DRUM_PATTERNS.get(name.toLowerCase());
        if (pattern == null) {
            throw new IllegalArgumentException("Unknown drums \"" + name + "\", expected one of " + DRUM_PATTERNS.keySet());
        }
        return pattern;
    }

    // Transcribed from the "Electric Drum Kit" track of shape.mid (bars 3+, once the groove kicks in).
    private static List<DrumTrack> shapeDrumTracks() {
        return List.of(
                new DrumTrack(Drum.KICK, "o..o....o..o....", 1.00f),
                new DrumTrack(Drum.CLOSED_HAT, "o.ooo.o.o.o.oooo", 0.60f),
                new DrumTrack(Drum.SNARE, "......o.......o.", 0.80f)
        );
    }

    // Transcribed from the "Drums" track (channel 10) of Don't_You_Worry_Child.mid, bar 40 (the drop).
    private static List<DrumTrack> worryDrumTracks() {
        return List.of(
                new DrumTrack(Drum.KICK, "X...X...X...X...", 1.00f),
                new DrumTrack(Drum.SNARE, "....X.......X...", 0.80f),
                new DrumTrack(Drum.CLOSED_HAT, "...X..X....X..X.", 0.55f),
                new DrumTrack(Drum.OPEN_HAT, "..X...X...X...X.", 0.45f)
        );
    }

    // Transcribed from the "Da Dope Beat" track (channel 10) of Still_Dre.mid, bar 10 — the groove
    // is identical for nearly the whole song, so any settled bar gives the same pattern.
    private static List<DrumTrack> dreDrumTracks() {
        return List.of(
                new DrumTrack(Drum.KICK, "X.....X.X.......", 1.00f),
                new DrumTrack(Drum.SNARE, "....X.......X...", 0.85f),
                new DrumTrack(Drum.CLOSED_HAT, "....X.......X...", 0.45f),
                new DrumTrack(Drum.OPEN_HAT, "..X.......X.....", 0.55f)
        );
    }

    // GiorgiobyMoroder.mid has no drum track at all in the source MIDI (a single arpeggio track) —
    // this is a plain four-on-the-floor pattern in the disco/italo style the song evokes, not a transcription.
    private static List<DrumTrack> giorgioDrumTracks() {
        return List.of(
                new DrumTrack(Drum.KICK, "X...X...X...X...", 1.00f),
//                new DrumTrack(Drum.CLAP, "....X.......X...", 0.75f),
                new DrumTrack(Drum.CLOSED_HAT, "x.x.x.x.x.x.x.x.", 0.50f),
                new DrumTrack(Drum.CLOSED_HAT, ".x.x.x.x.x.x.x.x", 0.25f)
        );
    }

    private static void printHelp() {
        System.out.println("""
                MusicJam: a MIDI phrase and a drum pattern, played together as one Song

                  ./gradlew run --args="sciezka/do/pliku.mid"
                  ./gradlew run --args="sciezka/do/pliku.mid 1 0 2 4"
                  ./gradlew run --args="sciezka/do/pliku.mid 1 0 2 4 pad"
                  ./gradlew run --args="sciezka/do/pliku.mid 1 0 2 4 anthem loopMIDI"
                  ./gradlew run --args="--config jam.properties"

                Argumenty: plik.mid [trackIndex=1] [startBar=0] [bars=2] [loops=4] [synth=anthem] [midiDevice]

                Syntezatory melodii (argument [synth]): anthem (domyslny), pad, pluck

                Plik .properties dla --config (tylko "file" wymagany):
                  file=sciezka/do/pliku.mid
                  track=1
                  fromBar=0
                  bars=2
                  loops=4
                  synth=anthem
                  drums=shape
                  midiDevice=loopMIDI

                Wzorce perkusji ("drums" w pliku .properties, nie ma jako argument CLI): shape (domyslny), worry, dre, giorgio

                To samo programistycznie: PhraseRequest.forFile("plik.mid").track(1).fromBar(0)
                  .bars(2).loops(4).synth("anthem").playJam();
                """);
    }
}

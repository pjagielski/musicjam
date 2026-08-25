# MusicJam

A small Java 25 jam app — a MIDI melody and a drum pattern played together — for a workshop about musical time, scheduler jitter and sample-accurate block rendering. The musical model stays readable while the audio engine places every hit at an exact sample frame.

## Run

Windows:

```powershell
.\gradlew.bat run
.\gradlew.bat run --args="src/main/resources/shape.mid"
.\gradlew.bat run --args="src/main/resources/shape.mid 1 0 2 4"
.\gradlew.bat run --args="--config jam.properties"
.\gradlew.bat test

# Steps 1 and 2 of the workshop path depend on nothing but the JDK's own javax.sound.midi, so
# they run standalone via single-file source-launch (from the repo root):
java src/main/java/pl/livecoding/musicjam/midi/SequencerDemo.java src/main/resources/shape.mid
java src/main/java/pl/livecoding/musicjam/midi/InspectMidi.java src/main/resources/shape.mid
java src/main/java/pl/livecoding/musicjam/midi/ListMidiDevices.java

# Step 3 builds on MidiFileReader/NaivePlayer (real app code, not dependency-free), so it needs
# the project's classpath — a small Gradle task instead of source-launch:
.\gradlew.bat naivePlayerDemo --args="src/main/resources/shape.mid 1 0 2 4"
```

macOS/Linux:

```bash
./gradlew run --args="src/main/resources/shape.mid"
./gradlew test
```

`BeatApp` does exactly one thing: play a MIDI phrase and a drum pattern together as a single `Song`. Run with no arguments (or `--help`) to print full usage — that prints and exits, no audio device is touched.

## Write a beat

Every run pairs a MIDI melody with a drum pattern. `BeatApp.DRUM_PATTERNS` holds a named pattern per workshop fixture — pick one with `drums=` in a `.properties` file (see "Configuring a request" below); `shape` is the default:

```java
private static List<DrumTrack> shapeDrumTracks() {
    return List.of(
            new DrumTrack(Drum.KICK, "o..o....o..o....", 1.00f),
            new DrumTrack(Drum.CLOSED_HAT, "o.ooo.o.o.o.oooo", 0.60f),
            new DrumTrack(Drum.SNARE, "......o.......o.", 0.80f)
    );
}
```

Add a new pattern by writing a `...DrumTracks()` method in the same shape and registering it in `DRUM_PATTERNS`.

Pattern characters:

- `X` — strong hit (100%)
- `x` — normal hit (80%)
- `o` — quiet hit (50%)
- `.`, `-`, space — rest

The number of characters determines the subdivision of one bar. A 16-character pattern uses sixteenth notes; an 8-character pattern uses eighth notes. Different track lengths create different subdivisions of the same bar (polyrhythm), not independent meters.

Each `DrumTrack` is one bar. `PatternCompiler` tiles it to fill the `Song`'s full length, which is set by the longest `MelodyTrack` (see `CONTEXT.md`) — so a short drum loop keeps repeating underneath a longer melodic phrase.

All `DrumTrack`s making up one pattern must share the same pattern-string length — `PatternCompiler` derives the subdivision independently per track (`beatsPerBar / steps.length()`), so a mismatched length quietly puts that track on a different, unaligned grid instead of failing loudly.

## Add samples

Put any of these optional files in `samples/`:

```text
bd.wav  sd.wav  hh.wav  oh.wav  cp.wav
```

Missing files use built-in synthesized sounds, so the project runs without assets. See `samples/README.md` for the mapping and recommended format.

## Architecture

```text
Song (DrumTrack pattern strings + MelodyTrack notes read from a MIDI file)
    -> PatternCompiler: musical Note(beat, voice, durationBeats, velocity)
    -> Transport: absolute beat -> absolute sample frame
    -> AudioEngine: block renderer with an in-block offset
    -> SourceDataLine (live) or streaming WAV output (offline)
```

`AudioEngine` is the seam between workshop code and low-level audio. `render`, `play` and `writeWav` all use the same renderer, so the tested timing is also the timing used for playback.

At 120 BPM a sixteenth note lasts 5512.5 frames at 44.1 kHz. `Transport` calculates every position from the absolute beat, so rounded intervals alternate between 5512 and 5513 frames instead of accumulating drift.

### Package layout

- `pl.livecoding.musicjam` — `BeatApp` (CLI) and `PhraseRequest` (the fluent/config-file entry point)
- `pl.livecoding.musicjam.model` — `Note`, `Voice`, `Drum`, `Song`, `Track`/`DrumTrack`/`MelodyTrack`, `PatternCompiler`: the musical data, independent of how it's played or rendered
- `pl.livecoding.musicjam.audio` — `AudioEngine` and everything it needs to render `Note`s to PCM: `Transport`, `Sample`, `SampleBank`, `DrumSamples`, `RenderedAudio`, WAV I/O
- `pl.livecoding.musicjam.midi` — reading MIDI files (`MidiFileReader`) and playing `Note`s out over MIDI (`NoteOutput`, `MidiNoteOutput`, `ExternalMidiOutput`, `MidiPlayer`, `NaivePlayer`, `TimingReport`), plus the workshop path's runnable steps (`SequencerDemo`, `InspectMidi`, `ListMidiDevices`, `NaivePlayerDemo`)
- `pl.livecoding.musicjam.synth` — `PitchSynth` and its novasaw-derived implementations (`NovasawSynth`, `AnthemLeadSynth`, `TrancePluckSynth`, `WidePadSynth`)

See `CONTEXT.md` for the vocabulary behind these names (why `Phrase` isn't `Melody`, why there are two "players", etc.) — worth reading before renaming anything.

## Jam: a MIDI melody and a drum pattern, played as one Song

`BeatApp` loads a window of bars from a single track of a standard MIDI file, synthesizes it natively, and plays it alongside one of `BeatApp.DRUM_PATTERNS` — both layers compiled and rendered as a single `Song`, through the same sample-accurate `AudioEngine`:

```powershell
.\gradlew.bat run --args="path\to\song.mid 1 0 2 4"
```

Arguments: file path, track index (default `1`), start bar (default `0`, 0-indexed — the window is `[startBar, startBar+bars)`), bars to loop (default `2`, assumes 4/4), number of loop repeats (default `4`), synth (default `anthem`), optional external MIDI device (see below). The CLI has no positional argument for the drum pattern — that's `drums=` in a `.properties` file (see "Configuring a request" below), always `shape` from the CLI. `MidiFileReader` converts note-on/note-off pairs to beats using the file's PPQ resolution and reads tempo from the tempo meta event (default 120 BPM) — that tempo drives the whole `Song`, so the drum layer stays rhythmically locked to the melody. A track normally holds the whole song, not just a repeating phrase — many tracks don't even start at bar 0 (an intro, or a part that only kicks in at the drop) — so `BeatApp` windows it down to the requested bars and rebases beats to the window's start, producing a `MelodyTrack`. Each `DrumTrack` in the chosen pattern is one bar; `PatternCompiler` tiles it to match the `MelodyTrack`'s length (see `CONTEXT.md`).

Not sure which track or bar range to use? The standalone `InspectMidi.java` lists every track in a file with its channel, GM program, note count, pitch range and the bar its first note falls on:

```powershell
java src/main/java/pl/livecoding/musicjam/midi/InspectMidi.java path\to\song.mid
```

Each melodic `Note` carries a `Voice.Pitch(midiNote)`; `AudioEngine` resolves that to a synthesized tone via a `PitchSynth` instead of a sample file, cached per distinct pitch+duration — no MIDI device involved, so there's no software-synthesizer patch-loading glitch on the first note. `AnthemLeadSynth`/`TrancePluckSynth`/`WidePadSynth` are ports of the "Anthem Lead - Mainstage", "Trance Pluck - Classic" and "Wide Pad - Halo" patches from a separate JUCE project (`Sandbox/novasaw`): a 7-voice unison PolyBLEP sawtooth with drift/vibrato, a diode waveshaper and a resonant lowpass driven by the envelope and key tracking. All three extend `NovasawSynth`, which owns the one shared `render(...)` — a patch is nothing but the recipe constants and preset macro knobs passed to its constructor; the low-level DSP primitives (`polyBlepSaw`, `shapeDiode`, the phase-jitter hash, the lowpass filter) live once in `NovasawDsp`. Pick a patch with the `[synth]` argument (`anthem`, the default, `pluck`, or `pad`). novasaw's stereo pan spread and chorus/delay/reverb sends are left out — `AudioEngine`'s `Sample` type is mono and these ports only target one preset each.

MIDI files generally shouldn't be checked into this repository — treat them like WAV samples and point `BeatApp` at a file on disk. `src/main/resources/` is the exception: it holds four workshop fixtures kept here on purpose (`shape.mid` — used to derive the `shape` drum pattern below — plus `Don't_You_Worry_Child.mid`, `GiorgiobyMoroder.mid` and `Still_Dre.mid` as extra material for `InspectMidi.java`/`BeatApp`). Don't add further copyrighted transcriptions the same way without checking you're allowed to.

### Routing the melody to an external MIDI device

An optional trailing argument (after `loops` and `synth`) redirects the melody layer to an external MIDI device (e.g. a standalone synth like Surge XT, reached over a virtual MIDI cable such as loopMIDI) instead of native synthesis — matched by a case-insensitive substring of the device name:

```powershell
java src/main/java/pl/livecoding/musicjam/midi/ListMidiDevices.java
.\gradlew.bat run --args="src/main/resources/shape.mid 1 0 2 4 anthem loopMIDI"
```

### Configuring a request without juggling positional arguments

`BeatApp` takes up to 7 positional arguments (`file trackIndex startBar bars loops synth midiDevice`) — easy to lose count of. `PhraseRequest` is the object `BeatApp` builds internally from those arguments; it's also a public, standalone way to configure the same thing, either fluently in Java:

```java
PhraseRequest.forFile("src/main/resources/shape.mid")
        .track(1)
        .fromBar(0)
        .bars(2)
        .loops(4)
        .synth("pad")
        .playJam();
```

or from a `.properties` file (only `file` is required, everything else falls back to the same defaults as the CLI):

```properties
file=src/main/resources/shape.mid
track=1
fromBar=0
bars=2
loops=4
synth=pad
drums=shape
midiDevice=loopMIDI
```

```powershell
.\gradlew.bat run --args="--config jam.properties"
```

`src/main/resources/` has one ready-made `.properties` file per workshop fixture, each pointing at a melody window and drum pattern (`BeatApp.DRUM_PATTERNS`) transcribed from (or, for `giorgio`, invented for) that same file: `jam.properties`/`worry.properties` (`shape.mid`/`Don't_You_Worry_Child.mid`), `dre.properties` (`Still_Dre.mid`), `giorgio.properties` (`GiorgiobyMoroder.mid`, which has no drum track in its source MIDI, so its pattern is a plain four-on-the-floor rather than a transcription).

`ListMidiDevices.java` prints every `MidiDevice` Java Sound can see, so you can find the exact name after setting up the virtual cable. Only the melody layer is redirected — `Drum.gmPercussionNote()` values only mean "drum" on GM channel 10, so drums keep rendering natively through `AudioEngine` on their own thread while the melody goes out over `MidiPlayer`/`NoteOutput` (`ExternalMidiOutput`, a sibling of `MidiNoteOutput` that sends raw `ShortMessage`s to any `MidiDevice.Receiver` instead of a `Synthesizer`'s `MidiChannel`) — same absolute-time thread scheduling as before, just pointed at a real device instead of Gervill. `ExternalMidiOutput` also registers a JVM shutdown hook that sends "All Sound Off" (CC 120), so killing the app (Ctrl+C) doesn't leave a note stuck ringing on the external synth. Verified against Surge XT over loopMIDI.

`shapeDrumTracks()`'s pattern (kick on beats 1 & 3, snare on 2 & 4, syncopated closed-hat in between) is transcribed from bar 3 onward of `shape.mid`'s "Electric Drum Kit" track — the intro bars are sparse, so extracting from bar 1 gave an empty pattern.

## Platform threads versus virtual threads

`NaivePlayer` deliberately creates one waiting thread per hit, like the one-coroutine-per-note approach from the original tracker article. Its `ThreadKind` (`PLATFORM` or `VIRTUAL`) picks the thread flavor without touching the timing calculation or MIDI output — `NaivePlayerTest` exercises both against the same pattern and absolute target times. `NaivePlayerDemo` (see "Run" above) plays a real MIDI file through it, hardcoded to `PLATFORM`; for the platform-versus-virtual comparison itself, run the test or follow "A 20-minute thread experiment" below.

The resulting `TimingReport` separates thread startup cost from wake-up lateness:

```text
PLATFORM: setup=... us, jitter min=... us, avg=... us, max=... us
VIRTUAL:  setup=... us, jitter min=... us, avg=... us, max=... us
```

Virtual threads make it much cheaper to keep many blocking tasks alive. They do not make musical events sample-accurate: `Thread.sleep` still wakes through the JVM and operating-system scheduler. The block renderer avoids that wake-up deadline entirely by putting each transient at its target position in PCM before playback.

## Suggested workshop path (3 hours)

Each step exists to answer a question the previous one raised — the path is designed to be followed in order, not picked from. `BeatApp` only plays a jam now, so steps 1-2 run through `SequencerDemo.java`/`InspectMidi.java`/`ListMidiDevices.java` — dependency-free beyond the JDK's own `javax.sound.midi`, so they run via plain single-file source-launch (`InspectMidi.java` also doubles as the worked answer for the tick → beat exercise). Step 3's `NaivePlayer` is real app code (it shares `Note`/`Voice` with everything else, see "Precision" below), so it needs the project's classpath: drive it from `NaivePlayerTest`, from `NaivePlayerDemo` (a small Gradle task, not source-launch — see "Run" above), or from a throwaway `main` written live, until step 7 folds everything back into `BeatApp`.

1. **The black box (5 min).** Run `java src/main/java/pl/livecoding/musicjam/midi/SequencerDemo.java path/to/song.mid` — four lines, `javax.sound.midi` plays a whole song, no code of ours involved. Everything that follows exists to answer "what is this actually doing, and can we do better?"
2. **Take the file apart (20-30 min).** `java src/main/java/pl/livecoding/musicjam/midi/InspectMidi.java path/to/song.mid` a real MIDI file: PPQ, tracks, channels, GM programs, note ranges, which bar each track's first note falls on. Participants implement the tick → beat conversion themselves (this is `MidiFileReader`'s core move) before looking at the real implementation.
3. **Write your own player (25-35 min).** `Sequencer` only plays a `Sequence` it was handed — it has no way to play the `List<Note>` step 2 just produced. So write one: `NaivePlayer` schedules one waiting thread per note and fires it at an absolute `System.nanoTime()` target, still through the same built-in software synthesizer as step 1 (`MidiNoteOutput`) — no drums yet, just melody, so the only thing that changed is who's doing the scheduling. Run it live with `.\gradlew.bat naivePlayerDemo --args="path\to\song.mid"` (`NaivePlayerDemo`, step 3's answer, playing a MIDI file's melody window through `NaivePlayer`). Time one platform thread per hit against one virtual thread per hit (see "A 20-minute thread experiment" below) and measure the jitter on both — neither is sample-accurate, that's the point being set up for step 6.
4. **— break (10-15 min) —**
5. **Write your own pattern (20-25 min).** `Note(beat, voice, durationBeats, velocity)` is a familiar shape by now — build `Song`, `DrumTrack` (implementing the sealed `Track`) and `PatternCompiler` as a second way to produce the same shape, by hand from a compact string instead of from a file, and feed it into the same `NaivePlayer` from step 3.
6. **Precision (20-30 min).** `NaivePlayer` has jitter. Implement `Transport.frameAtBeat` and compare block-edge quantization against `AudioEngineTest`'s exact-offset assertions — `AudioEngine` sidesteps the wake-up-deadline problem entirely by placing every transient at its target sample before playback starts.
7. **Put it together (20-30 min).** `BeatApp` loads a melody window into a `MelodyTrack` and plays it alongside step 5's `DrumTrack`s as one `Song` — `PatternCompiler` tiles the drum bar to the melody's length (see `CONTEXT.md`), and `AudioEngine` renders both. Circle back to step 1: `SequencerDemo`/Gervill can glitch on a synth's first note (a real, diagnosed bug — the built-in software synthesizer loads an instrument's patch lazily on its first `noteOn`, see `MidiNoteOutput.warmUp`'s comment); native synthesis through `AudioEngine` never touches an external synth at all, so the glitch doesn't exist there by construction.

The original single-file proof of concept remains in `Beat.java` for comparison. `src/main/resources/` has four MIDI files to explore beyond the workshop's own `shape.mid` fixture — `Don't_You_Worry_Child.mid`, `GiorgiobyMoroder.mid` and `Still_Dre.mid` aren't wired into any specific exercise, they're just more material to point participants at for steps 2/3/5/7.

### A 20-minute thread experiment

1. **5 minutes:** create one platform thread per hit. Start every thread behind the same `CountDownLatch`, then let it sleep until an absolute `System.nanoTime()` target.
2. **5 minutes:** run two bars and record the number of threads, setup time and min/average/max wake-up lateness.
3. **3 minutes:** replace the builder with `Thread.ofVirtual()`; do not change the timing calculation or MIDI output.
4. **4 minutes:** run the same pattern again and compare. Increase the number of bars if the machine can handle the platform-thread variant.
5. **3 minutes:** discuss the result: virtual threads address the cost of blocking concurrency, while the sample renderer addresses the different problem of placing musical events precisely on the audio timeline.

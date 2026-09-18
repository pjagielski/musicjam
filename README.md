# MusicJam

A small Java 25 jam app — a MIDI melody and a drum pattern played together — for a workshop about musical time, scheduler jitter and sample-accurate block rendering. The musical model stays readable while the audio engine places every hit at an exact sample frame.

## Run

```bash
./gradlew run
./gradlew run --args="src/main/resources/song_shape.mid"
./gradlew run --args="src/main/resources/song_shape.mid 1 0 2 4"
./gradlew run --args="--config src/main/resources/jam-dre.properties"
./gradlew test

# Steps 1 and 2 of the workshop path depend on nothing but the JDK's own javax.sound.midi, so
# they run standalone via single-file source-launch (from the repo root):
java src/main/java/pl/livecoding/musicjam/midi/SequencerDemo.java src/main/resources/song_shape.mid
java src/main/java/pl/livecoding/musicjam/midi/InspectMidi.java src/main/resources/song_shape.mid
java src/main/java/pl/livecoding/musicjam/midi/ListMidiDevices.java

# Step 3 builds on MidiFileReader/NaivePlayer (real app code, not dependency-free), so it needs
# the project's classpath — a small Gradle task instead of source-launch:
./gradlew naivePlayerDemo --args="src/main/resources/song_shape.mid 1 0 2 4"

# A window for changing the jam while it plays (JavaFX, fetched by Gradle like any dependency):
./gradlew studio
./gradlew studio --args="--config src/main/resources/jam.properties"
# Large window for a projector; use 1 instead of 0 if that is the desired monitor:
./gradlew studio --args="--presentation --screen 0"
```

`--presentation` opens Studio full screen with larger text; Esc leaves full-screen mode.
`--screen N` chooses a monitor (numbered from 0) and can also be used without presentation mode.
In an IntelliJ run configuration for `StudioLauncher`, put the same flags in **Program arguments**.
You can also use **−**, **+**, **100%**, **Drugi ekran** and **Pełny ekran** in the Studio window,
without changing any IntelliJ settings. The window scrolls when zoomed content no longer fits.

To send the melody to a software or hardware MIDI synth, follow
[External MIDI setup](MIDI_SETUP.md) for Windows, macOS and Linux.

`BeatApp` does exactly one thing: play a MIDI phrase and a drum pattern together as a single `Song`. With no arguments it plays `src/main/resources/jam.properties`; `--help` prints the full usage and exits without touching an audio device.

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
- `pl.livecoding.musicjam.studio` — `BeatStudio`, the JavaFX window over `AudioEngine.playLive`, and `StudioLauncher`, its entry point

See `CONTEXT.md` for the vocabulary behind these names (why `Phrase` isn't `Melody`, why there are two "players", etc.) — worth reading before renaming anything.

## Jam: a MIDI melody and a drum pattern, played as one Song

`BeatApp` loads a window of bars from a single track of a standard MIDI file, synthesizes it natively, and plays it alongside one of `BeatApp.DRUM_PATTERNS` — both layers compiled and rendered as a single `Song`, through the same sample-accurate `AudioEngine`:

```bash
./gradlew run --args="path/to/song.mid 1 0 2 4"
```

Arguments: file path, track index (default `1`), start bar (default `0`, 0-indexed — the window is `[startBar, startBar+bars)`), bars to loop (default `2`, assumes 4/4), number of loop repeats (default `4`), synth (default `anthem`), optional external MIDI device (see below). The CLI has no positional argument for the drum pattern — that's `drums=` in a `.properties` file (see "Configuring a request" below), always `shape` from the CLI. `MidiFileReader` converts note-on/note-off pairs to beats using the file's PPQ resolution and reads tempo from the tempo meta event (default 120 BPM) — that tempo drives the whole `Song`, so the drum layer stays rhythmically locked to the melody. A track normally holds the whole song, not just a repeating phrase — many tracks don't even start at bar 0 (an intro, or a part that only kicks in at the drop) — so `BeatApp` windows it down to the requested bars and rebases beats to the window's start, producing a `MelodyTrack`. Each `DrumTrack` in the chosen pattern is one bar; `PatternCompiler` tiles it to match the `MelodyTrack`'s length (see `CONTEXT.md`).

Not sure which track or bar range to use? The standalone `InspectMidi.java` lists every track in a file with its channel, GM program, note count, pitch range and the bar its first note falls on:

```bash
java src/main/java/pl/livecoding/musicjam/midi/InspectMidi.java path/to/song.mid
```

Each melodic `Note` carries a `Voice.Pitch(midiNote)`; `AudioEngine` resolves that to a synthesized tone via a `PitchSynth` instead of a sample file, cached per distinct pitch+duration — no MIDI device involved, so there's no software-synthesizer patch-loading glitch on the first note. `AnthemLeadSynth`/`TrancePluckSynth`/`WidePadSynth` are ports of the "Anthem Lead - Mainstage", "Trance Pluck - Classic" and "Wide Pad - Halo" patches from a separate JUCE project (`Sandbox/novasaw`): a 7-voice unison PolyBLEP sawtooth with drift/vibrato, a diode waveshaper and a resonant lowpass driven by the envelope and key tracking. All three extend `NovasawSynth`, which owns the one shared `render(...)` — a patch is nothing but the recipe constants and preset macro knobs passed to its constructor; the low-level DSP primitives (`polyBlepSaw`, `shapeDiode`, the phase-jitter hash, the lowpass filter) live once in `NovasawDsp`. Pick a patch with the `[synth]` argument (`anthem`, the default, `pluck`, or `pad`). novasaw's stereo pan spread and chorus/delay/reverb sends are left out — `AudioEngine`'s `Sample` type is mono and these ports only target one preset each.

MIDI files generally shouldn't be checked into this repository — treat them like WAV samples and point `BeatApp` at a file on disk. `src/main/resources/` is the exception: it holds five workshop fixtures kept here on purpose (`song_shape.mid` — used to derive the `shape` drum pattern below — plus `song_child.mid`, `song_giorgioby.mid`, `song_still_dre.mid` and `song_insomnia.mid` as extra material for `InspectMidi.java`/`BeatApp`). `song_insomnia.mid` is cleaned up from the file it was made from: that file said 125 BPM while its notes sit on a 130 BPM grid, so the tempo now says 130 and every tick is stretched to match, and the stray long notes and the notes written twice are gone. Don't add further copyrighted transcriptions the same way without checking you're allowed to.

### Routing the melody to an external MIDI device

An optional trailing argument (after `loops` and `synth`) redirects the melody layer to an external MIDI device (e.g. a standalone synth like Surge XT, reached over a virtual MIDI cable such as loopMIDI) instead of native synthesis — matched by a case-insensitive substring of the device name:

```bash
./gradlew listMidiDevices
./gradlew run --args="src/main/resources/song_shape.mid 1 0 2 4 anthem loopMIDI"
```

The melody goes out on MIDI channel 1 unless `midiChannel=` says otherwise. It counts from 1 to 16,
the way a synth does, while `javax.sound.midi` counts from 0, and a synth's own display may count
either way: a KORG NTS-1 set to "1" played on `midiChannel=2`.

Stopping ends every note: `ExternalMidiOutput.close`, and a shutdown hook for Ctrl+C, shut out any
note-on still on its way, then send All Sound Off and a note-off for every pitch. IntelliJ's Stop on a
program run through Gradle kills the JVM without running shutdown hooks, and a note can keep ringing -
with "Build and run using: IntelliJ IDEA" (Settings, Build Tools, Gradle) Stop runs them. Either way
the next run silences the device before it plays, and so does

```bash
./gradlew midiPanic
```

### Configuring a request without juggling positional arguments

`BeatApp` takes up to 7 positional arguments (`file trackIndex startBar bars loops synth midiDevice`) — easy to lose count of. `PhraseRequest` is the object `BeatApp` builds internally from those arguments; it's also a public, standalone way to configure the same thing, either fluently in Java:

```java
PhraseRequest.forFile("src/main/resources/song_shape.mid")
        .track(1)
        .fromBar(0)
        .bars(2)
        .loops(4)
        .synth("pad")
        .playJam();
```

or from a `.properties` file (only `file` is required, everything else falls back to the same defaults as the CLI):

```properties
file=src/main/resources/song_shape.mid
track=1
fromBar=0
bars=2
loops=4
synth=pad
drums=shape
midiDevice=loopMIDI
midiSync=loop
midiLatency=50
```

```bash
./gradlew run --args="--config src/main/resources/jam-dre.properties"
```

`src/main/resources/` has one ready-made `.properties` file per workshop fixture, each pointing at a melody window and the drum pattern (`BeatApp.DRUM_PATTERNS`) transcribed from — or, for `giorgio`, invented for — that same file:

| config | file | drums |
| --- | --- | --- |
| `jam-shape.properties` | `song_shape.mid` | `shape`, the default, so the file does not name it |
| `jam-child.properties` | `song_child.mid` | `worry` |
| `jam-dre.properties` | `song_still_dre.mid` | `dre` |
| `jam-giorgio.properties` | `song_giorgioby.mid` | `giorgio` — the source MIDI has no drum track, so this one is a plain four-on-the-floor rather than a transcription |
| `jam-insomnia.properties` | `song_insomnia.mid` | `insomnia` — no drum track either: a house groove at half the level, so the synth melody on top does not clip |

`jam.properties`, the config read when none is given, is the same jam as `jam-giorgio.properties`.

`ListMidiDevices.java` prints every `MidiDevice` Java Sound can see, so you can find the exact name after setting up the virtual cable. Only the melody layer is redirected — `Drum.gmPercussionNote()` values only mean "drum" on GM channel 10, so drums keep rendering natively through `AudioEngine` on their own thread while the melody goes out over `MidiPlayer`/`NoteOutput` (`ExternalMidiOutput`, a sibling of `MidiNoteOutput` that sends raw `ShortMessage`s to any `MidiDevice.Receiver` instead of a `Synthesizer`'s `MidiChannel`) — pointed at a real device instead of Gervill. `ExternalMidiOutput` also registers a JVM shutdown hook that sends "All Sound Off" (CC 120), so killing the app (Ctrl+C) doesn't leave a note stuck ringing on the external synth. Verified against Surge XT over loopMIDI.

The drums play on the audio device's clock and the melody on `System.nanoTime()`, and a stall on either side — a GC pause, a busy machine, samples loading slowly — would leave them apart for the rest of the jam. So the melody follows the drums, in one of two ways picked with `midiSync`:

- `loop` (the default): `MidiPlayer` still schedules notes by `System.nanoTime()`, but at the start of every loop it asks `AudioEngine.heardNanos()` how much of the drums has been heard, works out when they started, and schedules that loop from there. A stall is caught up with at the next loop.
- `live`: drums and melody play through `AudioEngine.playLive`, the same `LiveSession` as the studio, and each MIDI note goes out when the audio device reaches its frame, so a stall is caught up with note by note.

Either way the melody goes out `midiLatency` milliseconds early — 50 by default. It is how much later the synth's path to the speaker is than the drums': Surge XT over loopMIDI took 35–50 ms, on a Mac 75, and Gervill over 200, because it buffers 120 ms of audio and corrects its own jitter on top. It depends on the synth and on its buffer size, so find your own - by ear, with the studio's synth latency slider - and set the key.

`shapeDrumTracks()`'s pattern (kick on beats 1 & 3, snare on 2 & 4, syncopated closed-hat in between) is transcribed from bar 3 onward of `song_shape.mid`'s "Electric Drum Kit" track — the intro bars are sparse, so extracting from bar 1 gave an empty pattern.

## Studio: change the jam while it plays

`BeatStudio` opens the jam `BeatApp` would play from a `.properties` file, in a window where it keeps playing while you change it. It starts with a four-bar loop and the drums drawn from the code in the editor (see "Live coding in the studio" below); picking a jam preset brings in that jam's own drums and loop length:

- a step grid per drum track — click a cell to cycle rest → `x` → `X` → `o`; the highlighted column is the step you are hearing,
- jam presets: every `jam*.properties` next to the starting config; picking one loads its MIDI file and melody window, the file's tempo, its drum pattern and its synth,
- tempo, and the loop length, from 32 bars down to 1/16 of a bar for a hard stutter; the melody window is re-read from the MIDI file, and the drum bar is cut off where the loop ends,
- melody on/off and volume,
- an external MIDI device: once connected, the melody can be routed to it, and the filter slider sends a control change — CC 74 by default, which most synths map to cutoff; others need MIDI learn. An external synth sounds a note only after its own audio buffer, so the melody is sent early by the synth latency slider, which starts at the jam's `midiLatency` (50 ms by default) and goes up to 400 ms, enough for Gervill.

Every change lands on the next loop boundary rather than immediately: `AudioEngine.playLive` compiles each loop from whatever `Song` the window last published, so within a loop every hit is still placed at its exact sample frame. The status line says when an edit is waiting for the loop to come round; shorten the loop if that wait is too long.

Routed to an external synth, melody notes are not scheduled by a sleeping thread as in `BeatApp`'s MIDI mode: the audio thread queues each note with its frame, and it is sent when the audio device's playback position reaches that frame, so the synth and the drums share one clock.

## Live coding in the studio

Under the grid the studio has a code editor. Press Ctrl+Enter (or "Uruchom") and the code is drawn into the grid, one row for each sound in each layer, and played from there:

```js
$: stack(
    s("bd(3,8,5)"),
    s("[~ sd]*2").gain(1.25),
    s("hh*16").gain("[0.2 0.1]*8"),
    s("[~ oh ~ oh]*2").gain(0.45).rel(0.1).dec(0.2)
  )
```

It is a small language in the style of Strudel, written for this project: a subset, not Strudel itself.

The drums above come from `strudel/feb-remix.js`, a whole track written in Strudel itself: those drums
on a TR-707, a sliced riff, a supersaw bass, chords and an arpeggio over D - G - Bm - A at 130 BPM.
Paste it into [strudel.cc](https://strudel.cc) to hear where the studio's example came from; it loads
its samples from `github:mistipher/studel-beats`.

- `s("...")` (or `sound`) is a mini-notation pattern of drum names: `bd`, `sd`, `hh`, `oh`, `cp`. Steps separated by spaces share one cycle, and one cycle is one bar. `~` is a rest, `[ ]` groups steps into one, `,` inside brackets layers sequences, `*n` plays a step n times within its slot, and `(k,n)` or `(k,n,r)` spreads k hits evenly over n slots (Bjorklund), rotated r slots to the left.
- `stack(...)` plays patterns together, and so do several `$:` blocks.
- `.gain(0.5)` takes a number, `.gain("[0.2 0.1]*8")` a pattern read wherever the sound pattern plays; anything above 1 is capped at 1.
- `.dec(seconds)` and `.rel(seconds)` shape the sample itself: a decay to silence, and a fade-out once the step has ended. The shaping happens before playback, so it works the same on the synthesized drums and on WAV files.
- `//` comments out the rest of a line, which is the quickest way to mute a layer; Ctrl+/ comments or uncomments every line the cursor or selection is on.

A new pattern starts with the next loop, like every other edit. Code with a mistake is not applied: the error shows its line and column, and the previous pattern keeps playing.

The grid shows what the code plays, with each step's gain as its colour, and a row gets more than 16 steps when the code subdivides further (`[sd sd sd]` draws six). Clicking a step tweaks that drawing, not the code: the tweak plays until the code is run again, which draws the grid afresh. Picking a jam preset replaces the grid with that jam's drums.

`LiveCode.notes` is the same move as `PatternCompiler` and `MidiFileReader` make: a third way of producing `Note`s, this time from text typed while the music plays.

## Platform threads versus virtual threads

`NaivePlayer` deliberately creates one waiting thread per hit, like the one-coroutine-per-note approach from the original tracker article. Its `ThreadKind` (`PLATFORM` or `VIRTUAL`) picks the thread flavor without touching the timing calculation or MIDI output — `NaivePlayerTest` exercises both against the same pattern and absolute target times. `NaivePlayerDemo` (see "Run" above) plays a real MIDI file through it, hardcoded to `PLATFORM`; for the platform-versus-virtual comparison itself, run the test or follow "A 20-minute thread experiment" below.

The resulting `TimingReport` separates thread startup cost from wake-up lateness:

```text
PLATFORM: setup=... us, jitter min=... us, avg=... us, max=... us
VIRTUAL:  setup=... us, jitter min=... us, avg=... us, max=... us
```

Virtual threads make it much cheaper to keep many blocking tasks alive. They do not make musical events sample-accurate: `Thread.sleep` still wakes through the JVM and operating-system scheduler. The block renderer avoids that wake-up deadline entirely by putting each transient at its target position in PCM before playback.

## Suggested workshop path (3 hours)

For the person running it: [`docs/sciagi/`](docs/sciagi/) has the workshop programme and the step-by-step solutions as PDFs to read on a tablet (in Polish).

Each step exists to answer a question the previous one raised — the path is designed to be followed in order, not picked from. `BeatApp` only plays a jam now, so steps 1-2 run through `SequencerDemo.java`/`InspectMidi.java`/`ListMidiDevices.java` — dependency-free beyond the JDK's own `javax.sound.midi`, so they run via plain single-file source-launch (`InspectMidi.java` also doubles as the worked answer for the tick → beat exercise). Step 3's `NaivePlayer` is real app code (it shares `Note`/`Voice` with everything else, see "Precision" below), so it needs the project's classpath: drive it from `NaivePlayerTest`, from `NaivePlayerDemo` (a small Gradle task, not source-launch — see "Run" above), or from a throwaway `main` written live, until step 7 folds everything back into `BeatApp`.

1. **The black box (5 min).** Run `java src/main/java/pl/livecoding/musicjam/midi/SequencerDemo.java path/to/song.mid` — four lines, `javax.sound.midi` plays a whole song, no code of ours involved. Everything that follows exists to answer "what is this actually doing, and can we do better?"
2. **Take the file apart (20-30 min).** `java src/main/java/pl/livecoding/musicjam/midi/InspectMidi.java path/to/song.mid` a real MIDI file: PPQ, tracks, channels, GM programs, note ranges, which bar each track's first note falls on. Participants implement the tick → beat conversion themselves (this is `MidiFileReader`'s core move) before looking at the real implementation.
3. **Write your own player (25-35 min).** `Sequencer` only plays a `Sequence` it was handed — it has no way to play the `List<Note>` step 2 just produced. So write one: `NaivePlayer` schedules one waiting thread per note and fires it at an absolute `System.nanoTime()` target, still through the same built-in software synthesizer as step 1 (`MidiNoteOutput`) — no drums yet, just melody, so the only thing that changed is who's doing the scheduling. Run it live with `./gradlew naivePlayerDemo --args="path/to/song.mid"` (`NaivePlayerDemo`, step 3's answer, playing a MIDI file's melody window through `NaivePlayer`). Time one platform thread per hit against one virtual thread per hit (see "A 20-minute thread experiment" below) and measure the jitter on both — neither is sample-accurate, that's the point being set up for step 6.
4. **— break (10-15 min) —**
5. **Write your own pattern (20-25 min).** `Note(beat, voice, durationBeats, velocity)` is a familiar shape by now — build `Song`, `DrumTrack` (implementing the sealed `Track`) and `PatternCompiler` as a second way to produce the same shape, by hand from a compact string instead of from a file, and feed it into the same `NaivePlayer` from step 3.
6. **Precision (20-30 min).** `NaivePlayer` has jitter. Implement `Transport.frameAtBeat` and compare block-edge quantization against `AudioEngineTest`'s exact-offset assertions — `AudioEngine` sidesteps the wake-up-deadline problem entirely by placing every transient at its target sample before playback starts.
7. **Put it together (20-30 min).** `BeatApp` loads a melody window into a `MelodyTrack` and plays it alongside step 5's `DrumTrack`s as one `Song` — `PatternCompiler` tiles the drum bar to the melody's length (see `CONTEXT.md`), and `AudioEngine` renders both. Circle back to step 1: `SequencerDemo`/Gervill can glitch on a synth's first note (a real, diagnosed bug — the built-in software synthesizer loads an instrument's patch lazily on its first `noteOn`, see `MidiNoteOutput.warmUp`'s comment); native synthesis through `AudioEngine` never touches an external synth at all, so the glitch doesn't exist there by construction.

The original single-file proof of concept remains in `Beat.java` for comparison. `src/main/resources/` has five MIDI files to explore beyond the workshop's own `song_shape.mid` fixture — `song_child.mid`, `song_giorgioby.mid`, `song_still_dre.mid` and `song_insomnia.mid` aren't wired into any specific exercise, they're just more material to point participants at for steps 2/3/5/7.

### A 20-minute thread experiment

1. **5 minutes:** create one platform thread per hit. Start every thread behind the same `CountDownLatch`, then let it sleep until an absolute `System.nanoTime()` target.
2. **5 minutes:** run two bars and record the number of threads, setup time and min/average/max wake-up lateness.
3. **3 minutes:** replace the builder with `Thread.ofVirtual()`; do not change the timing calculation or MIDI output.
4. **4 minutes:** run the same pattern again and compare. Increase the number of bars if the machine can handle the platform-thread variant.
5. **3 minutes:** discuss the result: virtual threads address the cost of blocking concurrency, while the sample renderer addresses the different problem of placing musical events precisely on the audio timeline.

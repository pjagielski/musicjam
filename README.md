# MusicJam

A small Java 25 workshop about musical time: MIDI, scheduler jitter, and sample-accurate audio.

## Step 1: the black box

`javax.sound.midi`'s `Sequencer` plays a whole MIDI file for you — four lines, no code of ours involved:

```bash
./gradlew sequencerDemo --args="path/to/song.mid"
```

With no arguments at all, it loads `src/main/resources/jam.properties` by default:

```bash
./gradlew sequencerDemo
```

Or point it at a different `.properties` file (only `file` is required):

```bash
./gradlew sequencerDemo --args="--config src/main/resources/jam.properties"
```

```properties
file=src/main/resources/song_still_dre.mid
```

`Config` is what turns those arguments and that file into a path; every later step reuses it and
adds a field.

`src/main/resources/` has five MIDI fixtures to try: `song_shape.mid`, `song_child.mid`, `song_giorgioby.mid`, `song_still_dre.mid`, `song_insomnia.mid`.

Everything the rest of this workshop builds exists to answer one question: what is `Sequencer` actually doing, and can we do better?

## Step 2: take the file apart

`Sequencer` was handed the whole file and gave back sound. What was actually in there?

A MIDI file holds several tracks; `jam.properties` names the file and the one track carrying the
lead melody. `InspectMidi` lists every track in the file — name, channel, GM program, note count,
pitch range, first bar — and then prints every note of the configured one, in beats rather than
ticks:

```bash
./gradlew inspectMidi --args="--config src/main/resources/jam-dre.properties"
```

```text
File: song_still_dre.mid
PPQ: 480, tempo: 93.5 BPM, length: 101.0 bars (4/4)
Tracks:
   0:                          (no notes)
   1: Bowed Bass Strings       ch=0   prog=42   notes=188   range=[E2..E3]   first note: bar 1.8
   2: Pizz Strings             ch=1   prog=45   notes=2400  range=[B4..A5]   first note: bar 2.0
   4: Da Dope Beat             ch=9   prog=0    notes=1828  range=[B1..A#2]  first note: bar 5.6
   5: Electric lead            ch=3   prog=84   notes=201   range=[B4..E5]   first note: bar 9.7
   7: Snoop Dogg: "If you a... ch=5   prog=69   notes=6     range=[E3..G3]   first note: bar 17.9

Track 2: Pizz Strings (ch=1, prog=45), 2400 notes
    beat  bar.beat  pitch         dur    vel
   4.000    2:1.00  C5 (72)     0.438   1.00
   4.000    2:1.00  E5 (76)     0.438   1.00
   4.063    2:1.06  A5 (81)     0.438   1.00
   4.500    2:1.50  C5 (72)     0.438   1.00
```

The riff is three notes struck together, and the top one is consistently 0.063 beat late — the
file is played in, not quantized. Nothing here rounds that away.

`song_insomnia.mid` is the opposite case. Its notes sit on a 130 BPM grid, but the file it was
made from declared 125, so its two-bar riff lasted 7.69 beats. The fixture is that file with the
tempo event set to 130 and every tick stretched to match: each note sounds at the same second as
before, and the riff now fills two bars. The tempo in a file is only what the file claims.

```properties
file=src/main/resources/song_still_dre.mid
track=2
```

A bare `./gradlew inspectMidi` reads `jam.properties`, which points at a different fixture;
`src/main/resources/` has one config per file, listed under step 3.

Both can be overridden from the command line, which is how you try another track without editing
the file:

```bash
./gradlew inspectMidi --args="src/main/resources/song_shape.mid --track 1"
```

`--overview` stops after the listing, for when you are still looking for the track worth
pointing `track=` at:

```bash
./gradlew inspectMidi --args="--overview"
```

`InspectMidi` is only the entry point: `MidiFileReader` walks each track exactly once and returns
a `MidiFile` of plain data, `MidiFileInspector` formats it. The reading is two moves:

- **tick to beat.** `Sequence.getResolution()` is PPQ — ticks per quarter note. `song_shape.mid`
  has PPQ 384, so its first drum hit at tick 3072 lands on beat 8.0, i.e. bar 3.
- **note-on to note-off.** A note is a pair of events, and a note-on with velocity 0 is really a
  note-off. `PlayingNotes` keeps the bookkeeping: `start` when a note begins, `finish` returns the
  note-on that a note-off closes. It holds a queue per pitch, because the same pitch can be struck
  again before the previous one is released.

Everything around those two moves comes finished: the PPQ check, one pass per track, the meta
events that carry the track's name and the file's tempo, and the assembly into a `MidiFile`. What is
left in `readTrack` is the branch that handles channel messages, plus `noteBetween` - with
`ticksToBeats` doing the arithmetic of the first move. Nine tests in
`MidiFileReaderTest` say when they are right — four are green before you start, because they cover
the part you were handed, and they build their sequences in code, so nothing needs a file or a sound
device.

The result is a `TrackData` per track, each holding a `List<Note>` — `beat`, `voice`,
`durationBeats`, `velocity` — which is the shape every later step works on. `Sequencer` can only play a `Sequence` it was handed; it has no way to
play this. That is step 3.

## Step 3: write your own player

`Sequencer` plays a `Sequence` it was handed. It has no way to play the `List<Note>` step 2
produced — so schedule them yourself:

```bash
./gradlew playNotes
```

Playback goes through the same built-in software synthesizer `Sequencer` used in step 1
(`MidiNoteOutput` wraps the same `Synthesizer`), so the only thing that changed is who does the
scheduling.

Which synthesizer is that? `ListMidiDevices` prints every MIDI device Java can see, and the first
one is Gervill, the software synthesizer that comes with the JDK:

```bash
./gradlew listMidiDevices
```

```text
name                             description                      takes messages
Gervill                          Software MIDI Synthesizer        yes
Real Time Sequencer              Software sequencer               yes
```

What comes after those two depends on the system - and in step 4, on the virtual cable you add.

A whole track is too much to listen to, so the config gains a window and a loop count. `bars`
counts from `fromBar`, and the window's notes are rebased so the phrase starts at beat 0:

```properties
file=src/main/resources/song_giorgioby.mid
track=0
fromBar=0
bars=4
loops=2
scheduler=platform
```

Every key can also be passed on the command line (`--track`, `--fromBar`, `--bars`, `--loops`,
`--scheduler`), and `src/main/resources/` has one config per fixture:

| config | file | track |
| --- | --- | --- |
| `jam.properties` | `song_giorgioby.mid` | 0 Track 1 |
| `jam-dre.properties` | `song_still_dre.mid` | 2 Pizz Strings |
| `jam-shape.properties` | `song_shape.mid` | 1 8-Bit Triangle |
| `jam-child.properties` | `song_child.mid` | 4 Chords |
| `jam-insomnia.properties` | `song_insomnia.mid` | 0 Main Synth |

### The part you write

First the sound. `MidiNoteOutput` is the `NoteOutput` that plays into Gervill, and three of its
methods are yours: `open` opens the synthesizer and selects the track's instrument - the channel
and program step 2 read from the file - on that channel, and `noteOn` and `noteOff` hand a note to
the channel. `MidiNoteOutputTest` gives it a stand-in synthesizer whose channels only write down
what they were asked to do, so the tests make no sound. Checking that the synthesizer has the
channel (`channelOf`), warming the instrument up and closing come finished.

Then the timing. Each note becomes two scheduled events — a note-on at `beat`, a note-off at
`beat + durationBeats` — and each event fires at an absolute instant rather than after a delay
measured from the previous one, so lateness cannot accumulate. `MidiPlayer.schedule` builds that
list.

Firing them is somebody else's job. `MidiPlayer` hands each event to an `EventScheduler`:

```java
dispatcher.begin(startupDelayNanos);
for (ScheduledEvent event : events) {
    dispatcher.submit(event.offsetNanos(), targetNanos -> {
        lateness.wokeAt(targetNanos);
        event.fire(output);
    });
}
dispatcher.awaitDone();
```

`begin` fixes the zero point, `submit` says "run this at zero plus that offset", `awaitDone`
waits for the last event. Nothing above that line knows how the work is dispatched, and every
event's lateness is measured by the same code whichever way it is.

Write `PooledScheduler.submit`. It is four lines, and the interesting one is the conversion:
`ScheduledExecutorService` wants a delay from now, while the event has an instant to hit.

### Four ways to dispatch the same events

`pl.livecoding.musicjam.scheduler` exposes the interface and `SchedulerKind`, the enum that
builds one; the implementations are package-private, so `midi` never names a concrete dispatcher.

| `scheduler=` | what it does |
| --- | --- |
| `platform` | one platform thread per event, each sleeping to its own target |
| `virtual` | the same, with a virtual thread factory |
| `pool` | a `ScheduledThreadPoolExecutor` sized to the available processors |
| `scoped` | `StructuredTaskScope`, one forked task per event |
| `all` | run the phrase once per scheduler and print a report for each |

```bash
./gradlew playNotes --args="--scheduler all"
```

Each run ends with a `TimingReport`: how long the events took to set up, and how late each
wake-up actually was. Compare them on your own machine — the numbers move with the JDK, the
fixture and the load, so the interesting part is what changes when you swap one thing.

`scoped` is worth reading rather than just running: no task outlives the scope, so a failure
cannot leave hundreds of threads asleep with a closed synthesizer to play into.
`StructuredTaskScope` is still a preview API in Java 25, which is why the build passes
`--enable-preview` and pins the toolchain — classes compiled that way run only on that exact JDK.
Gradle will fetch it if you do not have it.

None of the four puts an event exactly where it belongs, because `Thread.sleep` returns when the
operating system gets round to it. Step 5 stops asking: it places every note at its exact sample
frame before playback starts, and the only deadline left is keeping the audio device fed.

## Step 4: play it on a real synth

Gervill is a General MIDI sound set from the nineties. The melody deserves a real synth - and the
player and the four schedulers from step 3 stay exactly as they are. The only thing this step
changes is where the notes go.

You need two programs outside Java:

- **a synth**: [Surge XT](https://surge-synthesizer.github.io/) is free and runs standalone. In its
  audio/MIDI settings, pick the virtual cable below as the MIDI input.
- **a virtual MIDI cable**, so one program can send MIDI to another: loopMIDI on Windows (start it
  and add a port), the IAC Driver on macOS (Audio MIDI Setup, MIDI Studio, IAC Driver, "Device is
  online"), or `snd-virmidi` on Linux.

See [External MIDI setup](MIDI_SETUP.md) for detailed instructions on all three systems.

Then find the name Java sees it under:

```bash
./gradlew listMidiDevices
```

```text
name                             description                      takes messages
Gervill                          Software MIDI Synthesizer        yes
Real Time Sequencer              Software sequencer               yes
Microsoft MIDI Mapper            Windows MIDI_MAPPER              yes
Microsoft GS Wavetable Synth     Internal software synthesizer    yes
loopMIDI Port                    External MIDI Port               yes
loopMIDI Port                    No details available             no
```

The cable shows up twice under one name: the end you send into, and the end the synth reads from.
Only the first takes messages. The config names the device by any part of that name, ignoring case:

```properties
file=src/main/resources/song_still_dre.mid
track=2
fromBar=9
bars=2
loops=4
midiDevice=loopMIDI
```

Before writing `ExternalMidiOutput`, check the cable, synth and channel with a single C4. This
program sends MIDI directly, so it works while the step 4 methods are still TODOs:

```bash
./gradlew checkMidiSetup --args="--config src/main/resources/jam-dre.properties --midiChannel 1"
```

It prints the selected device and channel, then plays for 600 ms. If the synth is silent, check its
MIDI input and audio output; try `--midiDevice Gervill` to check Java's own synth. Use the same
`--midiDevice` and `--midiChannel` when running `playOnSynth`.

```bash
./gradlew playOnSynth --args="--config src/main/resources/jam-dre.properties --midiChannel 1"
```

No cable, or no synth? `--midiDevice Gervill` sends the same messages down the same code into Java's
own synthesizer, which is a MIDI device like any other.

The notes go out on the channel the track was written on, and a hardware synth listens on one channel
only. `midiChannel=` (or `--midiChannel`) sends them on another one. It counts from 1 to 16, the way a
synth does, while `javax.sound.midi` counts from 0 - `midiChannel=2` is channel 1 in `ShortMessage` -
and a synth's own display may count either way: a KORG NTS-1 set to "1" played on `midiChannel=2`.

Stopping the program ends every note: `close`, and a shutdown hook for Ctrl+C, shut out any note-on
still on its way from the scheduler's threads, then send All Sound Off and a note-off for every
pitch. IntelliJ's Stop is different when Gradle runs the program - it kills the JVM without running
shutdown hooks, and a note can keep ringing. Let IntelliJ run it instead (Settings, Build, Execution,
Deployment, Build Tools, Gradle: "Build and run using: IntelliJ IDEA"), and Stop runs them. Either way
the next run silences the device before it plays, and so does

```bash
./gradlew midiPanic
```

### The part you write

`ExternalMidiOutput` is a second `NoteOutput`, beside step 3's `MidiNoteOutput`. Opening the device
and closing it come finished; you write:

- `find`: the first device whose name contains the one from the config and which takes messages.
- `noteOn`, `noteOff` and `send`: a `ShortMessage` on the output's channel, handed to the device's
  `Receiver` with a timestamp of -1, which means "now".
- `allSoundOff`: control change 120. It runs on `close` and on a shutdown hook, so Ctrl+C does not
  leave a note hanging on a synth that keeps running after this program has gone.

`ExternalMidiOutputTest` gives it a stand-in device whose receiver only remembers what it was sent,
so the tests need neither Surge nor the cable. One of its seven tests is green from the start: it
checks the part that came finished.

Run it with `--scheduler all` again. The `TimingReport` shows the same numbers as with Gervill -
and yet Surge sounds each note only after its own audio buffer. Nothing we measure can see that,
because the sound is made in another program, on another clock. Step 5 makes the sound itself.

## Step 5: make the sound yourself

Every strategy in step 3 woke a thread up and hoped; step 4 handed the notes to a synth with a
clock of its own. This step stops waiting for the moment and computes where the sound goes instead
- first for the drums, then for the drums and step 4's melody together.

### Drums, to the sample

```bash
./gradlew playBeat --args="--config src/main/resources/jam-dre.properties"
```

`drums=` in a config names one of `DrumPatterns` - `shape`, `worry`, `dre`, `giorgio` or `insomnia`,
one bar each, transcribed from its fixture (`giorgio`'s and `insomnia`'s files have no drums, so their
patterns are invented) - and
the tempo comes from the config's MIDI file, so the drums will fit its melody. Every drum is a file
in `samples/` - `bd.wav`, `sd.wav`, `hh.wav`, `oh.wav` and `cp.wav` - and a drum whose file is
missing is computed by a formula instead.

The renderer cannot tell the two kinds apart: a sample from a file and a sample from a formula are
both an array of floats. The files are not even alike - `bd.wav` is 192 kHz stereo, `sd.wav`
44.1 kHz stereo, `hh.wav` 44.1 kHz mono in 24 bits, `oh.wav` and `cp.wav` 44.1 kHz stereo in 24
bits - and `WavSampleLoader` turns each of them into 44.1 kHz mono floats. Every drum both ways, on one time scale:

```bash
./gradlew showSamples
```

A window opens with every drum twice, on the same 450 ms: the sample from its file in blue, the one
from its formula in orange, each with its length, its loudest value and a play button - and for a
file, how the file itself stores the sound, before it becomes 44.1 kHz mono. Where the two differ - the
length, the decay, the loudness - shows at a glance, and so does what they share: a column of
numbers the renderer does not care where it came from. JavaFX comes in with this window; step 6
builds on it.

`AudioEngine` hands the sound card blocks of 512 frames, about 12 ms. A hit does not wait for its
moment: its moment is a frame number, worked out before anything plays. At 120 BPM a sixteenth note
is 5512.5 frames, and counting every position from the start of playback, not from the hit before,
keeps the rounding from adding up.

### The part you write

In the order the tests take it:

- `Transport.frameAtBeat`: the frame a beat starts on (`TransportTest`).
- `PatternCompiler.compile`: a bar of `"X...x...o..."` into the same `Note`s a MIDI file gives
  (`PatternCompilerTest`). What one character means comes finished in `parse`: `X`, `x` and `o` are
  hits of different loudness, and a dot, a dash or a space is a rest, `Optional.empty()`.
- `AudioEngine.schedule`, the heart of the step: the schedule you wrote in step 3, counted in frames
  instead of nanoseconds - a hit for every note in every loop, on the frame of its beat counted from
  the start (`AudioEngineScheduleTest`).

Nobody waits for those frames. The renderer comes finished: it cuts each block at every hit inside
it, so a hit starts on its own frame rather than on the edge of the block. `AudioEngineTest` renders
into memory at 1000 frames a second with blocks of 128 frames, so the frames are easy to count and
no sound card is involved, and it turns green once your schedule is right. The tests of the parts
that come finished are green from the start.

Then hear what the cutting is for. In the renderer's `renderNext`, start every hit on the block's
first frame: where it takes `long eventFrame = hits[nextHit].frame();`, write
`long eventFrame = position;`, and play it with big blocks:

```bash
./gradlew playBeat --args="--config src/main/resources/jam-dre.properties --block 4096"
```

A block of 4096 frames lasts 93 ms, and the groove falls apart. Put the line back: nothing in this
renderer waits for a moment any more. The only deadline left is handing the sound card its next
block before the one it has runs out.

### Two clocks

```bash
./gradlew playJam --args="--config src/main/resources/jam-dre.properties"
```

The drums play through `AudioEngine`, the melody of step 4 through `MidiPlayer`: on the device
`midiDevice=` names, or on Gervill with `--midiDevice Gervill`. That is two clocks - the drums follow
the sound card, the player follows `System.nanoTime()`. Make the drums stall the way a garbage
collection pause would, three seconds in, and listen to the melody after that:

```bash
./gradlew playJam --args="--config src/main/resources/jam-dre.properties --stall 300"
```

`MidiPlayer` plays the melody loop by loop, through the schedulers of step 3. `loopStartNanos`
comes finished: when the drums started, counted back from now by how much of them has been heard
(`AudioEngine.heardNanos`), and when a loop starts after that - early by `midiLatency` milliseconds
(50 unless the config says otherwise), the time the synth takes to sound a note. What is left to write
is where it plugs in: the loop in `playLoop` that asks it, before every loop, where that loop starts,
and hands the loop to `playOneLoop`. `MidiPlayerLoopTest` checks the arithmetic with plain numbers -
green from the start - and then plays three loops against "audio" that falls 100 ms behind.

50 ms is only where `midiLatency` starts: it is how much later the synth's path to the speaker is
than the drums' - a virtual cable, the synth's own buffer, the operating system's audio - so every
machine and every synth has its own. Surge XT over loopMIDI took 35-50 ms, on a Mac 75; Gervill
over 200, because it buffers 120 ms of audio and corrects its own jitter on top. Find yours by ear:

```bash
./gradlew calibrateLatency
```

A window plays the kick through `AudioEngine` and a note on the synth, both on every beat, on the
device and the track the config names. Move the slider until the two sound as one hit, and copy the
`midiLatency=` line into the config. It comes finished and needs none of the exercises, so do it
first.

Now a stall is caught up with at the next loop. Two clocks are not set once; they are compared, again
and again - or there is only one.

### One clock

Keep the melody off MIDI altogether:

```bash
./gradlew playJam --args="--config src/main/resources/jam-insomnia.properties --synth pluck"
```

`synth=` in a config, or `--synth`, names one of `Synths` - `anthem`, `pluck` or `pad`, patches
ported from a synth written in C++. `AudioEngine` renders every melody note through it, for as long
as the note lasts, and puts the result on the note's frame exactly as it puts a drum sample there:
past that point the renderer cannot tell a note from a drum. Nothing is sent, so there is no
`midiLatency` to find and no second clock to compare with - add `--stall 300`, and the drums and the
melody pause together. It comes finished; `AudioEngineTest` checks a melody note against a synth that
holds one level for exactly as long as it is asked to. One clock for everything is where step 6
starts.

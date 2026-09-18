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

Steps 1–3 use four MIDI fixtures: `song_shape.mid`, `song_child.mid`, `song_giorgioby.mid`, `song_still_dre.mid`. Step 4 adds `song_insomnia.mid`.

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
`--scheduler`), and `src/main/resources/` has one config per fixture used so far:

| config | file | track |
| --- | --- | --- |
| `jam.properties` | `song_giorgioby.mid` | 0 Track 1 |
| `jam-dre.properties` | `song_still_dre.mid` | 2 Pizz Strings |
| `jam-shape.properties` | `song_shape.mid` | 1 8-Bit Triangle |
| `jam-child.properties` | `song_child.mid` | 4 Chords |

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
  online").

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

```bash
./gradlew playOnSynth --args="--config src/main/resources/jam-dre.properties"
```

`Insomnia` joins as another melody for the external synth. Try it here; step 5 adds its drums:

```bash
./gradlew playOnSynth --args="--config src/main/resources/jam-insomnia.properties --midiDevice Gervill"
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

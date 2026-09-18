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

`src/main/resources/` has four MIDI fixtures to try: `song_shape.mid`, `song_child.mid`, `song_giorgioby.mid`, `song_still_dre.mid`.

Everything the rest of this workshop builds exists to answer one question: what is `Sequencer` actually doing, and can we do better?

## Step 2: take the file apart

`Sequencer` was handed the whole file and gave back sound. What was actually in there?

A MIDI file holds several tracks; `jam.properties` names the file and the one track carrying the
lead melody. `InspectMidi` lists every track in the file — name, channel, GM program, note count,
pitch range, first bar — and then prints every note of the configured one, in beats rather than
ticks:

```bash
./gradlew inspectMidi
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

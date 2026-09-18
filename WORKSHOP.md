# Workshop path

Six steps in three hours, each one answering a question the previous step raised. Every step has a
branch with its starting point (`step-N`) and one with a reference solution (`step-N-final`), and
its entry point lives in a package of its own (`pl.livecoding.musicjam.stepN`).

| step | what happens | you write | minutes |
| --- | --- | --- | --- |
| 1 | `SequencerDemo`: Java plays a MIDI file for you | nothing | 5 |
| 2 | `InspectMidi`: read the file and look at its events | note pairing, tick to beat | 25 |
| 3 | `PlayNotes`: play the notes yourself, through Gervill | a MIDI output, the event list, a pooled scheduler | 30 |
| – | break | | 10 |
| 4 | the same melody on an external synth | a MIDI output for a real device | 20 |
| 5 | drums rendered to the sample, and two clocks | the frame schedule, then the clock fix | 45 |
| 6 | live coding in the studio | nothing | 15 |
| – | back to step 1, wrap-up | | 5 |

That is 155 minutes, which leaves 25 for setup trouble and questions.

## 1. The black box

`SequencerDemo` hands a `.mid` file to `javax.sound.midi.Sequencer`: four lines, and it plays. It is
also the moment everyone checks that sound, headphones and the build work. The question it leaves:
what is `Sequencer` actually doing, and can we do better?

## 2. Take the file apart

`InspectMidi` lists the tracks of a file and prints one track's notes. The PPQ check, the single pass
per track and the meta events (track name, tempo) come finished; you write the two moves that make a
note: tick to beat, and note-on to note-off (`MidiFileReader.readTrack`'s channel-message branch and
`noteBetween`). `MidiFileReaderTest` leads the way; four of its nine tests are green from the start.

The result is a `List<Note>` in beats, and `Sequencer` has no way to play it.

## 3. Write your own player

`ListMidiDevices` shows Gervill among the MIDI devices Java can see, and `PlayNotes` plays those
notes through it. You write `MidiNoteOutput` - open the synthesizer, select the track's instrument,
play and release a note - then `MidiPlayer.schedule` (every event at an instant counted from the
start, so lateness cannot add up) and `PooledScheduler.submit`, and compare four dispatchers with
`--scheduler all`.

None of them hits the instant, because the operating system decides when a thread wakes up. And
`TimingReport` measures the wake-up, not the sound: Gervill has a buffer of its own.

## 4. An external synth

The same player and the same schedulers; only where the notes go changes. The melody goes to
Surge XT over a virtual MIDI cable. `Insomnia` joins the MIDI fixtures here, before its drums arrive in step 5. You write `ExternalMidiOutput`, a second `NoteOutput`: find the
device by name, send note-on and note-off, and silence everything on close so that Ctrl+C leaves no
note hanging (IntelliJ's Stop skips that when Gradle runs the program - let IntelliJ run it, or use `midiPanic`, which comes finished). Its tests use a stand-in `Receiver`, so they need neither the synth nor the cable.

The report shows the same numbers as with Gervill, although Surge sounds a note only after its own
buffer - which our measurement cannot see. Anyone whose setup fails stays on Gervill and loses
nothing in step 5.

## 5. Drums, and two clocks

Stop waiting for the moment and compute the frame instead. You write `Transport.frameAtBeat`,
`PatternCompiler.compile` - a bar of `"X...x..."` into the same `Note`s a MIDI file gives - and the
heart of it: `AudioEngine.schedule`, step 3's schedule again, counted in frames instead of nanoseconds. The
renderer that puts every hit on its exact frame comes finished; starting every hit at the edge of its
block instead, with a 4096-frame block, makes the error audible.

Then the drums play with step 4's melody, and the melody is late: the drums follow the sound card,
`MidiPlayer` follows `System.nanoTime()`, and after a stall they stay apart. A few lines fix it: before
every loop, `playLoop` asks where that loop starts - from how much of the drums has been heard
(`AudioEngine.heardNanos`), early by the synth's latency (`midiLatency`). Two clocks are not set once; they are compared, again and again.

## 6. Change it while it plays

The studio: one `LiveSession` renders the drums and the melody on one clock, and `$: stack(...)`
typed into the editor lands on the next loop. Nothing to write - play with it. Then back to step 1:
now we know what `Sequencer` was doing.

## Before the workshop

- JDK 25 (Gradle fetches the toolchain if it is missing) and headphones.
- For step 4: Surge XT, plus a virtual MIDI cable - loopMIDI on Windows, the IAC Driver on macOS.

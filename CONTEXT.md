# MusicJam

A Java 25 workshop codebase about musical time: sample-accurate audio rendering, scheduler
jitter, and loading/synthesizing music from MIDI files.

## Language

**Note**:
One musical event: `beat`, `voice`, `durationBeats`, `velocity`, and an `Envelope` (usually
`Envelope.NONE`). The shared unit produced by
both a hand-authored pattern (`PatternCompiler`) and a MIDI file (`MidiFileReader`) — everything
downstream (rendering, scheduling) works on `Note`, never on the pattern string or the MIDI file
directly.

**Voice**:
Which sound a `Note` triggers: either a `Drum` or a `Voice.Pitch` (a MIDI note number). Sealed —
those are the only two kinds.
_Avoid_: Instrument (ambiguous with a MIDI program number, which is a different thing)

**Drum**:
One of the five built-in percussion sounds (KICK, SNARE, CLOSED_HAT, OPEN_HAT, CLAP). Also a
`Voice` — a `Note` naming a `Drum` is a percussion hit.

**Song**:
A complete jam: `bpm`, `beatsPerBar` (the length of one `DrumTrack` bar), and a `List<Track>`.
`PatternCompiler` compiles it into the `Note`s that `AudioEngine`/`NaivePlayer` actually schedule.

**Track**:
One layer of a `Song`: either a `DrumTrack` or a `MelodyTrack`. Sealed — those are the only two
kinds.

**DrumTrack**:
A `Drum`, a step-pattern string, and a gain — one bar. `PatternCompiler` tiles it to fill the
`Song`'s full length (set by its longest `MelodyTrack`, via `PatternCompiler.totalBeats`), so a
1-bar groove keeps looping under an N-bar melody. A loop that ends inside a bar cuts the pattern off
there.

**MelodyTrack**:
The `Note`s of a loaded `Phrase`, plus the length of one loop through them
(`patternLengthBeats`) and a gain. Doesn't tile — it already spans the whole loop once.

**Phrase**:
A windowed excerpt of one track from a MIDI file — file, track index, start bar, bar count —
loaded and rebased so its first beat is 0. What `BeatApp` loads into a `MelodyTrack`.
_Avoid_: Melody (a Phrase's track isn't always melodic — it can point at any track; the
name shouldn't imply the content)

**PitchSynth**:
Synthesizes one pitched tone — `render(midiNote, frameCount, sampleRate) -> Sample`. One tone,
not a sequence.
_Avoid_: MelodySynth (superseded — the old name implied it played a whole melody)

**DrumSamples**:
Procedural, algorithmic generation of the five `Drum` sounds, used when no WAV file is present
for that drum. The percussion counterpart to `PitchSynth`.
_Avoid_: SynthSamples (superseded — collided with the unrelated `synth` package)

**MidiPlayer** vs **NaivePlayer**:
Both schedule a `List<Note>` and send it to a `NoteOutput` (MIDI destination) — they differ only
in scheduling strategy, not in what they play. `MidiPlayer` runs one absolute-time thread for the
whole phrase, and can start each loop from where some audio it follows has got to
(`AudioEngine.heardNanos`). `NaivePlayer` deliberately runs one waiting thread *per note*, to demonstrate
platform-vs-virtual-thread jitter; its result is a `TimingReport`.
_Avoid_ (for MidiPlayer): MelodyPlayer (superseded — it plays any `Note`, drum or pitched, not
just melody)

**AudioEngine**:
The sample-accurate renderer: places every `Note` at its exact sample frame, shared by live
playback, WAV export, and the tests. The alternative to a `MidiPlayer`/`NaivePlayer` — nothing
routes through an external synthesizer on this path.

**LiveSession**:
`AudioEngine.playLive`'s running playback: an endless loop compiled afresh from a supplied `Song` and
synth at every loop boundary, so edits land on the next loop. The audio device's playback position is
its clock, also for melody notes routed to an external MIDI device, which go out early by that
synth's own latency.

**BeatStudio**:
The JavaFX window over a `LiveSession`: edits the drum grid, tempo, loop length and melody of a jam
started from the same `.properties` file as `BeatApp`.

**Envelope**:
How a drum hit is shaped: a decay time and a release time, in seconds. Applied to a copy of the
sample before playback, not per voice while mixing, so `AudioEngine`'s mixing loop is unchanged.

**LiveCode**:
The studio's live-coding language: `$:`, `stack(...)`, `s("mini-notation")`, `.gain`, `.dec`,
`.rel`. A third producer of `Note`s, beside `PatternCompiler` and `MidiFileReader`. Written for this
project in the style of Strudel; not Strudel.

**MiniNotation**:
The pattern strings inside `s("...")` and `.gain("...")`: rests, groups, layers, repeats and
Euclidean rhythms, evaluated to events placed within one cycle.

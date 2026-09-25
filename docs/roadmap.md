# Studio roadmap

Where BeatStudio could go next, and what each step is worth. Written after the session that made
the synth play live, gave it effects and a panel, so it starts from what exists rather than from a
blank page.

## Where it stands

- **Engine.** `AudioEngine` renders block by block and compiles each loop at its first frame, so the
  song can change while it plays. Drums are samples; the synth is a live `VoiceSource` making frames
  as it goes, which is why a knob is heard inside a sounding note.
- **Tracks.** A jam is a list of tracks: one drum track, which is the grid, and any number of
  melody tracks. Each has a name, a gain and a mute heard at once, a synth of its own, and its own
  notes — a window of a MIDI file or a line written in the piano roll.
- **Routing.** Drums mixed dry; each live synth through a channel of its own carrying a crusher, a
  ping-pong delay and a Freeverb-style reverb, ducked under the kick when the panel asks for it.
  A channel closes once the jam has let its synth go and the tail has died away. Stop releases
  rather than cuts, so tails ring out. The master is hard-clipped, with no limiter (1.5).
- **Synth.** One engine (`NovasawSynth`), seven unison saws, a sub sine, a diode shaper, a
  state-variable lowpass on an envelope of its own. Six patches, levelled against one another: anthem, pluck, pad,
  chords, sub bass, acid bass.
- **Control.** A panel of knobs, an XY pad and a draggable envelope, all publishing `SynthParams`
  and `EffectParams`. Presets load a patch's own numbers; the delay can lock to the jam's tempo.
- **Live code.** `s("bd(3,8,5)")`-style mini-notation drives the drum grid; a melody is drawn in
  the roll or read from a MIDI file, not written in code (3.1).
- **The roll.** Four bars at a time, turning the page with the playhead. Its keys are played by
  clicking them and light while the loop sounds on them. A hand adds, moves,
  stretches and removes notes, gathers several with a band and sets their velocities in a lane
  under it. Ctrl+Z walks back through what a hand has written.
- **Out.** Native audio, or any melody track to an external synth on a channel of its own, with a
  latency offset; the device and the latency are the jam's, the channel and the filter the
  track's.

## Principles worth keeping

1. **Everything is made, not shipped.** No binary assets: the drum samples have code fallbacks, the
   window icon is drawn. That is the workshop's whole argument.
2. **Plain Java.** `javax.sound.*`, JavaFX, no audio libraries. It keeps every layer readable and
   the exercises honest.
3. **The steps stay teachable.** `final` may grow; `step-1` … `step-5` are a course and change only
   when the course needs it.
4. **Heard immediately.** Anything a performer touches should take effect now, or on the next loop
   at the latest.

---

## Phase 1 — A desk instead of two paths

The single synth bus is already the seam for this; widening it is the biggest sonic step left.

| Step | What | Effort |
| --- | --- | --- |
| 1.1 | ◐ **Half done.** **A bus per track**: gain and mute are there, per track and heard at once (10.1); **pan and solo are not**, and neither is a drum track per drum — the grid is still one track of several rows. | M |
| 1.2 | **Sends**: one delay and one reverb shared by the tracks that want them, each with a send level — Strudel calls this an *orbit*, one delay and one reverb per orbit. | M |
| 1.3 | ✓ **Done.** ★ **Sidechain ducking**: the kick ducks the synth bus. Strudel's `duck` works on the whole orbit; ours can start with one source and one target. | S |
| 1.4 | **Meters**: a level readout per track, drawn like the knobs. | S |
| 1.5 | **A limiter on the master.** The mix is clipped hard at ±1 today, which a few Performance FX at once will find. One limiter after the FX slot, with the release long enough not to pump. | S |

Why first: pumping bass under a kick is the sound of the genre the workshop plays in, and the
mixer is the thing that makes the studio feel like a studio rather than a demo.

## Phase 2 — The synth gets deeper

| Step | What | Effort |
| --- | --- | --- |
| 2.1 | ✓ **Done.** ★ **A filter envelope of its own.** Today `Env→Filt` reuses the amplitude envelope; a separate ADSR for the filter is what separates a preset from an instrument. Strudel keeps `lpenv` apart from the amp envelope for the same reason. | M |
| 2.2 | **Glide (portamento)**, so a mono bass line slides between notes. The acid patch is half-finished without it. | M |
| 2.3 | **Highpass and bandpass** alongside the lowpass, chosen per patch. | S |
| 2.4 | **Unison voice count** as a parameter (7 is a lead; a bass wants 1–3). | S |
| 2.5 | **Pitch envelope** for drums-from-synth and for 909-style toms. | S |
| 2.6 | **Oversampling** of the saw and the shaper, to take the fizz off very bright patches. | L |
| 2.7 | ✓ **Done.** **Crush on the synth channel**, in front of the delay and reverb, so the repeats carry what the crusher left; the Drive knob was already the channel own dirt. The Performance FX strip of the same name crushes the whole mix, drums and all; this one only what the synth plays. | — |
| 2.8 | ✓ **Done.** **Drive that adds grit, not volume.** The diode shaper is some ten times louder at the top of the knob than at the bottom, which is what made the pad quiet at low drive and made Drive behave like a second Output. Its level is now measured, fitted and taken back off, as the Dirty strip does; every patch trim was re-levelled so the six patches sound exactly as loud as they did. | — |

## Phase 3 — Live code that reaches the melody

The grid and the code understand each other; the melody does not.

| Step | What | Effort |
| --- | --- | --- |
| 3.1 | ★ **`note("c3 e3 g3")` driving the synth**, so a line can be written rather than drawn or loaded. It is the third of a melody track's sources (10.2): the roll and the MIDI window are done, this one is not. | M |
| 3.2 | **More mini-notation**: `<a b>` (one per cycle), `?` (maybe), `*`/`/` on any subsequence, `.fast`/`.slow`. The Strudel reference is the map; the subset we have is a corner of it. | M |
| 3.3 | **Grid → code**: clicking a step rewrites the pattern text, so the two views stop fighting. | M |
| 3.4 | **Per-layer effects** in code (`.room`, `.delay`), which needs Phase 1's sends. | M |

## Phase 4 — Playing a set, not a loop

| Step | What | Effort |
| --- | --- | --- |
| 4.1 | ★ **Clip grid (session view)**: columns are tracks, rows are scenes, one clip per track at a time, launched on the next bar. Ableton's rules are worth copying exactly — a track plays one clip, a scene launches a row, and launches are quantized. Our loop-boundary compile is already that mechanism. | L |
| 4.2 | ✓ **Done** as 10.3: the selected melody track's editor, four bars at a time, with the playhead, dragging, a band to gather notes and a velocity lane. | L |
| 4.3 | **Recording**: capture clip launches and knob moves, then render the result to WAV (`writeWav` exists) and to a MIDI file. | M |
| 4.4 | ✓ **Done.** ★ **Undo** for roll edits, grid edits, code runs and tracks added, removed or moved. Ctrl+Z and Ctrl+Y (or Ctrl+Shift+Z) anywhere in the window, except in a text field, which keeps its own. One stack of whole states — the tracks with their notes, which was selected, the grid and the code — sixty-four deep. A gain, a mute, the tempo and the synth's knobs are performance rather than work, so playing them never fills the history. | S |
| 4.5 | ✓ **Done.** ★ **Tempo that changes now, not from the next loop.** Queued hits are kept in beats and placed through a `TempoMap` (the frame, beat and BPM of each recent change), so a change at frame F re-times every hit not yet played, the loop's end with them. The renderer reads the jam's tempo every block. `positionAt`, note lengths and a held stutter's slice all go through the map, and the melody for an external synth waits in beats too, turned into a frame only when it is due to be sent. Left for later: a tempo glide over a beat rather than a jump. | M |
| 4.6 | ✓ **Done.** **Loop length that changes now.** Shortening a loop waits for the old one to end: from 8 bars to 4 while bar 3 plays, it runs on to bar 8. Instead, cut the loop that is playing at the first multiple of the new length still ahead — bar 3 of 8 set to 4 wraps after bar 4, bar 5 after bar 8 — by moving the next loop's start and dropping the queued hits past it. Lengthening works the other way: from 4 bars to 8 while bar 3 plays, compile the longer song and queue its hits from bar 5 on in the loop already playing, so it runs to bar 8 instead of wrapping at 4. It needs the renderer to see the loop length every block rather than once per loop, which 4.5's tempo map asks for too. | S |

## Phase 5 — Playing with other machines

| Step | What | Effort |
| --- | --- | --- |
| 5.1 | ★ **MIDI clock out** (24 pulses per quarter note, plus start/stop), so an NTS-1's arpeggiator or a drum machine runs off our tempo. | S |
| 5.2 | **MIDI in**: a keyboard plays the synth, and a controller's CCs map to knobs (MIDI learn). | M |
| 5.3 | **Audio device choice and buffer size** in the UI, with the measured latency beside it. | S |
| 5.4 | **Ableton Link** — tempo and phase shared over the network. Note before starting: Link is a C++ header-only library, dual licensed GPLv2+ / proprietary, so it means an FFM binding *and* a licence decision. MIDI clock gives most of the benefit for a fraction of the work. | L |
| 5.5 | **MIDI 2.0 / UMP** — worth knowing that `javax.sound.midi` is MIDI 1.0 only, and that MIDI-CI and the Universal MIDI Packet would need a native bridge. Park it; there is nothing to gain for this instrument. | — |

## Phase 6 — Keeping what you make

| Step | What | Effort |
| --- | --- | --- |
| 6.1 | ★ **Save and recall a sound**: write the panel's parameters back to a `jam-*.properties` as a named preset, and let a preset carry its effects too. Today every knob move is lost when the window closes. | S |
| 6.2 | **Type a value into a knob** (double-click opens a field), as the CC number now allows. | S |
| 6.3 | **A stylesheet** instead of inline styles, and a light/dark switch for the whole studio. | M |
| 6.4 | **`jpackage` bundle**, so participants double-click an app with its own icon instead of running Gradle. | M |

## Phase 7 — The workshop itself, told apart from the instrument

One repository holds two things that have grown apart. The course is ten branches — `step-1` to
`step-5`, each with a `-final` — laid out for teaching: `step1/`, `step2/`, a `scheduler/` package
of four scheduler kinds, a `Config`. The instrument is `final`, where none of that exists and the
studio does. What `final` still carries from the course is the demo path: `SequencerDemo`,
`InspectMidi`, `NaivePlayerDemo` and the `NaivePlayer` and `TimingReport` behind them — five files
in `midi/` that nothing else calls, beside the `MidiFileReader` and `ExternalMidiOutput` the studio
lives on. The line is not clean, which is the point: `MidiPlayer` looks like course code and is
not (`BeatApp`'s loop MIDI sync uses it), `MidiNoteOutput` and `NoteOutput` are shared by both, and
`ListMidiDevices` is a lesson that is also the tool for finding a loopMIDI device by name. The
README's Run section is mostly workshop instructions, so someone opening the repo for the
instrument reads a lesson plan.

None of that is wrong, but it costs: every engine fix is ported to the step branches by hand (7.1
is exactly that), and nothing in the tree says which half a file belongs to.

| Step | What | Effort |
| --- | --- | --- |
| 7.1 | Port the state-variable filter fix to `step-5` / `step-5-final`. | S |
| 7.2 | A step built on the live voice: "make the filter follow the envelope" is a good exercise now that the engine plays voices rather than samples. | M |
| 7.3 | Update the cheat sheets in `docs/sciagi` once the steps change. | S |
| 7.4 | ★ **The instrument and the course in their own source sets.** A `course` source set for the five demo files nothing else calls, depending on the main one rather than sitting inside it, and the Gradle tasks split the same way (`studio`, `beat` for the instrument; `naivePlayerDemo` for the course). The work is not the move but the sorting: deciding where `MidiPlayer`, `MidiNoteOutput`, `NoteOutput` and `ListMidiDevices` belong when both halves use them. Nothing is deleted and no branch moves — it is the tree saying which half is which. | S |
| 7.5 | **A README apiece.** One that opens with what the instrument is and the one command that starts it, one for the workshop path with its steps, its source-launch lines and its cheat sheets. Today they are one page, and the instrument's half is the shorter. | S |
| 7.6 | **The steps built on the core module, not on copies of it** (wants 8.1). A step branch keeps its own teaching layout, but takes the engine as a dependency, so a filter fix lands once instead of being carried to ten branches. The open question is whether the course stays ten branches at all: branches give a clean `git checkout step-3`, directories in one branch give one history and no drift. Worth deciding before 7.6, not after. | M |

## Phase 8 — On a phone

Most of the studio is plain Java with no platform in it: the model, the synth and its effects, the
live-code parser and the renderer, which only fills `float[]` blocks. Two things are missing on
Android — `javax.sound.*` (no `sampled`, no `midi`) and JavaFX — and both sit at the edges.

| Step | What | Effort |
| --- | --- | --- |
| 8.1 | ★ **A core module with no JavaFX and no `javax.sound`**: model, synth, effects, live code and `LiveRenderer`, with `LiveSession`'s `SourceDataLine` behind an output interface. Worth doing even if Android never happens — a cleaner seam, and tests that need no audio device. | M |
| 8.2 | ★ **The spike**: a minimal Android app that plays a loop through our `LiveRenderer` into `AudioTrack` (low-latency mode), with one knob on the cutoff. It answers the only question that matters before anything else: can a phone compute seven saws per voice, frame by frame in Java, without dropouts, and how late does it sound. | M |
| 8.3 | **MIDI** through `android.media.midi`. A USB-MIDI device over OTG — the NTS-1 among them — should show up there. | M |
| 8.4 | **The interface in Jetpack Compose**, made for fingers rather than a mouse. The knobs, the XY pad and the envelope are canvas drawing, so their geometry carries over almost line for line; the drum grid, the code editor and the layout are new work, and most of the cost. | L |

Two things to check before 8.2:

- **The language level.** The project builds on Java 25; Android accepts only part of recent Java.
  Records and sealed types are fine, but a few `switch` statements use Java 21 type patterns
  (`case Drum drum ->` in `AudioEngine`) and may need rewriting as `instanceof`, depending on the
  toolchain.
- **Not via JavaFX.** Gluon runs JavaFX on Android through GraalVM native-image, but it is a heavy
  toolchain and still leaves `javax.sound` missing. The audio adapter has to be written either way,
  and a native UI is the better half to write.

If 8.2 shows dropouts, the fallback is Oboe (C++, AAudio underneath) for the output, fed with the
same blocks — but the spike should come first, because it may well not be needed.

## Phase 9 — Performance FX, the Koala way

Koala Sampler's Perform screen has sixteen touch effects over the whole mix, each played by holding
and sliding a finger on a strip. The `FxStrip` under the melody panel is the start of that screen;
this is the rest of it. Most of them are a few lines of DSP on the finished mix — what they share
is the plumbing in 9.1.

| Step | What | Effort |
| --- | --- | --- |
| 9.1 | ✓ **Done.** ★ **The plumbing**: a mix-wide effect slot after `mixInBus` (the stereo mix, drums and synth together), and a continuous mode for `FxStrip` — a value from bottom to top instead of zones, with a centre for the two-way effects (Filter, Pitch, VibroFlange). Lock works as it does for Stutter. | S |
| 9.2 | ✓ **Stutter.** Done, with one difference from Koala's: it repeats the slice's *notes*, not its audio, so the knobs stay live under a held repeat. Koala goes from ½ bar to 1/64; ours from 1/4 to 1/32. | — |
| 9.3 | ✓ **Done.** ★ **Crush**: sample-rate reduction (slide up for less), with a little transistor-style clipping. A sample-and-hold and a rounding step. | S |
| 9.4 | ✓ **Done.** ★ **Filter**: one strip, low-pass below the centre and high-pass above, resonant. A state-variable filter of its own in `PerformanceFx`, in the trapezoidal form, which stays stable while a finger sweeps it. | S |
| 9.5 | **Cutter**: a tempo-synced gate chopping the mix from 1 bar to 1/64 — Stutter's grid, applied to the volume instead of the notes. | S |
| 9.6 | ✓ **Done.** **Dirty**: overdrive. `NovasawDsp.shapeDiode` on the mix, with the slide as drive, and the shaper own gain taken back off so only the grit is heard. | — |
| 9.7 | **Ring**: ring modulation, slide up for a faster carrier. | S |
| 9.8 | **Comb**: a short feedback delay, slide up for a longer one — the metallic, pitched ring. | S |
| 9.9 | **Gate**: mutes whatever falls below a threshold, slide up to raise it; with a fast release it chops tails and reverb away. | S |
| 9.10 | ✓ **Dub done**, and it stands for both: a ping-pong delay of its own over the whole mix, a dotted eighth in time with the jam, damped and saturated in the feedback path, feeding harder as it slides; it adds to the mix rather than replacing it, so letting go leaves the repeats to ring out. **Tempo Delay**, the same line with the slide picking the time instead, was built and taken out again: next to Dub it was another delay to no purpose, and a strip is worth more to an effect that does something else. | — |
| 9.11 | **Reverb**: our Freeverb over the whole mix, slide up for a bigger room — a wash to throw a break into. | S |
| 9.12 | **VibroFlange**: above the centre a flanger (a short swept delay), below it a pitch wobble (a modulated delay without feedback). | M |
| 9.13 | **Compressor**: one knob, the higher the harder it pumps; an envelope follower, threshold and ratio together. | M |
| 9.14 | **Reverse**: plays the last stretch of the mix backwards, slide up for a longer stretch — a buffer read in reverse, lined up with the beat so it lands on time. | M |
| 9.15 | ✓ **Done.** ★ **Talkbox**: three resonant band-passes, one per formant, sweeping a-e-i-o-u as the finger slides; between two vowels each band is taken part of the way from one to the other. A tenor's formants, with the bands widened and the upper ones brought up, since a whole mix through a voice's own bands whistles and comes out an "o". | M |
| 9.16 | **Pitch**: all of it up or down from the centre. In the note domain, as Stutter is: transpose the synth's notes and resample the drums, rather than a pitch shifter on the audio. | M |

Worth doing in the order of the stars: the plumbing, then Crush and Filter (quick, and heard at
once), then Talkbox. Koala's mixer effects (EQ, limiter, bit cooker, tape delay and the rest) are a
different thing — per-channel inserts — and belong with Phase 1's desk; a limiter on the master,
at least, would save the mix from clipping once a few of these are stacked.

## Phase 10 — A jam of tracks

The model has the shape of this already: a `Song` is a list of `Track`, and `Track` is a `DrumTrack`
or a `MelodyTrack`. The studio does not use it. `publish()` builds exactly two tracks every time —
the grid, as one `MelodyTrack` of drum notes, and the melody window read from a MIDI file — with one
synth patch between them, one gain each, and one editor apiece bolted to the window. Everything
below is about making the panel as free as the model already is: one drum track and as many melody
tracks as a jam wants, each with its own notes, its own patch and its own way of being written.

It is the piece several other steps are waiting on. 1.1's bus per track needs tracks to put a bus
on; 4.1's clip grid needs columns to be tracks; 4.2's piano roll needs to be one track's editor
rather than the melody's; 3.4's per-layer effects need a layer to hang on.

| Step | What | Effort |
| --- | --- | --- |
| 10.1 | ✓ **Done.** ★ **A track list in the studio**: add, remove, rename, reorder. One drum track, which is the grid as it stands, and any number of melody tracks. Each carries a name, a gain and a mute, both heard at once rather than at the next loop; the selected track is what the editor below shows — the grid and its code, or a melody's MIDI window (file, track, first bar) — and what the instrument panel beside the performance effects shows: the synth or the external MIDI synth. Every track is as long as the loop, whose length the jam sets; a jam config opens with the grid and the one melody it names. | M |
| 10.2 | ◐ **Two of three done.** ★ **A melody track knows where its notes come from**, and there are three ways: a **piano roll** (4.2), **live code** (`note("c3 e3 g3")`, which is 3.1), and a **MIDI file window** — the file, the track inside it and the bars taken from it, which is what `PhraseRequest` holds and the studio does today for its one melody. The source is the track's own, so a jam can have a bass written in code over a lead loaded from a file. | M |
| 10.3 | ✓ **Done.** ★ **The MIDI window drawn in the piano roll, and edited there.** A melody track's editor draws its notes as a piano roll - a key per row with every C named, a line per beat and bar, and the playhead over it while the jam plays. It shows at most four bars at a time and turns the page as the playhead passes the last one shown, with buttons to turn it by hand. The roll takes a hand as a DAW's does: click to add a note, drag to move it, drag its right edge to change its length, right-click to take it away. A drag over empty rows gathers every note it touches, shift-click adds one, and what is gathered moves, is turned up or is taken away with Delete as one; a lane of bars under the roll carries the notes' velocities, each dragged up or down. The roll opens on as many keys as it is tall enough to draw, so a line of three notes still has octaves to write in. Everything snaps to a sixteenth. The first edit of a window makes its notes the track's own - the file is from then on only where they came from, and a button reads it again - so a hand never changes what a file is read as. | M |
| 10.4 | ✓ **Done.** **A patch per melody track.** Every melody track has an instrument of its own: a synth from a preset, with its own knobs, delay, reverb, crush and sidechain. The synth panel shows the selected track's and puts its knobs back where they were left; a delay locked to the beat follows a new tempo on every track, not only the one on show. In the engine a `Jam` names a synth per track, and each live synth has a channel of its own through its own effects, closed once the jam has let the synth go and its tail has died away. | M |
| 10.5 | ✓ **Done.** **Each melody track can go out over MIDI** instead of being played here, rather than the one global "Melody over MIDI" switch. The instrument panel's Synth / External MIDI switch is the track's own, and so are its channel, its CC and its filter; the device and the latency stay the jam's. A new track starts empty, with a synth and the lowest channel no other has, leaving 10 to the drums. The engine sends each note on its track's channel, and the session always has somewhere to send them - whatever device is connected when a note is due - so connecting, disconnecting and sending a track out all happen while the jam plays; a track sent out with no device connected is played here. | S |
| 10.6 | **Saving a jam with its tracks** (6.x's territory): a jam file naming each track, its source — its window, or its own notes written out — its patch and its channel, so a set survives the window closing. Wants 6.1 first, which works out how a patch is written down. | M |

## Phase 11 — A hand that does not know the keyboard yet

The roll can be written in now, which is the moment the workshop's hardest question arrives: what
to write. Someone who does not read music can drag notes about and hear that some of them are
wrong without knowing which, and nothing in the window tells them. This phase is about the window
knowing a little theory and lending it — showing which notes belong, playing one under the finger,
and turning one note into a chord — without ever taking the choice away.

| Step | What | Effort |
| --- | --- | --- |
| 11.1 | ✓ **Done.** ★ **A keyboard you can play.** The roll's keys sound when clicked, through the selected track's own instrument and its effects, or out over MIDI on that track's channel when it goes there; a key lights while a note of the loop is sounding on it. A key is heard whether the jam plays or not: with it stopped, the studio opens a session that compiles no loop at the first key pressed, so nothing holds the audio device until someone asks to hear something. A note lasts 0.7 s, since a voice is made with its length rather than let go of by hand. | S |
| 11.2 | ★ **A key and a scale for the jam**, named once (C minor, F Dorian) and shown everywhere: the roll shades the rows that are not in it, so what belongs is the part that stays white. Nothing is forbidden — a note off the scale still plays, it just looks like the choice it is. The grid's rows and the piano's keys take the same shading. | M |
| 11.3 | **Chord tones marked against the bar.** With a chord per bar (Cm, A♭, E♭), the roll marks the rows of the chord under the playhead more strongly than the rest of the scale, so the notes that will sound consonant are the obvious ones to reach for. Where the chords come from is the question: named by hand at first, later read from the MIDI file a track came from. | M |
| 11.4 | **One note into a chord.** A melody track can be told to play chords: each note it holds becomes the chord of that bar, voiced from the note played — so a beginner draws a line and hears a progression. The mapping belongs to the track, beside its patch, and what it makes is ordinary notes, so the roll shows what will actually sound. | M |
| 11.5 | **A bar of suggestions.** With a scale and a chord known, the studio can offer a handful of phrases that fit — an arpeggio, a held root, a walking line — as one click each, to take or to change. It is the last step, not the first: it is only worth anything once 11.2 and 11.3 have taught the window what fits. | L |

---

## What waits on what

Most of the list is independent; these are the ties worth knowing before picking something up.

- **1.2 sends** need **1.1**'s buses, and **3.4**'s per-layer effects need 1.2. Note that each
  synth now carries its own delay and reverb, so sends are about *sharing* one, not about having
  any at all.
- **10.2**'s third source is **3.1**: the roll and the MIDI window are done, live code is not.
- **10.6** (a jam file) wants **6.1** (a sound file) first: a jam names each track's patch, so
  something has to know how a patch is written down.
- **11.3** and **11.4** want **11.2**: a chord means little until the window knows the key. **11.5**
  wants both, which is why it is last.
- **8.2** (the Android spike) wants **8.1** (the core module), and so does **7.6**: the same seam
  that lets a phone play the engine lets a step branch depend on it instead of copying it. That is
  two reasons for one piece of work.
- **4.1** (clip grid) and **1.1** are unblocked now that 10.1 has given the studio tracks.
- **11.1** (a keyboard that sounds) is unblocked by 10.4: a key can be played through the selected
  track's own instrument.

## What is next

For a studio meant to be **played live**, with the musical help of Phase 11 next and undo woven in:

1. ~~**11.1 — a keyboard you can play.**~~ Done.
2. ~~**4.4 — undo.**~~ Done.
3. **11.2 — a key and a scale.** Shading the rows that do not belong is what turns the roll from a
   grid into a guide, and 11.3 and 11.4 both stand on it.
4. **The rest of Phase 9** — Cutter (9.5), Reverb (9.11) and Pitch (9.16) are the three missing
   strips a set actually reaches for, and **1.5**, a limiter, before stacking them.
5. **11.3 / 11.4 — chords**, once the key is known.

Kept for when the purpose changes: **6.1 + 10.6** (saving) matter the moment other people use this;
**3.1** (`note(...)`) is the first thing anyone asks about in a workshop; **8.1** (the core module)
is worth doing for its own sake, as a seam and as tests that need no audio device.

## Sources

- Julius O. Smith III, *Physical Audio Signal Processing*, §3.6 (CCRMA) — Freeverb's structure, and
  what `roomsize` and `damping` mean. Already the basis of our reverb.
- Freeverb `tuning.h` (Jezar at Dreampoint, 2000, public domain) — the comb and allpass lengths.
- Strudel documentation — *Audio effects* (the signal chain, orbits, `duck`) and *Mini-notation*
  (the pattern language our parser covers a corner of).
- Ableton Live 12 reference manual, *Session View* — clips, tracks, scenes and launch behaviour.
- Ableton Link repository — header-only C++, dual GPLv2+/proprietary licence.
- MIDI Association, *MIDI 2.0* — MIDI-CI, UMP, and why this stays out of scope.
- Koala Sampler manual, *Effects* (manual.koalasampler.com) — the Perform FX and what each one
  does as the finger slides: Crush is a "bitcrusher with transistor distortion", Talkbox a "formant
  filter based on human vocal tract".
- Csound manual, *Appendix D. Formant Values* — five formants a vowel, with levels and bandwidths,
  per voice type; the tenor's are what Talkbox sweeps between.
- Klatt, *Software for a cascade/parallel formant synthesizer* (JASA 1980), and the Csound
  Journal's *The Talk-Box and Formant Filtering* (Spring 1999) — why a parallel formant branch
  alternates its signs, and why a talkbox distorts before the formants rather than after. Gathered
  in [research/performance-fx.md](research/performance-fx.md), with what came of them.
- Android platform APIs — `AudioTrack` (low-latency performance mode), Oboe/AAudio, and
  `android.media.midi` for USB MIDI; none of `javax.sound.*` exists there.

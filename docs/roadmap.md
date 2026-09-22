# Studio roadmap

Where BeatStudio could go next, and what each step is worth. Written after the session that made
the synth play live, gave it effects and a panel, so it starts from what exists rather than from a
blank page.

## Where it stands

- **Engine.** `AudioEngine` renders block by block and compiles each loop at its first frame, so the
  song can change while it plays. Drums are samples; the synth is a live `VoiceSource` making frames
  as it goes, which is why a knob is heard inside a sounding note.
- **Routing.** Two paths only: drums mixed dry, synth voices through one bus carrying a ping-pong
  delay and a Freeverb-style reverb, ducked under the kick when the panel asks for it. Stop
  releases rather than cuts, so tails ring out.
- **Synth.** One engine (`NovasawSynth`), seven unison saws, a sub sine, a diode shaper, a
  state-variable lowpass on an envelope of its own. Six patches, levelled against one another: anthem, pluck, pad,
  chords, sub bass, acid bass.
- **Control.** A panel of knobs, an XY pad and a draggable envelope, all publishing `SynthParams`
  and `EffectParams`. Presets load a patch's own numbers; the delay can lock to the jam's tempo.
- **Live code.** `s("bd(3,8,5)")`-style mini-notation drives the drum grid; the melody comes from a
  MIDI file, not from code.
- **Out.** Native audio, or the melody to an external synth over MIDI with a latency offset.

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
| 1.1 | **A bus per track**: each drum track and the synth get gain, pan, mute and solo. | M |
| 1.2 | **Sends**: one delay and one reverb shared by the tracks that want them, each with a send level — Strudel calls this an *orbit*, one delay and one reverb per orbit. | M |
| 1.3 | ✓ **Done.** ★ **Sidechain ducking**: the kick ducks the synth bus. Strudel's `duck` works on the whole orbit; ours can start with one source and one target. | S |
| 1.4 | **Meters**: a level readout per track, drawn like the knobs. | S |

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

## Phase 3 — Live code that reaches the melody

The grid and the code understand each other; the melody does not.

| Step | What | Effort |
| --- | --- | --- |
| 3.1 | ★ **`note("c3 e3 g3")` driving the synth**, so a jam can be written rather than loaded from a MIDI file. | M |
| 3.2 | **More mini-notation**: `<a b>` (one per cycle), `?` (maybe), `*`/`/` on any subsequence, `.fast`/`.slow`. The Strudel reference is the map; the subset we have is a corner of it. | M |
| 3.3 | **Grid → code**: clicking a step rewrites the pattern text, so the two views stop fighting. | M |
| 3.4 | **Per-layer effects** in code (`.room`, `.delay`), which needs Phase 1's sends. | M |

## Phase 4 — Playing a set, not a loop

| Step | What | Effort |
| --- | --- | --- |
| 4.1 | ★ **Clip grid (session view)**: columns are tracks, rows are scenes, one clip per track at a time, launched on the next bar. Ableton's rules are worth copying exactly — a track plays one clip, a scene launches a row, and launches are quantized. Our loop-boundary compile is already that mechanism. | L |
| 4.2 | **Piano roll** for the melody, with the playhead and dragging. | L |
| 4.3 | **Recording**: capture clip launches and knob moves, then render the result to WAV (`writeWav` exists) and to a MIDI file. | M |
| 4.4 | **Undo** for code runs and grid edits. | S |
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

## Phase 7 — The workshop itself

| Step | What | Effort |
| --- | --- | --- |
| 7.1 | Port the state-variable filter fix to `step-5` / `step-5-final`. | S |
| 7.2 | A step built on the live voice: "make the filter follow the envelope" is a good exercise now that the engine plays voices rather than samples. | M |
| 7.3 | Update the cheat sheets in `docs/sciagi` once the steps change. | S |

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
| 9.6 | **Dirty**: overdrive. `NovasawDsp.shapeDiode` on the mix, with the slide as drive. | S |
| 9.7 | **Ring**: ring modulation, slide up for a faster carrier. | S |
| 9.8 | **Comb**: a short feedback delay, slide up for a longer one — the metallic, pitched ring. | S |
| 9.9 | **Gate**: mutes whatever falls below a threshold, slide up to raise it; with a fast release it chops tails and reverb away. | S |
| 9.10 | **Tempo Delay** and **Dub**: `SynthEffects`' delay over the whole mix, synced; Dub adds feedback as it slides, until it runs away. | S |
| 9.11 | **Reverb**: our Freeverb over the whole mix, slide up for a bigger room — a wash to throw a break into. | S |
| 9.12 | **VibroFlange**: above the centre a flanger (a short swept delay), below it a pitch wobble (a modulated delay without feedback). | M |
| 9.13 | **Compressor**: one knob, the higher the harder it pumps; an envelope follower, threshold and ratio together. | M |
| 9.14 | **Reverse**: plays the last stretch of the mix backwards, slide up for a longer stretch — a buffer read in reverse, lined up with the beat so it lands on time. | M |
| 9.15 | ★ **Talkbox**: a formant filter — two or three band-passes per vowel — sweeping a-e-i-o-u as the finger slides. The most voice-like of the set, and a good exercise in filters. | M |
| 9.16 | **Pitch**: all of it up or down from the centre. In the note domain, as Stutter is: transpose the synth's notes and resample the drums, rather than a pitch shifter on the audio. | M |

Worth doing in the order of the stars: the plumbing, then Crush and Filter (quick, and heard at
once), then Talkbox. Koala's mixer effects (EQ, limiter, bit cooker, tape delay and the rest) are a
different thing — per-channel inserts — and belong with Phase 1's desk; a limiter on the master,
at least, would save the mix from clipping once a few of these are stacked.

---

## If only three things happen

1. **Sidechain ducking on a per-track mixer** (1.1 + 1.3). Loudest result per hour spent.
2. **`note(...)` in live code** (3.1). It joins the two halves of the program: the code writes the
   drums but not the tune, which is the first thing anyone asks about.
3. **Save a sound** (6.1). Everything else is undermined by losing a patch when the window closes.

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
  does as the finger slides.
- Android platform APIs — `AudioTrack` (low-latency performance mode), Oboe/AAudio, and
  `android.media.midi` for USB MIDI; none of `javax.sound.*` exists there.

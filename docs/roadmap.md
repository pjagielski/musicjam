# Studio roadmap

Where BeatStudio could go next, and what each step is worth. Written after the session that made
the synth play live, gave it effects and a panel, so it starts from what exists rather than from a
blank page.

## Where it stands

- **Engine.** `AudioEngine` renders block by block and compiles each loop at its first frame, so the
  song can change while it plays. Drums are samples; the synth is a live `VoiceSource` making frames
  as it goes, which is why a knob is heard inside a sounding note.
- **Routing.** Two paths only: drums mixed dry, synth voices through one bus carrying a ping-pong
  delay and a Freeverb-style reverb. Stop releases rather than cuts, so tails ring out.
- **Synth.** One engine (`NovasawSynth`), seven unison saws, a sub sine, a diode shaper, a
  state-variable lowpass. Five patches: anthem, pluck, pad, sub bass, acid bass.
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
| 1.3 | ★ **Sidechain ducking**: the kick ducks the synth bus. Strudel's `duck` works on the whole orbit; ours can start with one source and one target. | S |
| 1.4 | **Meters**: a level readout per track, drawn like the knobs. | S |

Why first: pumping bass under a kick is the sound of the genre the workshop plays in, and the
mixer is the thing that makes the studio feel like a studio rather than a demo.

## Phase 2 — The synth gets deeper

| Step | What | Effort |
| --- | --- | --- |
| 2.1 | ★ **A filter envelope of its own.** Today `Env→Filt` reuses the amplitude envelope; a separate ADSR for the filter is what separates a preset from an instrument. Strudel keeps `lpenv` apart from the amp envelope for the same reason. | M |
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

# A second engine for the bass: what the sources say

Notes behind the question of what to build beside `NovasawSynth`, gathered in September 2026 while
the six patches were the only instrument in the tree and two of them were called bass. Sources at
the end; everything below is either from them or measured here, on this machine, with the JDK the
project builds on (Temurin 25.0.4, one core, no SIMD). The measurements are rough — a loop with a
warm-up and the best of seven runs, not JMH — but they are consistent with each other, which is all
the argument below asks of them.

## What the engine already is, measured

`NovasawVoice.next()` takes **276 ns a frame** for one note, which is about **75 voices to a core**
at 48 kHz. Roughly four fifths of that is transcendental: seven `Math.pow` for the unison detune
ratios (15.4 ns each), seven `Math.sin` for the per-voice drift, one more for the vibrato, one for
the sub, one `Math.tan` for the filter coefficient, two `Math.exp` inside the diode shaper and one
per envelope for its coefficient. Per call, on this JDK: `sin` 7.9 ns, `cos` 8.9, `exp` 7.0, `tan`
11.5, `tanh` 12.1, `pow` 15.4, a 2048-point interpolated sine table 4.3, a bare multiply 0.68. One
frame at 48 kHz is 20.8 µs, so a single voice may spend 1700 `tanh` calls before it is late.

That number is the frame this whole question sits in, and it points the other way from the usual
worry: **the engine already in the tree is the most expensive thing considered here.** Everything
below is cheaper.

## What the two bass patches actually do

`SubBassSynth` and `AcidBassSynth` are the same seven saws at different settings, and their settings
are worth reading as numbers rather than as their docstrings. Printed from the compiled classes:

| | `SubBassSynth` | `AcidBassSynth` |
| --- | --- | --- |
| detune | 1.72 cents | 6.48 cents |
| sub level | 0.70 | 0.45 |
| cutoff | 835 Hz | 1322 Hz |
| filter envelope | +302 Hz | +3400 Hz |
| key tracking | 16 Hz/semitone | 22 Hz/semitone |
| resonance parameter | 0.215 | 0.342 |
| filter decay / sustain | 0.22 s / 0.90 | 0.14 s / 0.08 |

The resonance parameter is the one that matters. `NovasawDsp.LowpassFilter` is a two-pole
topology-preserving state-variable filter in the standard form, where the damping term `k` is
`1/Q`; the code sets `k = clamp(1.82 - resonance * 1.25, 0.45, 2.0)`. At the acid patch's 0.342
that is `k = 1.392`, so **Q = 0.718** — a hair above Butterworth's 0.707, which puts the peak at
0.004 dB. There is, to the ear, no peak at all. The ceiling is not much better: the Res knob runs to
0.95 (`SynthControls.RESONANCE`), which gives `k = 0.633` and **Q = 1.58, a 4.4 dB bump** two poles
wide; patches built through `NovasawSynth`'s own derivation are clamped to 0.82, so Q = 1.26 and
2.4 dB. (`k`'s floor of 0.45 would be Q = 2.22, but it needs a resonance of 1.10 and nothing can
ask for that.) `AcidBassSynth`'s docstring says
"resonance high enough to whistle around it"; the numbers say a maximally flat two-pole lowpass with
a fast envelope on it. That is not a bug — it is a patch that was tuned by ear and sounds like
something — but it means the acid sound is currently being made entirely by the envelope and the
diode drive, with the filter contributing slope and nothing else.

For scale: Open303 fits the TB-303's cutoff knob to a measured range of **313.8 Hz to 2394 Hz**
(`calculateEnvModScalerAndOffset`, two named constants `c0` and `c1`), and clamps the filter to a
floor of 200 Hz. The acid patch's cutoff starts at 1322 Hz before key tracking and sweeps to about
4.7 kHz. It is working an octave and a half above the machine it is named after.

## The architectures worth considering, and what each one can do that seven saws cannot

### A 303 done properly — the diode ladder and the shape of its envelope

The character is in three places, and none of them is the oscillator.

**The filter is a diode ladder, not a transistor ladder and not a two-pole.** Zavalishin's *The Art
of VA Filter Design* gives it a section of its own (§5.10, p. 164, after the Moog ladder at §5.1):
four one-pole lowpasses in series, but with **½ gain elements between the stages** and each stage
loaded by the one after it, so the stages are coupled in both directions rather than being four
copies of one filter behind a single global feedback path. Zavalishin even remarks that it would
have been more consistent to have no ½ gain element at the input of the fourth lowpass — the
asymmetry is in the circuit, not in the model. Odin 2's `DiodeFilter.cpp`, which is where Surge's
diode ladder came from and which credits Will Pirkle's book, shows the same thing as arithmetic:
`G4`, then `G3` computed from `G4`, then `G2` from `G3`, then `G1` from `G2`; each stage's `β`, `γ`
and `ε` depend on the stages downstream of it; the input gain `a0` is 1.0 for the first stage and
0.5 for the other three; and the whole zero-delay loop resolves in one division,
`u = (x - k·σ) / (1 + k·γ)`, where `σ` is a weighted sum of all four stages' feedback outputs. Its
`k` runs 0 to 16, with a passband make-up of `1 + 0.3·k`.

What that buys, and what the SVF cannot give: a resonant peak whose **width and slope change with
resonance** rather than only its height, and enough feedback to self-oscillate. The two-pole SVF has
one peak of fixed shape and a ceiling of Q = 1.58 — 4.4 dB. The squelch is not a louder peak, it is
a peak that narrows and drags the slope around with it.

Open303 takes a shortcut worth knowing about: `TeeBeeFilter`'s `TB_303` mode is not the
textbook ladder at all but a four-stage coupled difference equation attributed in a code comment to
"mystran & kunn (page 40 in the kvr-thread)" —

```
y1 += 2*b0*(y0-y1+y2);   y2 += b0*(y1-2*y2+y3);
y3 += b0*(y2-2*y3+y4);   y4 += b0*(y3-2*y4);
```

— with `b0` and the feedback factor `k` both given as fitted polynomials in the normalised cutoff,
and an output gain `g` that depends on resonance. Note that each stage reads the stage *after* it,
which is the coupling again, in four lines. Also note that in this branch the nonlinearity is
commented out: `shape()` (a clipped cubic, `x - x³/6` clamped to ±√2) exists but the 303 path runs
the ladder linearly.

**The envelopes have no attack stage and no sustain.** Robin Whittle, who designed the Devil Fish
modification and wrote up the circuit, names two: a Volume EG with "Sharp attack, exponential decay,
fixed decay and rather long time", driving the VCA and nothing else; and a Main EG, also "sharp
attack and exponential decay", driving the filter through the Env Mod pot. Open303 implements
exactly that: `DecayEnvelope` is one multiply a sample (`y *= c`) with no stages at all, and the
attack is supplied afterwards by a `LeakyIntegrator` — so the filter envelope is **two one-poles in
series**, a decaying exponential smoothed by an RC, and its "attack" is the smoothing time constant
(3 ms normally, 15 ms on the accent path). Decay is 1000 ms for normal notes, 200 ms for accented
ones. The amplitude envelope is attack 0, decay 1230 ms, sustain 0, release 0.5 ms — 50 ms on an
accented note.

And it modulates the cutoff **in octaves, not in hertz**: `instCutoff = cutoff * pow(2, tmp1+tmp2)`.
`NovasawVoice` adds `filterLevel * filterEnvAmountHz`. An additive sweep of 3400 Hz from a 1322 Hz
base is a different gesture from a multiplicative sweep of two octaves from 400 Hz: the additive one
spends most of its time in the top half of the sweep, the multiplicative one spends equal time per
octave, which is what the ear measures.

**Accent is three things at once, and it accumulates.** Whittle's page is the primary description.
On an accented note the Main EG's decay is shorted to its short time; the Main EG is also routed
into the VCA's control current through a 47 kΩ / 0.033 µF network, "to soften the attack a little",
which "is the primary reason why accented notes are louder"; and it is routed into the filter
through what he calls the Accent Sweep Circuit — a diode and 47 kΩ into the anti-clockwise end of a
100 kΩ pot with a 1 µF capacitor to ground off the other end. With the pot clockwise the filter is
driven from that capacitor as it charges through the diode and 147 kΩ, so the filter "goes 'Wow'. It
rises and falls in a quick, smooth curve" rather than the angular shape an ADSR gives. The 147 kΩ
and 1 µF make the charge time constant about 150 ms, and the drain through 100 kΩ about 100 ms —
which is why, in Whittle's words, when accented notes come in quick succession "the capacitor has
not discharged fully from the one before" and each peak goes *higher*. And the sting in the tail:
**that pot is the second section of the Resonance pot**, so the accent sweep and the resonance are
ganged and cannot be set apart.

Open303 models the first two parts of accent and not the third. `triggerNote` sets
`accentGain = accent` per note, swaps the Main EG decay for `accentDecay`, lengthens the amp release,
and in `getSample` adds `0.45*mainEnvOut + accentGain*4.0*mainEnvOut` into the amplitude envelope
while the note is on — so the filter envelope bleeds into the level, which is the loudness part. But
`accentGain` carries no state between notes, so successive accents do not climb. Nothing in the tree
or in Open303 does the climbing; it is the one piece of the 303 that no open-source emulation read
here reproduces, and it is a single leaky accumulator.

**Slide is a gate that does not close.** Whittle's slide page gives the sequencer semantics
exactly: slide is programmed on a note and ties it to the next; the gate "stays high and runs into
the next", the CV changes to the following note's pitch at the following note's start, and the slide
circuit smooths the step. So a slid pair is *one* note with two pitches, not two notes. Open303
implements it as legato — `noteOn` triggers when the note list is empty and slides otherwise — and
smooths the oscillator frequency with a `LeakyIntegrator` whose time constant is `0.2 * slideTime`;
a code comment says the TB-303's slide time was 60 ms, a figure I could not confirm from Roland or
from Whittle. Note that the smoothing is applied to frequency in hertz, not to pitch, so the glide
is not linear in semitones — an implementation choice, not a measured property of the machine.

This is also where `AcidBassSynth`'s architecture, rather than its numbers, gets in the way.
`LivePitchSynth.voice(midiNote, heldFrames, sampleRate)` makes one voice per note with the pitch
fixed at construction, and `AudioEngine` calls it at each note-on. Glide needs no change to that
interface: `LiveNovasawSynth` is one instrument per track and already holds mutable state, so it can
remember the note it last handed out a voice for and let the new voice start at the old frequency
and slew. What it cannot do that way is suppress the new voice's attack, which is what a tie is —
for that, a still-sounding voice would have to be retuned rather than replaced. So glide is the
cheap four fifths and the tie is the expensive fifth, and they can be shipped apart.

### Two-operator FM

A modulator sine at a ratio of the carrier, added to the carrier's phase, with the modulation index
on its own fast-decaying envelope. What that does and a filter cannot: the index changes **which
harmonics exist and how strong each is**, in a pattern set by Bessel functions of the index, so as
the index falls the spectrum collapses inward toward the carrier — partials above the carrier and
below it both fade, and the ones near the carrier can rise while the far ones fall. A filter can
only attenuate what the oscillator already produced, monotonically with frequency. Chowning's 1973
paper is the thing that owns this and is still the clearest statement of it. At a 1:1 ratio the
sidebands land on harmonics of the carrier and the result is a harmonic tone that goes from a bright
metallic click to a hollow sine as the index decays — a DX-style bass attack, which no envelope on
the SVF imitates, because a closing filter takes the top off evenly and an index envelope changes
the balance between neighbouring partials.

Plaits' `fm_engine.cc` is 60 lines of this and worth reading before writing any of it: two phases,
`SinePM(phase, modulation)` on a lookup table, 4× oversampling, a sub-oscillator at half the carrier
frequency phase-modulated by the carrier, and feedback that at positive settings feeds the
modulator's output back into itself and at negative settings modulates the modulator's *frequency*.
One detail there earns its place — `hf_taming`, which reduces the maximum index for high notes
because FM sidebands run past Nyquist long before the carrier does. Measured here, a 2-operator FM
voice with table sines, a sub and an index envelope costs **7.7 ns a frame**, 36 times less than the
supersaw voice it would sit beside.

Plaits has no index envelope — in a module the envelope comes from outside — so for the shape of the
envelope the reference is the DX7 itself, through msfa (in Dexed). The useful fact there is that the
operator level runs in the **log domain**: `Env::getsample` decrements a level that `Exp2::lookup`
turns into a gain, so each segment is linear in decibels and each operator has four rate/level pairs
rather than an ADSR. A log-domain decay on the index is what makes the attack sound struck rather
than faded.

### Wavetable

A table per position, interpolated along the position axis, with the position on an envelope or an
LFO: the spectrum moves along a path someone chose, rather than along the one path a filter offers.
That is the thing the supersaw cannot do — a filter sweep is always low-pass-shaped, and a wavetable
sweep can put a formant in the middle and move it, or cross from a hollow square to a nasal pulse
without changing the overall brightness at all.

Plaits' `wavetable_engine.cc` is the readable reference and also shows the cost honestly: it
interpolates in three dimensions, which is **eight four-point Hermite table reads per sample**, and
it solves anti-aliasing by storing the tables *integrated* and differentiating the output
(`diff_out_.Process(cutoff, mix)`) rather than by mip-mapping. A one-axis version is two reads and
is cheap. The real objection for this project is not cost: it is that a wavetable engine is only as
good as its tables, and principle 1 of the roadmap is that nothing is shipped as a binary asset.
Surge XT and Vital both ship wavetable files. Tables generated in code are possible — Open303
generates its own saw and square algorithmically and builds a twelve-level mip-map by halving the
spectrum with an FFT — but generated tables tend to be the same handful of maths curves the
oscillator could make directly, and then the engine has bought nothing.

### Phase distortion (Casio CZ)

Read one sine table, but advance the read pointer at a rate that varies within the cycle. Casio's
patent, Masanori Ishibashi's US 4,658,691 (priority 17 December 1982), describes it as
"modification means to modify the address signal ... into a modified address signal whose changing
rate varies in one cycle of the waveform", and — the second and more interesting claim — a modified
address that "appoints an address of more than one cycle of a waveform". The first is how a sine
becomes a saw or a pulse: bend the phase ramp into two segments and the sine's peak lands off
centre. The second is the resonant waveform: cram several cycles of the sine into one period of the
note and the result has a peak in its spectrum at the rate the inner cycles run at — a formant you
can sweep, made with no filter and no feedback at all. The patent states the aim plainly: a sound
"having a peak value in the higher frequency region of a spectrum".

That is a genuinely different thing from anything in the tree, and it is the cheapest of all the
candidates: measured here, a one-segment phase warp and one table sine is **4.3 ns a frame**. Plaits
has a phase distortion engine too (`engine2/phase_distortion_engine.cc`), which uses an asymmetric
triangle as the modulator and 2× upsampling. The catch is that phase distortion aliases badly — the
warped ramp has a corner in it, and the sine reading through that corner is a discontinuity in
slope — which is why both Casio's and Plaits' answers involve keeping the sweep off the top octaves.
And its resonant sweep, musically, mostly sounds like a resonant filter sweep. It is a lot of
distinctiveness on paper for a sound the ear files under "acid" anyway.

## The projects worth reading, with licences

**Open303**, Robin Schmidt, `github.com/RobinSchmidt/Open303`, C++, **MIT** ("Copyright (c) 2009
Robin Schmidt", `License.txt`, verbatim MIT text). This is the one to read. The repository is a 2022
clone of the author's old SourceForge project, now being turned into a CLAP plugin; the DSP is about
forty files under `Source/DSPCode` (eighteen classes, a header and a body each, plus an FFT) and the
two that matter are `rosic_Open303.h/.cpp` (about 750 lines
between them, with the whole per-sample chain inline in one readable `getSample()`) and
`rosic_TeeBeeFilter.h` (328 lines). It is readable: plain doubles, named variables, comments that
say what the real machine did and where a value came from, and — unusually — comments admitting what
is a fit and what is a guess ("\todo: find some more suitable nonlinearity here", "seems not to work
yet", `n1 = 1.0; // test`). The fitted polynomials in `calculateCoefficientsApprox4` are opaque by
nature, but the exact formulas sit right above them and can be used instead. MIT means it could be
copied, but the project has no LICENSE file, so read-and-reimplement with a citation is the right
mode regardless.

**JC303**, `github.com/midilab/jc303`, C++/JUCE, **GPL-3.0** with the README stating that "The
Open303 engine part of this software is also licensed under the MIT License". Worth knowing as the
maintained build of the same engine, and its README is a good short spec of 303 sequencer behaviour
(per-step rest, accent, slide and tie; the "authentic 303 half-step (50%) gate"). Not a better DSP
read than Open303 itself.

**Odin 2**, `github.com/TheWaveWarden/odin2`, C++, **GPL-3.0** (per the file headers; GitHub reports
the repository licence as unrecognised). `Source/audio/Filters/DiodeFilter.cpp` is **151 lines of
scalar double arithmetic** and is the clearest diode ladder in any of these projects. It says in a
comment that it follows Will Pirkle's *Designing Software Synthesizer Plug-Ins in C++*. GPL-3, so
read it, do not lift it.

**sst-filters**, `github.com/surge-synthesizer/sst-filters`, C++, **GPL-3.0**. Its
`include/sst/filters/DiodeLadder.h` says in its own comment that it is "an adaptation of the filter
from" Odin 2's file above — and it is the same filter written in SSE intrinsics behind
one-letter macros (`#define M(a,b) SIMD_MM(mul_ps)(a,b)`), with a `@TODO` noting that two of the
arguments look unused. As a DSP reference it is unreadable; go to Odin 2 instead. Same verdict for
Surge XT's own `WavetableOscillator.cpp` and Vital's oscillator (both **GPL-3.0**): production
SIMD, not teaching code.

**Plaits**, in `github.com/pichenettes/eurorack`, C++, **MIT** (per-file headers; there is no
top-level LICENSE file, which is why GitHub reports none). The best-written DSP in this list by a
distance: `plaits/dsp/engine/fm_engine.cc` for 2-op FM, `engine2/phase_distortion_engine.cc` for
phase distortion, `engine/wavetable_engine.cc` for wavetables, each 100–200 lines including the
licence header, scalar floats, one clearly named thing per line. Written for a Cortex-M4 at 48 kHz,
which is roughly the discipline plain Java needs.

**Dexed** and **msfa**, `github.com/asb2m10/dexed`, C++. Dexed is **GPL-3.0**; its README states
that "The msfa component ... stays on the Apache 2.0 license", and the files under `Source/msfa`
carry the Apache 2.0 header, "Copyright 2012 Google Inc." — so the DX7 engine proper is the
permissive part. Honest judgement: msfa is a **specification of the DX7, not a DSP tutorial**. It is
fixed-point `int32` with log-domain gains, `Exp2::lookup`, shifts, the 32 algorithms packed as hex
bit-flags (`{ 0xc1, 0x11, 0x11, 0x14, 0x01, 0x14 }`), and reverse-engineered constants with
comments like "and so on, I stopped measuring after R=76 (needs to be double-checked anyway)". Read
it for the *data* — the algorithm table, the envelope rate tables, the log-domain envelope — and
read Plaits for how FM is written.

**The Art of VA Filter Design**, Vadim Zavalishin, rev. 2.1.2, PDF, free to download but with no
open-source licence of any kind (it is a book, not code). Chapter 5 is the ladder filter (p. 133),
§5.10 the diode ladder (p. 164), chapter 6 nonlinearities (p. 173). The state-variable filter the
project already uses is chapter 4 of this book. Native Instruments' own copy of the PDF is now a
404; the Internet Archive has rev. 2.1.2.

**Huovilainen, "Non-Linear Digital Implementation of the Moog Ladder Filter"**, DAFx-04, Naples —
the proceedings PDF is free from dafx.de. The cost sentence is in the paper: with calculations
shared between stages "the implementation requires only five tanh-function evaluations", and "some
oversampling is required to avoid aliasing". Relevant here as the transistor-ladder counterpart and
as the source of the honest per-sample count.

**Robin Whittle's Devil Fish pages**, `firstpr.com.au/rwi/dfish/`. Not code: first-party circuit
analysis by the person who designed the best-known TB-303 modification, with component values.
`303-unique.html` is the Accent Sweep Circuit; `303-slide.html` is the slide and gate timing. This
is the primary source for accent and slide behaviour, and it is where both differ from what any
emulation does.

**Chowning, "The Synthesis of Complex Audio Spectra by Means of Frequency Modulation"**, JAES 21(7),
1973, pp. 526–534. Behind the AES paywall; reachable as a scan on a university course page. The
paper that owns FM, and the place the index-versus-spectrum relation is stated.

**Casio, US 4,658,691** (Masanori Ishibashi, Casio Computer Co., priority 17 December 1982, filed
17 October 1985). Free on Google Patents. The primary description of phase distortion, in the
patent's own words rather than a blog's.

## What is worth the work, and in what order

**Deepen what exists, and do it as the roadmap already says.** Three of the four things a bass most
wants are already listed in Phase 2 as not done — 2.2 glide, 2.4 unison voice count as a parameter
(the acid patch is running seven saws at 6.5 cents where the machine had one oscillator), 2.5 pitch
envelope — and 2.3 highpass and bandpass is the same filter rewrite as the item below. The argument
for doing these before writing any second engine is that they are where the measured gap is. The
acid patch's filter is at Q = 0.72 and cannot go past 2.22; its sweep is additive where the 303's is
multiplicative; its envelope has an attack stage and a sustain the machine did not have; it re-attacks
on every note where the machine tied them. Four small changes, each independently audible:

1. **A diode ladder beside the SVF**, chosen per patch. Odin 2's file is 151 lines and this project
   already computes `tan(π·fc/fs)` once a frame, which is the only transcendental the ladder needs.
   Measured here, a saw plus a diode ladder with coefficients recomputed every frame is **58 ns a
   frame**, 112 ns at 2× oversampling and 219 ns at 4× — all of them *less* than the 276 ns the
   supersaw voice costs now. This is not a performance question. It is the item that makes "acid"
   mean something, and it subsumes 2.3, since a ladder gives highpass and bandpass by tapping and
   summing the stages, which is exactly what `TeeBeeFilter`'s `c0..c4` coefficients are for.
2. **Glide**, roadmap 2.2, which needs no interface change: `LiveNovasawSynth` remembers the last
   note and the new voice slews from it. Open303's leaky integrator on frequency is the whole of the
   mechanism; do it on pitch rather than hertz and say so.
3. **A decay-plus-RC filter envelope** as an alternative envelope shape, per patch. It is cheaper
   than the ADSR it replaces — one multiply plus one one-pole — and it is the difference between a
   preset that sweeps and one that goes "wow".
4. **Accent**, which does not exist anywhere in the roadmap and is the cheapest distinctive thing
   here: a velocity threshold (Open303 uses `velocity >= 100`) that shortens the filter decay, bleeds
   the filter envelope into the level, and — the part no emulation read here does — feeds a leaky
   accumulator so that accents in quick succession climb, as Whittle's capacitor does. The project
   already has per-note velocity in the piano roll's velocity lane. This is the one place where
   reading the primary source rather than another emulation buys something nobody else has.

**Then a 2-operator FM engine as the actual second engine.** Of the three genuinely new
architectures it has the best ratio of new sound to new code: two phase accumulators, a shared sine
table, an index on a log-domain decay, a ratio, and a sub — call it 150 lines, measured at 7.7 ns a
frame, and it fits `LivePitchSynth` unchanged (one voice per note, parameters read per frame from a
volatile record, exactly as `NovasawVoice` does). It gives the project a spectral gesture the filter
cannot make, it gives the workshop the cleanest possible demonstration that timbre is not the same
as brightness, and it costs a 36th of what the existing voice costs. Do not attempt six operators
and 32 algorithms; a fixed two-operator stack with a ratio knob and an index envelope is where all
the DX bass presets live anyway.

**Wavetable and phase distortion: later, or not at all.** Wavetable is the strongest of the three on
sound and the weakest on fit, because its value is in its tables and the project ships no binary
assets. Phase distortion is the cheapest to write and the least likely to be noticed: its resonant
sweep is a formant sweep, and once a diode ladder is in the tree the ear will hear both as the same
gesture. If either is built, build phase distortion, because it is 30 lines and it is a good lesson
about what a phase accumulator is.

## What not to do

Note first what is *not* on this list. Every architecture measured here — 2-op FM at 7.7 ns, phase
distortion at 4.3, a 4× oversampled diode ladder at 219, a 6-operator FM stack with six envelopes at
37.6, a 16-partial additive bank at 43.5, Huovilainen's nonlinear ladder with a `tanh` per stage at
94.5 (194 at 2×) — is **cheaper per frame than the 276 ns `NovasawVoice` already spends**, and a
frame at 48 kHz is 20.8 µs. "Too slow for plain Java" is, for everything on this page, folklore. The
real budget is written in code, not cycles: every parameter added is a knob on the panel, a field in
`SynthParams`, and a thing a workshop participant has to be told about.

The cases where cost genuinely bites:

- **Oversampling the engine that is already there.** Roadmap 2.6 (oversampling the saw and the
  shaper) is marked L, and it is the one item on the list where the arithmetic is against it: 4× of a
  voice that already costs 276 ns puts the oscillator and shaper part near 1 µs, taking the ceiling
  from about 75 voices a core to something like 20. A second engine that is cheap and clean to
  oversample is a better use of the same effort than oversampling a seven-saw voice.
- **Per-voice unison in a new engine.** Seven `Math.pow` a frame is what makes the current voice
  expensive. Any new engine should compute detune ratios once per note or from a table, not per
  frame per voice — which is also roadmap 2.4's real motivation.
- **Anything with an iterative solve per sample.** A nonlinear ladder with a Newton or fixed-point
  iteration inside the feedback loop multiplies the `tanh` count by the iteration count and makes the
  cost depend on the signal, which is the one thing a fixed audio block budget cannot absorb. The
  explicit forms — Huovilainen's Euler solution, or the zero-delay-feedback division in the diode
  ladder — have closed forms and should be used.
- **Anything with an FFT in the voice path.** Additive resynthesis from spectra, phase-vocoder
  effects and spectral warping all bring block latency and a windowing argument with them, and none
  of it answers a question dance bass is asking. (An FFT to *generate* a table once at start-up, as
  Open303 does for its mip-map, is a different matter and is fine.)
- **Physical modelling and anything sample-based.** A plucked or bowed model is a good workshop
  subject and a poor bass: dance bass wants a stable pitch, a flat body and a fundamental that does
  not move, which is what makes a physical model interesting and a bass patch wrong. Sample-based
  anything — a multisampled bass, a shipped wavetable set — is out on principle 1 before it is out
  on cost.
- **A six-operator, 32-algorithm DX7.** Cheap enough at 37.6 ns, and still the wrong thing: the
  work is not the DSP, it is the 155 parameters, the four-stage rate/level envelopes per operator,
  the keyboard scaling curves and a patch format. msfa is 25 files for this. Two operators get the
  bass sounds; the other four get the bell sounds nobody asked for.

## Sources

- [Open303 — Robin Schmidt, github.com/RobinSchmidt/Open303](https://github.com/RobinSchmidt/Open303),
  C++, **MIT** (`License.txt`, "Copyright (c) 2009 Robin Schmidt"). The primary reference for
  everything in the 303 section: `rosic_TeeBeeFilter.h` for the `TB_303` filter mode, the 150 Hz
  feedback highpass and the resonance skew; `rosic_Open303.h/.cpp` for the 4× oversampling, the
  exponential cutoff modulation, the accent routing, the slide slew limiter and the measured
  313.8–2394 Hz cutoff range; `rosic_DecayEnvelope.h` and `rosic_LeakyIntegrator.h` for the envelope
  shape; `rosic_MipMappedWaveTable.h` for algorithmically generated 303 saw and square.
- [KVR forum thread 262829, "Open303 — open source 303 emulation project"](https://www.kvraudio.com/forum/viewtopic.php?t=262829)
  — named in `rosic_TeeBeeFilter.h` as the source of the `TB_303` coupled-stage structure, "ala
  mystran & kunn (page 40 in the kvr-thread)". **Not verified here:** KVR returns 403 to this
  machine, so the attribution rests on Robin Schmidt's code comment, not on reading the thread.
- [JC303 — github.com/midilab/jc303](https://github.com/midilab/jc303), C++/JUCE, **GPL-3.0** with
  the Open303 engine part stated to remain MIT. Used for the sequencer semantics: per-step rest,
  accent, slide and tie, and the 50 % gate.
- [Odin 2 — github.com/TheWaveWarden/odin2](https://github.com/TheWaveWarden/odin2), C++,
  **GPL-3.0** (file headers). `Source/audio/Filters/DiodeFilter.cpp`, 151 readable scalar lines, is
  the diode ladder reference: the downstream-dependent `G1..G4`, the 0.5 input gains, the `σ` sum and
  the one-division zero-delay resolution. It credits Will Pirkle, *Designing Software Synthesizer
  Plug-Ins in C++*.
- [sst-filters — github.com/surge-synthesizer/sst-filters](https://github.com/surge-synthesizer/sst-filters),
  C++, **GPL-3.0**. `include/sst/filters/DiodeLadder.h` states in its own comment that it adapts
  Odin 2's file. Cited here only as the counter-example: correct, fast, SIMD-macro, unreadable.
- [Plaits, in github.com/pichenettes/eurorack](https://github.com/pichenettes/eurorack), Émilie
  Gillet, C++, **MIT** (per-file headers; no top-level LICENSE file).
  `plaits/dsp/engine/fm_engine.cc` — 2-op FM, 4× oversampling, `SinePM`, the sub at half the carrier,
  the two signs of feedback and `hf_taming`. `plaits/dsp/engine2/phase_distortion_engine.cc` — phase
  distortion with an asymmetric triangle modulator. `plaits/dsp/engine/wavetable_engine.cc` — the
  eight Hermite reads per sample and the integrated-table differentiator.
- [Dexed — github.com/asb2m10/dexed](https://github.com/asb2m10/dexed), C++, **GPL-3.0**, with
  `Source/msfa` under **Apache 2.0** ("Copyright 2012 Google Inc.", and the README says so
  explicitly). Used for the DX7's 32-algorithm table (`fm_core.cc`) and its log-domain,
  four-rate/four-level envelope (`env.cc`). Judged a specification rather than a reference.
- [Surge XT](https://github.com/surge-synthesizer/surge) and [Vital](https://github.com/mtytel/vital),
  both C++ and **GPL-3.0** — looked at for the wavetable question and set aside: production SIMD, and
  both ship wavetable data files, which principle 1 of the roadmap rules out here.
- [Vadim Zavalishin, *The Art of VA Filter Design*, rev. 2.1.2](https://archive.org/details/the-art-of-va-filter-design-rev.-2.1.2)
  — free PDF, no code licence. §5.10 "Diode ladder" (p. 164) for the ½ inter-stage gains and the
  coupling; chapter 5 for the ladder generally, chapter 6 for nonlinearities. Native Instruments'
  own hosted copy (`VAFilterDesign_2.1.0.pdf`) now 404s; the Archive copy was read directly.
- [Antti Huovilainen, "Non-Linear Digital Implementation of the Moog Ladder Filter", DAFx-04,
  Naples](https://dafx.de/paper-archive/2004/P_061.PDF) — free from the DAFx paper archive. Source
  of the five-`tanh`-per-sample figure and of "some oversampling is required to avoid aliasing".
- [Robin Whittle, "TB-303's unique characteristics"](https://www.firstpr.com.au/rwi/dfish/303-unique.html)
  and [ "TB-303 Slide"](https://www.firstpr.com.au/rwi/dfish/303-slide.html), firstpr.com.au.
  First-party circuit analysis with component values: the two envelope generators and their shapes,
  the 47 kΩ / 0.033 µF network into the VCA, the Accent Sweep Circuit's diode, 47 kΩ, 100 kΩ pot and
  1 µF capacitor, the fact that the pot is the second section of the Resonance pot, the climbing
  accent, and the gate-stays-high semantics of slide.
- [John Chowning, "The Synthesis of Complex Audio Spectra by Means of Frequency Modulation", JAES
  21(7), 1973, pp. 526–534](https://web.uvic.ca/~aschloss/course_mat/MU307/MUS307_MATERIALS/Chowning_FM.pdf)
  — the paper that owns FM and the index-to-spectrum relation. Behind the AES paywall at the
  publisher; the link is a scan on a university course page, not a first-party host.
- [Casio, US 4,658,691, "Electronic musical instrument"](https://patents.google.com/patent/US4658691A/en),
  Masanori Ishibashi, Casio Computer Co., priority 17 December 1982 — the primary description of
  phase distortion: an address signal "whose changing rate varies in one cycle of the waveform", and
  one that "appoints an address of more than one cycle of a waveform".
- Checked against the code after the note was written, and two numbers corrected: the acid patch's
  Q is 0.718, which is a shade *above* Butterworth rather than below it (the conclusion — no audible
  peak — is unchanged), and the reachable ceiling is Q = 1.58 from the Res knob's range of 0.95, not
  the 2.22 that `k`'s clamp floor would allow. `SynthParams.of` does not clamp resonance at all; the
  0.82 is in `NovasawSynth`'s own parameter derivation.
- Measured here, not from a source: per-frame costs and per-call transcendental costs on Temurin
  25.0.4, one core, and the printed `SynthParams` of `AcidBassSynth` and `SubBassSynth` with the
  effective `Q = 1/k` worked out from `NovasawDsp.LowpassFilter`.

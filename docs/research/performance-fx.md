# Talkbox and Crush: what the sources say

Notes behind the two Performance FX in `PerformanceFx`, gathered when the first versions of them
were already playing and sounded, in the words of the person holding the strip, "even ok". Sources
at the end; everything below is either from them or measured here.

## What Koala's own two are

Koala's manual gives each Perform FX a line. **CRUSH** is a "bitcrusher with transistor
distortion", so the distortion is not a garnish on the bitcrusher, it is half of what the effect
is. **TALKBOX** is a "formant filter based on human vocal tract", swept between vowels — no
modulator input, no vocoder, no tube: the vowel is baked into the filter.

## Talkbox

### The tables

Csound's Appendix D gives, per voice type and vowel, five formants with a frequency, a level in dB
and a bandwidth. The tenor's five vowels are what this Talkbox sweeps between. The first three
formants were already right here; the fourth and fifth were missing, and they sit at 2.8–3.6 kHz —
the region a singer's voice carries in. Adding them brought back both presence and level.

### Parallel formants cancel unless the signs alternate

Klatt's parallel synthesizer sums adjacent formant resonators in opposite phase. Without it,
"unwanted spectral zeros may occur" where two neighbouring skirts overlap — a notch exactly where a
vowel wants a peak. Every other band is now subtracted rather than added. It costs a little level,
which the makeup gain takes back.

Parallel rather than cascade is the usual choice here (and what JOS's formant example uses): in a
cascade "when one formant filter is resonating, the others will be attenuating", which the
resonating one has to overcome. The price of parallel is that the level of each formant is yours to
set, which is what the dB column of the table is for.

### A talkbox distorts on the way in, not on the way out

The Csound Journal's talk-box article models the real thing — a compression driver pushing sound up
a tube into the mouth — and increases the harmonic content of the signal with `tanh` waveshaping
*before* the formants, because "to produce a more significant response from the formants the
harmonic content of the signal is increased". A band gives back nothing the sound did not bring it.
The drive moved from after the bands to before them.

### Two deliberate departures

A voice's formants are narrow (40–140 Hz) because what goes through them is a buzz dense in
harmonics. A whole mix through bands that narrow whistles and all but disappears, so the bands are
widened (×2.2). And the upper formants sit 10–25 dB under the first, which over a mix loses them
and turns every vowel into an "o", so half the difference in dB is kept. Both are departures from
the table, made by ear and by measurement; with them the vowel holds the mix about 5 dB down.

## Crush

A bitcrusher is two things: sample rate reduction and bit depth reduction, and it is the *lack* of
a filter that makes it one. Downsampling properly means low-passing before you decimate; a
bitcrusher skips that on purpose, and "a lot of the 'grit' of a bitcrusher effect is caused by the
aliasing" — the frequencies that fold back over the Nyquist of the rate it pretends to run at.
Adding an anti-aliasing filter would take away exactly what the effect is for.

Two details that are easy to get wrong:

- **Where the distortion sits.** Koala's is a crusher *with* a transistor stage. Driving the signal
  into a soft clip before the rounding gives the rounding more to bite on; a clip after it only
  rounds off what the rounding just made square. It is now in front, with the output trimmed so
  holding Crush does not jump the level of the jam.
- **Which way it rounds.** Rounding to the nearest level keeps a level at zero, so silence stays
  silent. Rounding to the middle of each step, which some crushers do, never outputs zero and
  leaves half a step of DC under a quiet passage.

What was already right: the sample-and-hold as a phase accumulator, and both axes moving together
on one strip, 700 Hz and 4 bits at the bottom to 11 kHz and 10 bits at the top.

## Sources

- [Effects — Koala Sampler manual](https://manual.koalasampler.com/mobile/9-effects/)
- [Appendix D. Formant Values — Csound manual](https://csound.com/docs/manual/MiscFormants.html)
- [The Talk-Box and Formant Filtering — Csound Journal, Spring 1999](https://csoundjournal.com/ezine/spring1999/processing/index.html)
- [Klatt, *Software for a cascade/parallel formant synthesizer*, JASA 1980](https://www.fon.hum.uva.nl/david/ma_ssp/doc/Klatt-1980-JAS000971.pdf)
- [Methods, Techniques, and Algorithms (Lemmetty, on Klatt's parallel branch)](http://research.spa.aalto.fi/publications/theses/lemmetty_mst/chap5.html)
- [Formant Filtering Example — Julius O. Smith, CCRMA](https://ccrma.stanford.edu/~jos/filters/Formant_Filtering_Example.html)
- [Bitcrusher — Wikipedia](https://en.wikipedia.org/wiki/Bitcrusher)
- [Weird FX: Bitcrushers + Sample Rate Reduction — Perfect Circuit](https://www.perfectcircuit.com/signal/weird-fx-bitcrushers)

package pl.livecoding.musicjam.synth;

import pl.livecoding.musicjam.audio.VoiceSource;

import java.util.function.Supplier;

import static pl.livecoding.musicjam.synth.NovasawDsp.Adsr;
import static pl.livecoding.musicjam.synth.NovasawDsp.LowpassFilter;
import static pl.livecoding.musicjam.synth.NovasawDsp.clamp;
import static pl.livecoding.musicjam.synth.NovasawDsp.deterministicPhaseJitter;
import static pl.livecoding.musicjam.synth.NovasawDsp.polyBlepSaw;
import static pl.livecoding.musicjam.synth.NovasawDsp.shapeDiode;
import static pl.livecoding.musicjam.synth.NovasawDsp.wrapTwoPi;
import static pl.livecoding.musicjam.synth.NovasawDsp.wrapUnitPhase;

/**
 * One sounding note of the novasaw engine: 7 unison sawtooths, a diode waveshaper and a resonant
 * lowpass, advanced one frame at a time. Two envelopes run side by side: one sets the level, the
 * other opens the filter by {@code filterEnvAmountHz}, so a note can keep sounding after its
 * brightness has gone, which is what makes a pluck rather than just a short note. All the state the old {@code render} loop kept in local
 * variables lives here instead, so the note can be played out over many blocks.
 *
 * <p>The parameters are read from the supplier on every frame rather than captured once. Hand it a
 * fixed value and the note sounds exactly as a rendered sample would; hand it a patch whose
 * parameters a panel is moving, and the filter opens under the finger while the note is held.
 */
public final class NovasawVoice implements VoiceSource {

    static final int UNISON_VOICES = 7;

    private static final double[] UNISON_OFFSETS = {-1.0, -0.58, -0.23, 0.0, 0.23, 0.58, 1.0};
    private static final float MAX_OUTPUT_GAIN = 0.22f;
    /** Loud enough that a sub at 1.0 stands up to the seven saws without swamping them. */
    private static final float SUB_GAIN = 2.2f;

    private final Supplier<SynthParams> params;
    private final int midiNote;
    private final int heldFrames;
    private final int sampleRate;
    private final double frequency;
    private final double[] phase = new double[UNISON_VOICES];
    private final double[] driftPhase = new double[UNISON_VOICES];
    private final LowpassFilter filter = new LowpassFilter();
    private final Adsr amplitude = new Adsr();
    private final Adsr filterEnvelope = new Adsr();
    private final float deClickStep;

    private double motionPhase;
    private double subPhase;
    private float deClick;
    private int frame;

    public NovasawVoice(int midiNote, int heldFrames, int sampleRate, Supplier<SynthParams> params) {
        this.params = params;
        this.midiNote = midiNote;
        this.heldFrames = heldFrames;
        this.sampleRate = sampleRate;
        this.frequency = 440.0 * Math.pow(2.0, (midiNote - 69) / 12.0);
        this.deClickStep = (float) (1.0 / (0.006 * sampleRate));
        for (int unison = 0; unison < UNISON_VOICES; unison++) {
            double evenPhase = (double) unison / UNISON_VOICES;
            phase[unison] = wrapUnitPhase(evenPhase + deterministicPhaseJitter(midiNote, unison, heldFrames) * 0.19);
            driftPhase[unison] = deterministicPhaseJitter(midiNote + 17, unison, heldFrames) * 2.0 * Math.PI;
        }
        this.motionPhase = deterministicPhaseJitter(midiNote, UNISON_VOICES, heldFrames) * 2.0 * Math.PI;
    }

    @Override
    public float next() {
        SynthParams current = params.get();
        float envelope = amplitude.next(frame, heldFrames, current.attackSeconds(), current.decaySeconds(),
                current.sustainLevel(), current.releaseSeconds(), sampleRate);
        float filterLevel = filterEnvelope.next(frame, heldFrames, current.filterAttackSeconds(),
                current.filterDecaySeconds(), current.filterSustainLevel(), current.filterReleaseSeconds(),
                sampleRate);
        deClick = Math.min(1.0f, deClick + deClickStep);

        double vibrato = Math.sin(motionPhase) * current.vibratoCents();
        motionPhase = wrapTwoPi(motionPhase + 2.0 * Math.PI * current.motionRateHz() / sampleRate);

        float mono = 0.0f;
        for (int unison = 0; unison < UNISON_VOICES; unison++) {
            double driftCents = Math.sin(driftPhase[unison]) * current.motion() * 0.55;
            double detuneRatio = Math.pow(
                    2.0, (UNISON_OFFSETS[unison] * current.detuneCents() + vibrato + driftCents) / 1200.0);
            float phaseIncrement = (float) (frequency * detuneRatio / sampleRate);
            mono += polyBlepSaw((float) phase[unison], phaseIncrement);

            phase[unison] += phaseIncrement;
            if (phase[unison] >= 1.0) {
                phase[unison] -= Math.floor(phase[unison]);
            }
            driftPhase[unison] =
                    wrapTwoPi(driftPhase[unison] + 2.0 * Math.PI * (0.035 + 0.011 * unison) / sampleRate);
        }

        if (current.subLevel() > 0) {
            // a sine an octave down, under the saws and through the same filter and drive: what
            // gives a bass line its fundamental, since seven detuned saws alone come out thin
            mono += (float) Math.sin(subPhase) * current.subLevel() * SUB_GAIN;
            subPhase = wrapTwoPi(subPhase + Math.PI * frequency / sampleRate);
        }

        float voiceSample = mono * envelope * deClick;
        float shaped = shapeDiode(voiceSample * current.unisonGain() * 0.55f, current.drive())
                * MAX_OUTPUT_GAIN * current.outputTrim();
        float dynamicCutoff = clamp(
                current.cutoffHz() + filterLevel * current.filterEnvAmountHz()
                        + (midiNote - 60) * current.keyTrackHzPerSemitone(),
                80.0f, 18000.0f);
        frame++;
        return filter.process(shaped, dynamicCutoff, current.resonance(), sampleRate)
                * current.resonanceCompensation();
    }

    @Override
    public boolean finished() {
        return frame >= heldFrames + releaseFrames(params.get(), sampleRate);
    }

    /** The tail a note rings on for after its held part, as the current release says. */
    static int releaseFrames(SynthParams params, int sampleRate) {
        return Math.max(1, Math.round(params.releaseSeconds() * sampleRate));
    }
}

package pl.livecoding.musicjam.synth;

import pl.livecoding.musicjam.audio.Sample;

import static pl.livecoding.musicjam.synth.NovasawDsp.LowpassFilter;
import static pl.livecoding.musicjam.synth.NovasawDsp.clamp;
import static pl.livecoding.musicjam.synth.NovasawDsp.deterministicPhaseJitter;
import static pl.livecoding.musicjam.synth.NovasawDsp.envelopeCoefficient;
import static pl.livecoding.musicjam.synth.NovasawDsp.polyBlepSaw;
import static pl.livecoding.musicjam.synth.NovasawDsp.shapeDiode;
import static pl.livecoding.musicjam.synth.NovasawDsp.shapeEnergy;
import static pl.livecoding.musicjam.synth.NovasawDsp.shapeTone;
import static pl.livecoding.musicjam.synth.NovasawDsp.smoothStep;
import static pl.livecoding.musicjam.synth.NovasawDsp.wrapTwoPi;
import static pl.livecoding.musicjam.synth.NovasawDsp.wrapUnitPhase;

/**
 * Shared engine behind every novasaw patch port (Sandbox/novasaw, Source/MainComponent.cpp): a
 * 7-voice unison PolyBLEP sawtooth with drift/vibrato, a diode waveshaper and a resonant lowpass
 * driven by the amplitude envelope and key tracking. The algorithm never changes between patches
 * — only the recipe constants and the preset's macro knobs do — so subclasses just pass those to
 * the constructor; {@link #render} itself lives here once.
 *
 * <p>novasaw mixes each unison voice to stereo with a pan spread and layers chorus/delay/reverb
 * sends; this port collapses everything to the mono, one-shot-sample model {@code AudioEngine}
 * already renders through, so the pan spread and the send effects are intentionally left out.
 */
public abstract class NovasawSynth implements PitchSynth {

    private static final int UNISON_VOICES = 7;
    private static final double[] UNISON_OFFSETS = {-1.0, -0.58, -0.23, 0.0, 0.23, 0.58, 1.0};
    private static final float MAX_OUTPUT_GAIN = 0.22f;

    private final float attackSeconds;
    private final float decaySeconds;
    private final float sustainLevel;
    private final float releaseSeconds;
    private final float motionRateHz;
    private final float keyTrackHzPerSemitone;
    private final float vibratoCents;
    private final float drive;
    private final float outputTrim;
    private final float unisonGain;
    private final float baseCutoffHz;
    private final float filterEnvAmount;
    private final float resonance;
    private final float resonanceCompensation;
    private final float detuneCents;
    private final float motion;

    /**
     * The first block of parameters is the patch's recipe (fixed per patch, ported from
     * novasaw's {@code SoundRecipe}); the last three are the chosen factory preset's macro knobs
     * (intensity, tone, motion — "space" is skipped, novasaw only spends it on stereo spread and
     * reverb, both dropped in this port).
     */
    protected NovasawSynth(
            float attackSeconds, float decaySeconds, float sustainLevel, float releaseSeconds,
            float detuneBaseCents, float detuneRangeCents,
            float cutoffBaseHz, float cutoffRangeHz, float filterEnvAmountHz, float keyTrackHzPerSemitone,
            float motionRateHz, float vibratoCentsBase,
            float driveBase, float driveRange, float outputTrim,
            float presetIntensity, float presetTone, float presetMotion
    ) {
        this.attackSeconds = attackSeconds;
        this.decaySeconds = decaySeconds;
        this.sustainLevel = sustainLevel;
        this.releaseSeconds = releaseSeconds;
        this.motionRateHz = motionRateHz;
        this.keyTrackHzPerSemitone = keyTrackHzPerSemitone;
        this.outputTrim = outputTrim;

        float intensity = shapeEnergy(presetIntensity);
        float tone = shapeTone(presetTone);
        float motion = smoothStep(presetMotion);
        this.motion = motion;

        this.detuneCents = detuneBaseCents + intensity * detuneRangeCents;
        this.vibratoCents = vibratoCentsBase * motion;
        this.drive = driveBase + intensity * driveRange;
        float detuneCorrelation = clamp(this.detuneCents / 30.0f, 0.0f, 1.0f);
        this.unisonGain = (0.82f + detuneCorrelation * 0.18f) / (float) Math.sqrt(UNISON_VOICES);
        this.baseCutoffHz = clamp(cutoffBaseHz + tone * cutoffRangeHz + intensity * 1200.0f, 80.0f, 18000.0f);
        this.filterEnvAmount = filterEnvAmountHz * (0.25f + intensity * 0.75f);
        this.resonance = clamp(0.12f + tone * 0.20f + intensity * 0.16f + motion * 0.08f, 0.0f, 0.82f);
        this.resonanceCompensation = 1.0f / (1.0f + this.resonance * 0.38f);
    }

    /**
     * frameCount is the held (attack/decay/sustain) portion of the note; the release tail extends
     * the returned sample past frameCount so the note rings out instead of cutting off abruptly.
     * Stateless beyond construction — every mutable value here is local to one call, so a single
     * instance is safe to reuse (and to call concurrently) across many notes.
     */
    @Override
    public final Sample render(int midiNote, int frameCount, int sampleRate) {
        double frequency = 440.0 * Math.pow(2.0, (midiNote - 69) / 12.0);
        int releaseFrames = Math.max(1, Math.round(releaseSeconds * sampleRate));
        int attackFrames = (int) (attackSeconds * sampleRate);
        int totalFrames = frameCount + releaseFrames;

        float attackCoeff = envelopeCoefficient(attackSeconds, sampleRate);
        float decayCoeff = envelopeCoefficient(decaySeconds, sampleRate);
        float releaseCoeff = envelopeCoefficient(releaseSeconds, sampleRate);
        float deClickStep = (float) (1.0 / (0.006 * sampleRate));

        double[] phase = new double[UNISON_VOICES];
        double[] driftPhase = new double[UNISON_VOICES];
        for (int unison = 0; unison < UNISON_VOICES; unison++) {
            double evenPhase = (double) unison / UNISON_VOICES;
            phase[unison] = wrapUnitPhase(evenPhase + deterministicPhaseJitter(midiNote, unison, frameCount) * 0.19);
            driftPhase[unison] = deterministicPhaseJitter(midiNote + 17, unison, frameCount) * 2.0 * Math.PI;
        }
        double motionPhase = deterministicPhaseJitter(midiNote, UNISON_VOICES, frameCount) * 2.0 * Math.PI;
        float envelope = 0.0f;
        float deClick = 0.0f;
        LowpassFilter filter = new LowpassFilter();

        float[] data = new float[totalFrames];
        for (int i = 0; i < totalFrames; i++) {
            if (i < frameCount) {
                if (envelope < 1.0f && i < attackFrames) {
                    envelope = Math.min(1.0f, envelope + (1.0f - envelope) * attackCoeff);
                } else if (envelope > sustainLevel) {
                    envelope = Math.max(sustainLevel, envelope + (sustainLevel - envelope) * decayCoeff);
                }
            } else {
                envelope += (0.0f - envelope) * releaseCoeff;
            }
            deClick = Math.min(1.0f, deClick + deClickStep);

            double vibrato = Math.sin(motionPhase) * vibratoCents;
            motionPhase = wrapTwoPi(motionPhase + 2.0 * Math.PI * motionRateHz / sampleRate);

            float mono = 0.0f;
            for (int unison = 0; unison < UNISON_VOICES; unison++) {
                double driftCents = Math.sin(driftPhase[unison]) * motion * 0.55;
                double detuneRatio = Math.pow(
                        2.0, (UNISON_OFFSETS[unison] * detuneCents + vibrato + driftCents) / 1200.0);
                float phaseIncrement = (float) (frequency * detuneRatio / sampleRate);
                mono += polyBlepSaw((float) phase[unison], phaseIncrement);

                phase[unison] += phaseIncrement;
                if (phase[unison] >= 1.0) {
                    phase[unison] -= Math.floor(phase[unison]);
                }
                driftPhase[unison] =
                        wrapTwoPi(driftPhase[unison] + 2.0 * Math.PI * (0.035 + 0.011 * unison) / sampleRate);
            }

            float voiceSample = mono * envelope * deClick;
            float shaped = shapeDiode(voiceSample * unisonGain * 0.55f, drive) * MAX_OUTPUT_GAIN * outputTrim;
            float dynamicCutoff = clamp(
                    baseCutoffHz + envelope * filterEnvAmount + (midiNote - 60) * keyTrackHzPerSemitone,
                    80.0f, 18000.0f);
            data[i] = filter.process(shaped, dynamicCutoff, resonance, sampleRate) * resonanceCompensation;
        }
        return Sample.mono(data);
    }
}

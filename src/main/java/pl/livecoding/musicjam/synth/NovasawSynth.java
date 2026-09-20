package pl.livecoding.musicjam.synth;

import pl.livecoding.musicjam.audio.Sample;

import static pl.livecoding.musicjam.synth.NovasawDsp.clamp;
import static pl.livecoding.musicjam.synth.NovasawDsp.shapeEnergy;
import static pl.livecoding.musicjam.synth.NovasawDsp.shapeTone;
import static pl.livecoding.musicjam.synth.NovasawDsp.smoothStep;

/**
 * Shared engine behind every novasaw patch port (Sandbox/novasaw, Source/MainComponent.cpp): a
 * 7-voice unison PolyBLEP sawtooth with drift/vibrato, a diode waveshaper and a resonant lowpass
 * driven by the amplitude envelope and key tracking. The algorithm never changes between patches
 * — only the recipe constants and the preset's macro knobs do — so subclasses just pass those to
 * the constructor; the sound itself is made by {@link NovasawVoice}, one frame at a time.
 *
 * <p>The constructor's job is to boil a recipe and its macros down to the {@link SynthParams} a
 * voice reads. Those parameters are also what a panel of knobs moves: see {@link #params()} and
 * {@link LiveNovasawSynth}.
 *
 * <p>novasaw mixes each unison voice to stereo with a pan spread and layers chorus/delay/reverb
 * sends; this port collapses everything to the mono, one-shot-sample model {@code AudioEngine}
 * already renders through, so the pan spread and the send effects are intentionally left out.
 */
public abstract class NovasawSynth implements PitchSynth {

    private final SynthParams params;

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
        this(attackSeconds, decaySeconds, sustainLevel, releaseSeconds, detuneBaseCents, detuneRangeCents,
                cutoffBaseHz, cutoffRangeHz, filterEnvAmountHz, keyTrackHzPerSemitone,
                motionRateHz, vibratoCentsBase, driveBase, driveRange, outputTrim,
                presetIntensity, presetTone, presetMotion, 0.0f);
    }

    /** As above, with a sine an octave below the saws at {@code subLevel} — what a bass patch wants. */
    protected NovasawSynth(
            float attackSeconds, float decaySeconds, float sustainLevel, float releaseSeconds,
            float detuneBaseCents, float detuneRangeCents,
            float cutoffBaseHz, float cutoffRangeHz, float filterEnvAmountHz, float keyTrackHzPerSemitone,
            float motionRateHz, float vibratoCentsBase,
            float driveBase, float driveRange, float outputTrim,
            float presetIntensity, float presetTone, float presetMotion, float subLevel
    ) {
        float intensity = shapeEnergy(presetIntensity);
        float tone = shapeTone(presetTone);
        float motion = smoothStep(presetMotion);
        this.params = SynthParams.of(
                attackSeconds, decaySeconds, sustainLevel, releaseSeconds,
                detuneBaseCents + intensity * detuneRangeCents, subLevel, vibratoCentsBase * motion,
                motionRateHz, motion,
                cutoffBaseHz + tone * cutoffRangeHz + intensity * 1200.0f,
                clamp(0.12f + tone * 0.20f + intensity * 0.16f + motion * 0.08f, 0.0f, 0.82f),
                filterEnvAmountHz * (0.25f + intensity * 0.75f), keyTrackHzPerSemitone,
                driveBase + intensity * driveRange, outputTrim);
    }

    /** What this patch's recipe and macros come out as: the starting point for a panel of knobs. */
    public final SynthParams params() {
        return params;
    }

    /**
     * frameCount is the held (attack/decay/sustain) portion of the note; the release tail extends
     * the returned sample past frameCount so the note rings out instead of cutting off abruptly.
     * Stateless beyond construction — the voice that does the work is local to one call, so a single
     * instance is safe to reuse (and to call concurrently) across many notes.
     */
    @Override
    public final Sample render(int midiNote, int frameCount, int sampleRate) {
        return render(midiNote, frameCount, sampleRate, params);
    }

    /** One note rendered in one go, as {@link NovasawVoice} plays it with these parameters. */
    static Sample render(int midiNote, int frameCount, int sampleRate, SynthParams params) {
        NovasawVoice voice = new NovasawVoice(midiNote, frameCount, sampleRate, () -> params);
        float[] data = new float[frameCount + NovasawVoice.releaseFrames(params, sampleRate)];
        for (int i = 0; i < data.length; i++) {
            data[i] = voice.next();
        }
        return Sample.mono(data);
    }
}

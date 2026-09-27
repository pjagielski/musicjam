package pl.livecoding.musicjam.synth;

import static pl.livecoding.musicjam.synth.NovasawDsp.clamp;

/**
 * Everything {@link NovasawVoice} needs to know to make a sound: the seven unison saws and their
 * movement, the sine an octave below them, the filter and its own envelope, the level's envelope
 * and the drive. What a patch's constructor used to bake into its fields is now a value: stored in
 * a patch, read frame by frame by a voice, or replaced while a note is sounding when a knob moves.
 *
 * <p>{@code unisonGain} and {@code resonanceCompensation} follow from the others, so build these
 * with {@link #of}, which works them out; the canonical constructor is for {@code with...} copies.
 */
public record SynthParams(
        float attackSeconds, float decaySeconds, float sustainLevel, float releaseSeconds,
        float detuneCents, float subLevel, float vibratoCents, float motionRateHz, float motion,
        float cutoffHz, float resonance, float filterEnvAmountHz, float keyTrackHzPerSemitone,
        float filterAttackSeconds, float filterDecaySeconds, float filterSustainLevel,
        float filterReleaseSeconds,
        float drive, float outputTrim, float unisonGain, float resonanceCompensation,
        Saturation shape, FilterKind filter) {

    /**
     * A patch whose filter follows the level's envelope, as every patch did before the filter had
     * one of its own: the filter's four stages are copies of the level's.
     */
    public static SynthParams of(
            float attackSeconds, float decaySeconds, float sustainLevel, float releaseSeconds,
            float detuneCents, float subLevel, float vibratoCents, float motionRateHz, float motion,
            float cutoffHz, float resonance, float filterEnvAmountHz, float keyTrackHzPerSemitone,
            float drive, float outputTrim) {
        return of(attackSeconds, decaySeconds, sustainLevel, releaseSeconds,
                detuneCents, subLevel, vibratoCents, motionRateHz, motion,
                cutoffHz, resonance, filterEnvAmountHz, keyTrackHzPerSemitone,
                attackSeconds, decaySeconds, sustainLevel, releaseSeconds,
                drive, outputTrim);
    }

    public static SynthParams of(
            float attackSeconds, float decaySeconds, float sustainLevel, float releaseSeconds,
            float detuneCents, float subLevel, float vibratoCents, float motionRateHz, float motion,
            float cutoffHz, float resonance, float filterEnvAmountHz, float keyTrackHzPerSemitone,
            float filterAttackSeconds, float filterDecaySeconds, float filterSustainLevel,
            float filterReleaseSeconds,
            float drive, float outputTrim) {
        float detuneCorrelation = clamp(detuneCents / 30.0f, 0.0f, 1.0f);
        float unisonGain = (0.82f + detuneCorrelation * 0.18f) / (float) Math.sqrt(NovasawVoice.UNISON_VOICES);
        return new SynthParams(attackSeconds, decaySeconds, sustainLevel, releaseSeconds,
                detuneCents, subLevel, vibratoCents, motionRateHz, motion,
                clamp(cutoffHz, 80.0f, 18000.0f), resonance, filterEnvAmountHz, keyTrackHzPerSemitone,
                filterAttackSeconds, filterDecaySeconds, filterSustainLevel, filterReleaseSeconds,
                drive, outputTrim, unisonGain, 1.0f / (1.0f + resonance * 0.38f),
                Saturation.DIODE, FilterKind.TWO_POLE);
    }

    /**
     * What {@code of} cannot work out from its arguments, put back on a patch it has just rebuilt.
     * Every {@code with*} goes through {@code of}, which makes a patch of the default shape and the
     * default filter; without this, turning the cutoff would quietly reset both. One place, so the
     * next thing added to a patch has one place to be added to.
     */
    private SynthParams keeping(SynthParams rebuilt) {
        return rebuilt.withShape(shape).withFilter(filter);
    }

    /** The same patch through the other filter: two poles or four. */
    public SynthParams withFilter(FilterKind next) {
        return new SynthParams(attackSeconds, decaySeconds, sustainLevel, releaseSeconds,
                detuneCents, subLevel, vibratoCents, motionRateHz, motion,
                cutoffHz, resonance, filterEnvAmountHz, keyTrackHzPerSemitone,
                filterAttackSeconds, filterDecaySeconds, filterSustainLevel, filterReleaseSeconds,
                drive, outputTrim, unisonGain, resonanceCompensation, shape, next);
    }

    /** The same patch driven into another shape: the drive says how hard, this says what kind. */
    public SynthParams withShape(Saturation next) {
        return new SynthParams(attackSeconds, decaySeconds, sustainLevel, releaseSeconds,
                detuneCents, subLevel, vibratoCents, motionRateHz, motion,
                cutoffHz, resonance, filterEnvAmountHz, keyTrackHzPerSemitone,
                filterAttackSeconds, filterDecaySeconds, filterSustainLevel, filterReleaseSeconds,
                drive, outputTrim, unisonGain, resonanceCompensation, next, filter);
    }

    public SynthParams withCutoff(float hz) {
        return keeping(of(attackSeconds, decaySeconds, sustainLevel, releaseSeconds, detuneCents, subLevel,
                vibratoCents, motionRateHz, motion, hz, resonance, filterEnvAmountHz,
                keyTrackHzPerSemitone, filterAttackSeconds, filterDecaySeconds, filterSustainLevel,
                filterReleaseSeconds, drive, outputTrim));
    }

    public SynthParams withDrive(float amount) {
        return keeping(of(attackSeconds, decaySeconds, sustainLevel, releaseSeconds, detuneCents, subLevel,
                vibratoCents, motionRateHz, motion, cutoffHz, resonance, filterEnvAmountHz,
                keyTrackHzPerSemitone, filterAttackSeconds, filterDecaySeconds, filterSustainLevel,
                filterReleaseSeconds, amount, outputTrim));
    }

    public SynthParams withResonance(float amount) {
        return keeping(of(attackSeconds, decaySeconds, sustainLevel, releaseSeconds, detuneCents, subLevel,
                vibratoCents, motionRateHz, motion, cutoffHz, amount, filterEnvAmountHz,
                keyTrackHzPerSemitone, filterAttackSeconds, filterDecaySeconds, filterSustainLevel,
                filterReleaseSeconds, drive, outputTrim));
    }

    public SynthParams withSub(float level) {
        return keeping(of(attackSeconds, decaySeconds, sustainLevel, releaseSeconds, detuneCents, level,
                vibratoCents, motionRateHz, motion, cutoffHz, resonance, filterEnvAmountHz,
                keyTrackHzPerSemitone, filterAttackSeconds, filterDecaySeconds, filterSustainLevel,
                filterReleaseSeconds, drive, outputTrim));
    }

    /** The same patch with the filter's envelope set apart from the level's. */
    public SynthParams withFilterEnvelope(float attack, float decay, float sustain, float release) {
        return keeping(of(attackSeconds, decaySeconds, sustainLevel, releaseSeconds, detuneCents, subLevel,
                vibratoCents, motionRateHz, motion, cutoffHz, resonance, filterEnvAmountHz,
                keyTrackHzPerSemitone, attack, decay, sustain, release, drive, outputTrim));
    }
}

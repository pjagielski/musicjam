package pl.livecoding.musicjam.synth;

/**
 * Shared DSP primitives ported from the novasaw synth (Sandbox/novasaw,
 * Source/MainComponent.cpp), used by every {@code *Synth} patch class so the
 * per-sample math lives in exactly one place.
 */
public final class NovasawDsp {

    private NovasawDsp() {
    }

    static float envelopeCoefficient(float seconds, int sampleRate) {
        float timeConstant = Math.max(0.001f, seconds) * 0.35f;
        return (float) (1.0 - Math.exp(-1.0 / (timeConstant * sampleRate)));
    }

    static float polyBlep(float phase, float phaseIncrement) {
        phaseIncrement = clamp(phaseIncrement, 0.0f, 0.5f);
        if (phaseIncrement <= 0.0f) {
            return 0.0f;
        }
        if (phase < phaseIncrement) {
            float t = phase / phaseIncrement;
            return t + t - t * t - 1.0f;
        }
        if (phase > 1.0f - phaseIncrement) {
            float t = (phase - 1.0f) / phaseIncrement;
            return t * t + t + t + 1.0f;
        }
        return 0.0f;
    }

    static float polyBlepSaw(float phase, float phaseIncrement) {
        return phase * 2.0f - 1.0f - polyBlep(phase, phaseIncrement);
    }

    public static float shapeDiode(float input, float drive) {
        float driven = input * (1.0f + drive * 7.0f);
        float positive = 1.0f - (float) Math.exp(-Math.max(0.0f, driven));
        float negative = -0.72f * (1.0f - (float) Math.exp(-Math.max(0.0f, -driven * 1.25f)));
        return (driven >= 0.0f ? positive : negative) * (0.9f + drive * 0.55f);
    }

    static float smoothStep(float value) {
        value = clamp(value, 0.0f, 1.0f);
        return value * value * (3.0f - 2.0f * value);
    }

    static float shapeEnergy(float value) {
        return (float) Math.pow(clamp(value, 0.0f, 1.0f), 1.18);
    }

    static float shapeTone(float value) {
        return (float) Math.pow(clamp(value, 0.0f, 1.0f), 1.08);
    }

    static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    static double wrapTwoPi(double phase) {
        double twoPi = 2.0 * Math.PI;
        return phase >= twoPi ? phase - twoPi : phase;
    }

    static double wrapUnitPhase(double phase) {
        phase -= Math.floor(phase);
        return phase < 0.0 ? phase + 1.0 : phase;
    }

    /**
     * novasaw seeds each unison voice's starting phase and drift/vibrato LFO phase from this
     * hash instead of zero (Sandbox/novasaw, MainComponent.cpp:130, MainComponent.cpp:3191-3201).
     * Without it every unison voice's drift LFO starts locked together — since their rates only
     * differ by a small amount, they stay correlated for seconds before audibly spreading out,
     * which reads as the pitch "coming apart" specifically late into a held note's tail.
     */
    static double deterministicPhaseJitter(int seedA, int seedB, int seedC) {
        int seed = seedA * 747796405
                + seedB * (int) 2891336453L
                + seedC * (int) 277803737L;
        seed ^= seed >>> 16;
        seed *= (int) 2246822519L;
        seed ^= seed >>> 13;
        return (seed & 0x00ffffff) / (double) 0x01000000;
    }

    /**
     * One ADSR, one frame at a time, in novasaw's own shape: exponential segments, the attack
     * ending on time or on reaching full level, whichever is first. The voice runs two, one for
     * the level and one for the filter, so both move alike when given the same four numbers.
     */
    static final class Adsr {
        private float level;

        float next(int frame, int heldFrames, float attackSeconds, float decaySeconds, float sustainLevel,
                   float releaseSeconds, int sampleRate) {
            if (frame < heldFrames) {
                if (level < 1.0f && frame < (int) (attackSeconds * sampleRate)) {
                    level = Math.min(1.0f, level + (1.0f - level) * envelopeCoefficient(attackSeconds, sampleRate));
                } else if (level > sustainLevel) {
                    level = Math.max(sustainLevel, level + (sustainLevel - level)
                            * envelopeCoefficient(decaySeconds, sampleRate));
                }
            } else {
                level += (0.0f - level) * envelopeCoefficient(releaseSeconds, sampleRate);
            }
            return level;
        }
    }

    static final class LowpassFilter {
        private float low;
        private float band;

        float process(float input, float cutoffHz, float resonance, int sampleRate) {
            float limitedCutoff = clamp(cutoffHz, 40.0f, sampleRate * 0.45f);
            float g = (float) Math.tan(Math.PI * limitedCutoff / sampleRate);
            float k = clamp(1.82f - resonance * 1.25f, 0.45f, 2.0f);
            float safeInput = clamp(input, -3.0f, 3.0f);
            float denominator = 1.0f / (1.0f + g * (g + k));
            float high = (safeInput - low - (k + g) * band) * denominator;
            float newBand = band + g * high;
            float newLow = low + g * newBand;
            band = clamp(newBand + g * high, -3.0f, 3.0f);
            low = clamp(newLow + g * newBand, -3.0f, 3.0f);
            return newLow;
        }
    }
}

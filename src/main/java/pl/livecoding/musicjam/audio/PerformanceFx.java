package pl.livecoding.musicjam.audio;

/**
 * The effects played over the whole mix, drums and synth together, as a sampler's Perform screen
 * plays them: each one off until a finger holds it, then set by where the finger is. The setters
 * are safe from any thread; {@link #process} runs on the render thread, block by block.
 *
 * <p>A position is 0 at the bottom of a strip and 1 at the top; anything below 0 turns the effect
 * off. Positions glide rather than jump, and an effect fades in and out over a few milliseconds,
 * so neither a drag nor letting go clicks.
 */
public final class PerformanceFx {
    private static final double GLIDE_SECONDS = 0.015;
    private static final double FADE_SECONDS = 0.005;
    private static final double OFF = -1;

    private final Crush crush;
    private final Filter filter;

    PerformanceFx(int sampleRate) {
        crush = new Crush(sampleRate);
        filter = new Filter(sampleRate);
    }

    /**
     * Crush: the mix at a lower sample rate and fewer bits, a little clipped. The top of the strip
     * crushes least (about 11 kHz and 10 bits), the bottom most (about 700 Hz and 4 bits).
     */
    public void crush(double position) {
        crush.target = position;
    }

    /**
     * Filter: a resonant low-pass below the centre, closing towards the bottom, and a high-pass
     * above it, opening towards the top. At the centre the mix passes as it is.
     */
    public void filter(double position) {
        filter.target = position;
    }

    /** Lets every effect go, as Stop does. */
    public void releaseAll() {
        crush(OFF);
        filter(OFF);
    }

    /** The first {@code frames} stereo frames of {@code mix}, in place. */
    void process(float[] mix, int frames) {
        crush.process(mix, frames);
        filter.process(mix, frames);
    }

    /** What every effect shares: a position that glides, and a level that fades the effect in and out. */
    private abstract static class Effect {
        final int sampleRate;
        private final double glide;
        private final double fade;
        volatile double target = OFF;
        double position = 0.5;
        private double wet;

        Effect(int sampleRate) {
            this.sampleRate = sampleRate;
            glide = 1 - Math.exp(-1 / (GLIDE_SECONDS * sampleRate));
            fade = 1.0 / (FADE_SECONDS * sampleRate);
        }

        final void process(float[] mix, int frames) {
            double wanted = target;
            boolean on = wanted >= 0;
            if (!on && wet == 0) {
                return;
            }
            if (on && wet == 0) {
                // coming in from off: start where the finger is, not glide there from the last place
                position = Math.min(1, wanted);
                reset();
            }
            for (int frame = 0; frame < frames; frame++) {
                if (on) {
                    position += (Math.min(1, wanted) - position) * glide;
                    wet = Math.min(1, wet + fade);
                } else {
                    wet = Math.max(0, wet - fade);
                }
                int left = frame * 2;
                float dryLeft = mix[left];
                float dryRight = mix[left + 1];
                apply(mix, left);
                if (wet == 1) {
                    continue;
                }
                mix[left] = (float) (dryLeft + (mix[left] - dryLeft) * wet);
                mix[left + 1] = (float) (dryRight + (mix[left + 1] - dryRight) * wet);
            }
        }

        /** Clears whatever the effect remembers, before it comes in again. */
        abstract void reset();

        /** Replaces the stereo frame at {@code mix[left]}, {@code mix[left + 1]} with the effect's output. */
        abstract void apply(float[] mix, int left);
    }

    private static final class Crush extends Effect {
        private double phase = 1;
        private float heldLeft;
        private float heldRight;

        Crush(int sampleRate) {
            super(sampleRate);
        }

        @Override
        void reset() {
            phase = 1;
        }

        @Override
        void apply(float[] mix, int left) {
            double rate = 700 * Math.pow(16, position);
            phase += rate / sampleRate;
            if (phase >= 1) {
                phase -= Math.floor(phase);
                double steps = Math.pow(2, 4 + 6 * position - 1);
                heldLeft = crushed(mix[left], steps);
                heldRight = crushed(mix[left + 1], steps);
            }
            mix[left] = heldLeft;
            mix[left + 1] = heldRight;
        }

        /** Rounded to {@code steps} levels a side, then into a soft, transistor-like clip. */
        private static float crushed(float value, double steps) {
            double rounded = Math.round(value * steps) / steps;
            return (float) Math.tanh(rounded * 1.4);
        }
    }

    /**
     * A state-variable filter in the trapezoidal form, which stays stable while its cutoff moves a
     * sample at a time — needed, since a finger sweeps it.
     */
    private static final class Filter extends Effect {
        private static final double RESONANCE = 0.75;
        private final double[] low = new double[2];
        private final double[] band = new double[2];

        Filter(int sampleRate) {
            super(sampleRate);
        }

        @Override
        void reset() {
            low[0] = low[1] = band[0] = band[1] = 0;
        }

        @Override
        void apply(float[] mix, int left) {
            boolean highPass = position > 0.5;
            double sweep = highPass ? (position - 0.5) * 2 : position * 2;
            double cutoff = highPass ? 20 * Math.pow(400, sweep) : 150 * Math.pow(120, sweep);
            double g = Math.tan(Math.PI * Math.min(cutoff, sampleRate * 0.45) / sampleRate);
            // no resonance at the centre, where the filter should be heard as nothing at all
            double k = 2 - 2 * RESONANCE * Math.min(1, Math.abs(position - 0.5) * 8);
            double a1 = 1 / (1 + g * (g + k));
            for (int channel = 0; channel < 2; channel++) {
                double input = mix[left + channel];
                double v1 = a1 * (band[channel] + g * (input - low[channel]));
                double v2 = low[channel] + g * v1;
                band[channel] = 2 * v1 - band[channel];
                low[channel] = 2 * v2 - low[channel];
                double high = input - k * v1 - v2;
                mix[left + channel] = (float) (highPass ? high : v2);
            }
        }
    }
}

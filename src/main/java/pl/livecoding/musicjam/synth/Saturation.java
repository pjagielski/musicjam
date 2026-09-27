package pl.livecoding.musicjam.synth;

/**
 * The shapes a signal can be driven into. Drive has always said how hard; this says what kind.
 *
 * <p>{@link #DIODE} is the one every patch here was levelled against and stays the default: an
 * asymmetric exponential diode, which is what a hand reaches for when it wants dirt. The rest are
 * the usual family - a soft clip, a hard one, a rational S, a cubic, a pair of diodes, its
 * asymmetric sibling, and the two folds, which stop being clipping altogether and start adding
 * partials that were never in the sound.
 *
 * <p>Every shape is levelled, so turning the drive up or changing the shape changes the sound and
 * not the loudness. The levels are measured rather than derived - see {@link #gain} - because
 * these curves clip back off much of what they add, by an amount no formula here would guess.
 *
 * <p>What separates them once they are levelled is how much they lift what is quiet. A shape that
 * only deals with the peaks leaves the rest where it was; one that bends the whole curve raises
 * everything to keep its level, which is what makes saturation sound like compression. Measured,
 * as the gain a signal far below the level they were levelled at comes out with, at drive 1 and
 * at drive 2:
 *
 * <pre>
 *   Fold      1.00  1.35     only the peaks are turned round; the rest is untouched
 *   Hard      1.17  1.78     the same, flattened rather than folded
 *   Sine fold 1.23  1.76
 *   Soft      1.38  1.95     the gentlest of the bending shapes
 *   Cubic     1.55  2.37
 *   S-curve   1.82  2.59
 *   Asym      1.94  2.60     and it leans, so the even harmonics come with it
 *   Tube      1.96  2.55
 *   Diode     2.43  4.13     ours: the most compressive of them by a long way
 * </pre>
 */
public enum Saturation {

    /** The exponential diode the patches are levelled against: asymmetric, and the default. */
    DIODE("Diode"),
    /** A plain soft clip, tanh: the mildest of them, and the one that never quite gets there. */
    SOFT("Soft"),
    /** A rational S: softer in the middle than tanh and harder at the edges. */
    SCURVE("S-curve"),
    /** Clipped flat. Everything above the line is the line. */
    HARD("Hard"),
    /** A cubic softened into a tanh: the polite one, with a little of the third harmonic. */
    CUBIC("Cubic"),
    /** Two soft diodes facing each other across a small bias, as a clipper's pair of them do. */
    TUBE("Tube"),
    /** The same with one leg held still, so it leans one way and the even harmonics come up. */
    ASYM("Asym"),
    /** Folded rather than clipped: past the top it comes back down, and keeps coming. */
    FOLD("Fold"),
    /** The fold run through a sine, so its corners are round and it sings rather than tears. */
    SINEFOLD("Sine fold");

    /** What the drive knob's 0..2 becomes for the shapes that take a gain: 2 is hard driving. */
    private static final float K = 3.5f;

    private final String label;

    Saturation(String label) {
        this.label = label;
    }

    /** The name the panel shows. */
    public String label() {
        return label;
    }

    /** {@code input} driven into this shape at {@code drive}, before its level is taken back off. */
    public float shape(float input, float drive) {
        if (this == DIODE) {
            return NovasawDsp.shapeDiode(input, drive);
        }
        float k = Math.max(0.0f, drive) * K;
        return switch (this) {
            case SOFT -> soft(input, k);
            case SCURVE -> (1 + k) * input / (1 + k * Math.abs(input));
            case HARD -> Math.max(-1.0f, Math.min(1.0f, (1 + k) * input));
            case CUBIC -> cubic(input, k);
            case TUBE -> diode(input, k, false);
            case ASYM -> diode(input, k, true);
            case FOLD -> fold(input, k);
            case SINEFOLD -> (float) Math.sin(Math.PI / 2 * fold(input, k));
            case DIODE -> throw new IllegalStateException("handled above");
        };
    }

    /** The same, with the level it adds taken back off, so only the shape is heard. */
    public float levelled(float input, float drive) {
        return shape(input, drive) / gain(drive);
    }

    private static float soft(float input, float k) {
        return (float) Math.tanh(input * (1 + k));
    }

    /** Maps [0, inf) onto [0, 1): how far along a shape's range a drive is, whatever the drive. */
    private static double squash(double value) {
        return value / (1 + value);
    }

    private static float cubic(float input, float k) {
        double third = squash(Math.log1p(k)) / 3;
        return soft((float) ((input - third * input * input * input) / (1 - third)), k);
    }

    /**
     * Two soft diodes across a bias, divided by the slope at zero so the pair is the identity for
     * small signals before the soft clip that follows it - which then gives them its own gain, so
     * the shape as a whole is not transparent, only evenly behaved around zero. With
     * {@code leaning}, one leg is held at the bias instead of following the signal, which is what
     * makes it asymmetric and brings up the even harmonics.
     */
    private static float diode(float input, float k, boolean leaning) {
        double gain = 1 + 2 * k;
        double bias = 0.07 * squash(Math.log1p(k));
        double positive = Math.tanh(gain * (input + bias));
        double negative = Math.tanh(gain * (leaning ? bias : bias - input));
        double sech = 1 / Math.cosh(gain * bias);
        // the slope at zero: one leg's worth when it leans, both when it does not
        double slope = Math.max(1e-8, (leaning ? 1 : 2) * gain * sech * sech);
        return soft((float) ((positive - negative) / slope), k);
    }

    /** Past the top it turns round and comes back: a triangle wrapped over the driven signal. */
    private static float fold(float input, float k) {
        double driven = (1 + 0.5 * k) * input;
        double window = (driven + 1) % 4;
        if (window < 0) {
            window += 4;
        }
        return (float) (1 - Math.abs(window - 2));
    }

    /**
     * What this shape does to the level at {@code drive}, so it can be taken back off. Measured
     * rather than worked out: a saw at the level a patch's voices reach is driven through the shape
     * at every fifth of the knob, and read between those points. {@link #DIODE} keeps the fitted
     * curve it always had, so no patch made before any of this sounds different for it.
     *
     * <p>The saw is at 0.335, which is the level at which measuring DIODE reproduces the fit it was
     * given years before these other shapes existed - so they are all levelled against the same
     * thing rather than each against itself.
     */
    float gain(float drive) {
        if (this == DIODE) {
            return NovasawDsp.driveGain(drive);
        }
        float[] measured = LEVELS[ordinal()];
        float along = Math.max(0.0f, Math.min(1.0f, drive / 2.0f)) * (measured.length - 1);
        int below = (int) along;
        int above = Math.min(measured.length - 1, below + 1);
        float between = along - below;
        return measured[below] * (1 - between) + measured[above] * between;
    }

    /** Measured by SaturationLevels in the tests, at drive 0, 0.2, 0.4 ... 2. */
    private static final float[][] LEVELS = {
            {}, // DIODE keeps its own fit
            {0.9783f, 1.5996f, 2.1400f, 2.5931f, 2.9643f, 3.2649f, 3.5075f, 3.7040f, 3.8642f, 3.9960f, 4.1055f},
            {1.0000f, 1.4492f, 1.7888f, 2.0572f, 2.2762f, 2.4592f, 2.6151f, 2.7500f, 2.8680f, 2.9725f, 3.0658f},
            {1.0000f, 1.7000f, 2.4000f, 3.0938f, 3.5686f, 3.8618f, 4.0627f, 4.2096f, 4.3217f, 4.4103f, 4.4821f},
            {0.9783f, 1.7692f, 2.4197f, 2.9307f, 3.3217f, 3.6186f, 3.8453f, 4.0205f, 4.1581f, 4.2681f, 4.3574f},
            {0.9583f, 1.4497f, 1.7694f, 1.9841f, 2.1482f, 2.2939f, 2.4386f, 2.5921f, 2.7592f, 2.9422f, 3.1407f},
            {0.9583f, 1.4504f, 1.7728f, 1.9925f, 2.1630f, 2.3144f, 2.4615f, 2.6100f, 2.7600f, 2.9086f, 3.0510f},
            {1.0000f, 1.3500f, 1.7000f, 2.0500f, 2.4000f, 2.7500f, 3.0877f, 3.2837f, 3.3636f, 3.3720f, 3.3348f},
            {1.5279f, 2.0160f, 2.4644f, 2.8649f, 3.2110f, 3.4981f, 3.7233f, 3.8859f, 3.9873f, 4.0314f, 4.0242f},
    };
}

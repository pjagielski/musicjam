package pl.livecoding.musicjam.studio.knobs;

import java.util.Locale;

/**
 * One synth parameter as a control sees it: a name, the range it spans and how a knob's 0..1
 * position maps onto that range. Frequencies and times are heard logarithmically, so those are
 * {@code exponential}; a {@code bipolar} parameter fills its arc from the middle out.
 */
record Param(String label, double min, double max, String unit, boolean exponential, boolean bipolar,
             int decimals, double initial) {

    static Param linear(String label, double min, double max, String unit, int decimals, double initial) {
        return new Param(label, min, max, unit, false, false, decimals, initial);
    }

    static Param bipolar(String label, double min, double max, String unit, int decimals, double initial) {
        return new Param(label, min, max, unit, false, true, decimals, initial);
    }

    static Param exponential(String label, double min, double max, String unit, int decimals, double initial) {
        return new Param(label, min, max, unit, true, false, decimals, initial);
    }

    /** 0..1 from the knob to the value the synth would use. */
    double valueOf(double position) {
        return exponential ? min * Math.pow(max / min, position) : min + position * (max - min);
    }

    /** The knob position a value sits at; the inverse of {@link #valueOf}. */
    double positionOf(double value) {
        return exponential
                ? Math.log(value / min) / Math.log(max / min)
                : (value - min) / (max - min);
    }

    /** Hz past a thousand reads better as kHz, and a unit hangs off the number unless it is a ratio. */
    String format(double value) {
        if ("Hz".equals(unit) && value >= 1000) {
            return String.format(Locale.ROOT, "%.2f kHz", value / 1000);
        }
        String number = String.format(Locale.ROOT, "%." + decimals + "f", value);
        return unit.isEmpty() ? number : number + " " + unit;
    }
}

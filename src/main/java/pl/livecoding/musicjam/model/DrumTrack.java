package pl.livecoding.musicjam.model;

import java.util.Objects;

/**
 * One drum's part of a one-bar pattern: {@code steps} is a compact string (see
 * {@link PatternCompiler} for the accent characters) whose length sets how finely it divides the
 * bar.
 */
public record DrumTrack(Drum drum, String steps, float gain) {

    public DrumTrack {
        Objects.requireNonNull(drum, "drum");
        Objects.requireNonNull(steps, "steps");
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("Pattern cannot be empty");
        }
        if (!Float.isFinite(gain) || gain < 0.0f || gain > 1.0f) {
            throw new IllegalArgumentException("Gain must be between 0 and 1");
        }
    }
}

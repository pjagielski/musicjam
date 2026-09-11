package pl.livecoding.musicjam.model;

/**
 * How a percussive hit is shaped over time: {@code decaySeconds} is how long it takes to die away
 * from its peak, {@code releaseSeconds} how long it fades out once its note has ended. Zero leaves
 * the sound as it is, for either.
 */
public record Envelope(double decaySeconds, double releaseSeconds) {

    public static final Envelope NONE = new Envelope(0.0, 0.0);

    public Envelope {
        if (!(decaySeconds >= 0.0) || !(releaseSeconds >= 0.0)) {
            throw new IllegalArgumentException("Envelope times must be zero or positive");
        }
    }

    public boolean shapes() {
        return decaySeconds > 0.0 || releaseSeconds > 0.0;
    }
}

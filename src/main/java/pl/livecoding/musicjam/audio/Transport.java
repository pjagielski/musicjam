package pl.livecoding.musicjam.audio;

public record Transport(double bpm, int sampleRate) {

    public Transport {
        if (!Double.isFinite(bpm) || bpm <= 0.0) {
            throw new IllegalArgumentException("BPM must be positive and finite");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("Sample rate must be positive");
        }
    }

    /**
     * The frame a beat starts on, counted from the start of playback - never from the beat before,
     * so rounding cannot add up.
     */
    public long frameAtBeat(double beat) {
        return Math.round(beat * 60.0 / bpm * sampleRate);
    }
}

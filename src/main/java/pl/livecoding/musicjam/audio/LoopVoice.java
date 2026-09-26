package pl.livecoding.musicjam.audio;

import java.util.function.DoubleSupplier;

/**
 * A loop of recorded audio played in time with the jam: the sample is read with a fractional index
 * at whatever rate makes its bars last as long as the jam's, so a break cut at 120 BPM fills the
 * same bars at 128. The rate is asked for every frame, which is what lets a tempo change while it
 * plays re-time the rest of it rather than only the next pass.
 *
 * <p>Faster means higher, as a sampler has always done: what it would take to move the tempo and
 * leave the pitch where it is has a step of its own (12.4).
 */
final class LoopVoice implements VoiceSource {

    private final Sample audio;
    private final DoubleSupplier rate;
    private double position;
    private boolean stopped;

    /** {@code rate} is frames of the sample read per frame played: 2 is twice as fast, an octave up. */
    LoopVoice(Sample audio, DoubleSupplier rate) {
        this.audio = audio;
        this.rate = rate;
    }

    @Override
    public float next() {
        float[] both = new float[2];
        next(both);
        return (both[0] + both[1]) / 2;
    }

    @Override
    public void next(float[] stereoOut) {
        int frame = (int) position;
        if (stopped || frame >= audio.frameCount() - 1) {
            stopped = true;
            stereoOut[0] = 0;
            stereoOut[1] = 0;
            return;
        }
        // between two frames of the sample: what is read at a rate that is rarely a whole number
        double blend = position - frame;
        stereoOut[0] = (float) (audio.leftAt(frame) * (1 - blend) + audio.leftAt(frame + 1) * blend);
        stereoOut[1] = (float) (audio.rightAt(frame) * (1 - blend) + audio.rightAt(frame + 1) * blend);
        position += Math.max(0, rate.getAsDouble());
    }

    @Override
    public boolean finished() {
        return stopped || position >= audio.frameCount() - 1;
    }

    /** Ends it now: what a loop being started again does to the pass still playing. */
    void stop() {
        stopped = true;
    }
}

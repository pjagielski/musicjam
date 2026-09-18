package pl.livecoding.musicjam.midi;

import javax.sound.midi.MidiUnavailableException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * A short note on every beat, in time with audio that plays alongside it - the drum of
 * {@code AudioEngine.playEveryBeat} - and a changeable {@code latencyNanos} early. Once the drum and
 * the note sound as one, that latency is the synth's {@code midiLatency}.
 */
public final class BeatClicks implements AutoCloseable {
    static final int PITCH = 72;
    static final int VELOCITY = 110;
    static final long NOTE_NANOS = TimeUnit.MILLISECONDS.toNanos(60);

    private final NoteOutput output;

    BeatClicks(NoteOutput output) {
        this.output = output;
    }

    public static BeatClicks openMidi(int channel, int program) throws MidiUnavailableException {
        return new BeatClicks(MidiNoteOutput.open(channel, program));
    }

    public static BeatClicks openDevice(String deviceNameContains, int channel, int program)
            throws MidiUnavailableException {
        return new BeatClicks(ExternalMidiOutput.open(deviceNameContains, channel, program));
    }

    /**
     * Plays until interrupted. Every beat is placed the way {@link MidiPlayer#playLoop} places a loop:
     * from how much of the audio has been heard, one beat being a loop - so the latency is read again
     * for every beat, and a change is heard on the next one.
     */
    public void play(double bpm, LongSupplier heardNanos, LongSupplier latencyNanos) throws InterruptedException {
        long beatNanos = MidiPlayer.beatToNanos(1.0, bpm);
        long beat = -1;
        while (true) {
            long nowNanos = System.nanoTime();
            long heard = heardNanos.getAsLong();
            long latency = latencyNanos.getAsLong();
            beat = nextBeat(heard, latency, beatNanos, beat);
            long startNanos = MidiPlayer.loopStartNanos(nowNanos, heard, (int) beat, beatNanos, latency);

            sleepUntil(startNanos);
            output.noteOn(PITCH, VELOCITY);
            try {
                sleepUntil(startNanos + NOTE_NANOS);
            } finally {
                output.noteOff(PITCH);
            }
        }
    }

    /**
     * The first beat still to be sent: beat n goes out {@code latencyNanos} before the audio reaches
     * it, so it must lie further than that ahead of what has been heard. Never a beat already sent,
     * even when the latency has just shrunk.
     */
    static long nextBeat(long heardNanos, long latencyNanos, long beatNanos, long lastBeat) {
        long ahead = Math.floorDiv(heardNanos + latencyNanos, beatNanos) + 1;
        return Math.max(ahead, lastBeat + 1);
    }

    private static void sleepUntil(long targetNanos) throws InterruptedException {
        long waitNanos = targetNanos - System.nanoTime();
        if (waitNanos > 0) {
            Thread.sleep(Duration.ofNanos(waitNanos));
        } else if (Thread.interrupted()) {
            throw new InterruptedException();
        }
    }

    @Override
    public void close() {
        output.close();
    }
}

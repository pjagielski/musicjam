package pl.livecoding.musicjam.midi;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;

/**
 * Sends notes to an external MIDI device (e.g. a virtual MIDI cable feeding a
 * standalone synth like Surge XT) instead of the built-in Java synthesizer.
 * Devices are matched by a case-insensitive substring of their name — run
 * {@code java ListMidiDevices.java} to see what's available once a virtual cable is set up.
 */
public final class ExternalMidiOutput implements NoteOutput {

    private final MidiDevice device;
    private final Receiver receiver;
    private final int channel;
    private final Thread shutdownHook;

    private ExternalMidiOutput(MidiDevice device, Receiver receiver, int channel) {
        this.device = device;
        this.receiver = receiver;
        this.channel = channel;
        this.shutdownHook = new Thread(this::allSoundOff);
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    public static ExternalMidiOutput open(String deviceNameContains, int channel, int program)
            throws MidiUnavailableException {
        MidiDevice device = findDevice(deviceNameContains);
        device.open();
        Receiver receiver;
        try {
            receiver = device.getReceiver();
            receiver.send(new ShortMessage(ShortMessage.PROGRAM_CHANGE, channel, program, 0), -1);
        } catch (MidiUnavailableException exception) {
            device.close();
            throw exception;
        } catch (InvalidMidiDataException exception) {
            device.close();
            throw new IllegalStateException(exception);
        }
        return new ExternalMidiOutput(device, receiver, channel);
    }

    private static MidiDevice findDevice(String nameContains) throws MidiUnavailableException {
        String needle = nameContains.toLowerCase();
        for (MidiDevice.Info info : MidiSystem.getMidiDeviceInfo()) {
            if (info.getName().toLowerCase().contains(needle)) {
                MidiDevice device = MidiSystem.getMidiDevice(info);
                if (device.getMaxReceivers() != 0) {
                    return device;
                }
            }
        }
        throw new MidiUnavailableException("No MIDI output device matching \"" + nameContains + "\"");
    }

    @Override
    public synchronized void noteOn(int pitch, int velocity) {
        send(ShortMessage.NOTE_ON, pitch, velocity);
    }

    @Override
    public synchronized void noteOff(int pitch) {
        send(ShortMessage.NOTE_OFF, pitch, 0);
    }

    /** A control change on this output's channel - e.g. CC 74, which most synths map to filter cutoff. */
    public synchronized void controlChange(int controller, int value) {
        send(ShortMessage.CONTROL_CHANGE, controller, value);
    }

    private void send(int command, int data1, int data2) {
        try {
            receiver.send(new ShortMessage(command, channel, data1, data2), -1);
        } catch (InvalidMidiDataException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * CC 120 (All Sound Off) cuts every currently sounding note immediately, ignoring release
     * tails and sustain pedal — unlike CC 123 (All Notes Off), which just releases notes and can
     * still leave a synth ringing. Run on a JVM shutdown hook (see the constructor) so a killed
     * process (Ctrl+C, a crash) doesn't leave the external synth playing a stuck note forever.
     */
    private synchronized void allSoundOff() {
        try {
            send(ShortMessage.CONTROL_CHANGE, 120, 0);
        } catch (RuntimeException exception) {
            // Best-effort: the device may already be gone (closed, unplugged) by the time this runs.
        }
    }

    @Override
    public synchronized void close() {
        allSoundOff();
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException exception) {
            // The JVM is already shutting down (this close() call came from the hook itself, or a
            // sibling one) — nothing to remove.
        }
        receiver.close();
        device.close();
    }
}

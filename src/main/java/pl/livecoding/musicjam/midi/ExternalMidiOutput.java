package pl.livecoding.musicjam.midi;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends notes to a MIDI device outside the JVM - a virtual cable feeding a synth such as Surge XT -
 * instead of to Gervill. The device is found by part of its name; {@code listMidiDevices} shows
 * the names Java can see.
 */
public final class ExternalMidiOutput implements NoteOutput {
    static final int ALL_SOUND_OFF = 120;
    static final int ALL_NOTES_OFF = 123;

    private final MidiDevice device;
    private final NoteGate receiver;
    private final int channel;
    private final Thread shutdownHook = new Thread(this::silence);

    private ExternalMidiOutput(MidiDevice device, NoteGate receiver, int channel) {
        this.device = device;
        this.receiver = receiver;
        this.channel = channel;
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    public static ExternalMidiOutput open(String nameContains, int channel, int program)
            throws MidiUnavailableException {
        return open(find(nameContains, devices()), channel, program);
    }

    /**
     * Silences every channel of a device, for a synth left with a note hanging by a run that was
     * killed rather than stopped: IntelliJ's Stop ends the JVM without running its shutdown hooks.
     */
    public static void panic(String nameContains) throws MidiUnavailableException {
        MidiDevice device = find(nameContains, devices());
        device.open();
        try (Receiver receiver = device.getReceiver()) {
            for (int channel = 0; channel < 16; channel++) {
                sendQuietly(receiver, ShortMessage.CONTROL_CHANGE, channel, ALL_SOUND_OFF);
                panic(receiver, channel);
            }
        } finally {
            device.close();
        }
    }

    private static List<MidiDevice> devices() throws MidiUnavailableException {
        List<MidiDevice> devices = new ArrayList<>();
        for (MidiDevice.Info info : MidiSystem.getMidiDeviceInfo()) {
            devices.add(MidiSystem.getMidiDevice(info));
        }
        return devices;
    }

    static ExternalMidiOutput open(MidiDevice device, int channel, int program)
            throws MidiUnavailableException {
        device.open();
        try {
            Receiver receiver = device.getReceiver();
            // a note left hanging by a run that was killed ends here, before anything new is played
            panic(receiver, channel);
            receiver.send(new ShortMessage(ShortMessage.PROGRAM_CHANGE, channel, program, 0), -1);
            return new ExternalMidiOutput(device, new NoteGate(receiver), channel);
        } catch (MidiUnavailableException exception) {
            device.close();
            throw exception;
        } catch (InvalidMidiDataException exception) {
            device.close();
            throw new IllegalArgumentException(exception);
        }
    }

    /**
     * The first device whose name contains {@code nameContains}, ignoring case, that takes messages.
     * A virtual cable usually shows up twice under one name - the end you send into and the end
     * another program reads from - and only the first of them has receivers.
     */
    static MidiDevice find(String nameContains, List<MidiDevice> devices) throws MidiUnavailableException {
        // TODO(step-4): compare each device's getDeviceInfo().getName() with nameContains, ignoring
        // TODO(step-4): case, and skip a device whose getMaxReceivers() is 0 - it only sends
        // TODO(step-4): (-1 means as many receivers as you like). When nothing fits, throw a
        // TODO(step-4): MidiUnavailableException that says what was looked for.
        throw new UnsupportedOperationException("ExternalMidiOutput.find");
    }

    @Override
    public synchronized void noteOn(int pitch, int velocity) {
        // TODO(step-4): a NOTE_ON on this output's channel, through send
        throw new UnsupportedOperationException("ExternalMidiOutput.noteOn");
    }

    @Override
    public synchronized void noteOff(int pitch) {
        // TODO(step-4): a NOTE_OFF on this output's channel, through send
        throw new UnsupportedOperationException("ExternalMidiOutput.noteOff");
    }

    private void send(int command, int data1, int data2) {
        // TODO(step-4): receiver.send takes a message and a timestamp, and -1 means "now". Building
        // TODO(step-4): a ShortMessage throws a checked InvalidMidiDataException for data out of
        // TODO(step-4): range, which here can only be a bug - rethrow it unchecked.
        throw new UnsupportedOperationException("ExternalMidiOutput.send");
    }

    /**
     * Silences the synth at once. Control change 120, All Sound Off, cuts notes in the middle of
     * their release; 123, All Notes Off, only releases them, and a pad with a long tail keeps
     * ringing. Runs on a shutdown hook too, so Ctrl+C does not leave a note hanging on a synth that
     * outlives this program.
     */
    private synchronized void allSoundOff() {
        // TODO(step-4): a CONTROL_CHANGE with controller ALL_SOUND_OFF and value 0. Best effort only:
        // TODO(step-4): when the shutdown hook runs, the device may already be gone, so no exception
        // TODO(step-4): gets out of here.
    }

    /**
     * Everything that stops the synth, for close() and the shutdown hook alike. Note-ons are shut out
     * first: the JVM runs its shutdown hooks while the scheduler's threads still run, and a note-on a
     * moment after All Sound Off would start a note that nothing ends. Then All Sound Off, and a
     * note-off for every pitch, for a synth that ignores All Sound Off.
     */
    private synchronized void silence() {
        receiver.shutNoteOns();
        allSoundOff();
        panic(receiver, channel);
    }

    /** All Notes Off and a note-off for every pitch on one channel - best effort, like allSoundOff. */
    private static void panic(Receiver receiver, int channel) {
        sendQuietly(receiver, ShortMessage.CONTROL_CHANGE, channel, ALL_NOTES_OFF);
        for (int pitch = 0; pitch < 128; pitch++) {
            sendQuietly(receiver, ShortMessage.NOTE_OFF, channel, pitch);
        }
    }

    private static void sendQuietly(Receiver receiver, int command, int channel, int data1) {
        try {
            receiver.send(new ShortMessage(command, channel, data1, 0), -1);
        } catch (InvalidMidiDataException | RuntimeException deviceAlreadyGone) {
            // a device that is closed or unplugged has nothing left to silence
        }
    }

    @Override
    public synchronized void close() {
        silence();
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException alreadyShuttingDown) {
            // close() was called while the JVM shuts down - there is no hook left to remove
        }
        receiver.close();
        device.close();
    }

    /**
     * The device's receiver, with a door for note-ons that closing shuts: after that only what ends a
     * note gets through.
     */
    static final class NoteGate implements Receiver {
        private final Receiver receiver;
        private volatile boolean noteOnsShut;

        NoteGate(Receiver receiver) {
            this.receiver = receiver;
        }

        void shutNoteOns() {
            noteOnsShut = true;
        }

        @Override
        public void send(MidiMessage message, long timeStamp) {
            if (noteOnsShut && message instanceof ShortMessage note && note.getCommand() == ShortMessage.NOTE_ON) {
                return;
            }
            receiver.send(message, timeStamp);
        }

        @Override
        public void close() {
            receiver.close();
        }
    }
}

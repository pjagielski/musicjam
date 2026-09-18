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
import java.util.Locale;

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
        String wanted = nameContains.toLowerCase(Locale.ROOT);
        for (MidiDevice device : devices) {
            String name = device.getDeviceInfo().getName().toLowerCase(Locale.ROOT);
            if (name.contains(wanted) && device.getMaxReceivers() != 0) {
                return device;
            }
        }
        throw new MidiUnavailableException("No MIDI device named like \"" + nameContains + "\" takes messages");
    }

    @Override
    public synchronized void noteOn(int pitch, int velocity) {
        send(ShortMessage.NOTE_ON, pitch, velocity);
    }

    @Override
    public synchronized void noteOff(int pitch) {
        send(ShortMessage.NOTE_OFF, pitch, 0);
    }

    private void send(int command, int data1, int data2) {
        try {
            receiver.send(new ShortMessage(command, channel, data1, data2), -1);
        } catch (InvalidMidiDataException exception) {
            throw new IllegalArgumentException(exception);
        }
    }

    /**
     * Silences the synth at once. Control change 120, All Sound Off, cuts notes in the middle of
     * their release; 123, All Notes Off, only releases them, and a pad with a long tail keeps
     * ringing. Runs on a shutdown hook too, so Ctrl+C does not leave a note hanging on a synth that
     * outlives this program.
     */
    private synchronized void allSoundOff() {
        try {
            send(ShortMessage.CONTROL_CHANGE, ALL_SOUND_OFF, 0);
        } catch (RuntimeException deviceAlreadyGone) {
            // best effort: by the time a shutdown hook runs, the device may be closed or unplugged
        }
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

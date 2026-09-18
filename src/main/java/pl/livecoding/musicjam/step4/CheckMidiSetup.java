package pl.livecoding.musicjam.step4;

import pl.livecoding.musicjam.Config;
import pl.livecoding.musicjam.midi.MidiFileReader;
import pl.livecoding.musicjam.midi.TrackData;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A short audible check of the device and channel, independent of the step 4 exercise. */
class CheckMidiSetup {
    private static final int TEST_PITCH = 60;
    private static final int TEST_VELOCITY = 100;
    private static final long TEST_MILLIS = 600;

    static void main(String[] args) throws Exception {
        Config config = Config.fromArgs(args);
        String wanted = config.midiDevice().orElseThrow(() -> new IllegalArgumentException(
                "Set midiDevice= in the config or pass --midiDevice <name>; listMidiDevices shows the names"));
        TrackData track = MidiFileReader.read(config.file()).track(config.track().orElseThrow(() ->
                new IllegalArgumentException("Set track= in the config or pass --track <n>")));
        int channel = config.channelFor(track);
        int program = track.program().orElse(0);
        MidiDevice device = find(wanted, devices());

        System.out.printf("MIDI output: %s (%s)%n", device.getDeviceInfo().getName(),
                device.getDeviceInfo().getDescription());
        System.out.printf("Track %d: %s; channel %d (Java: %d); program %d%n",
                track.index(), track.name(), channel + 1, channel, program);
        System.out.println("Sending C4 for 600 ms...");
        device.open();
        try (Receiver receiver = device.getReceiver()) {
            probe(receiver, channel, program, TEST_MILLIS);
        } finally {
            device.close();
        }
        System.out.println("Done. If C4 was silent, check the synth's MIDI input, audio output and channel.");
    }

    static MidiDevice find(String nameContains, List<MidiDevice> devices) throws MidiUnavailableException {
        String wanted = nameContains.toLowerCase(Locale.ROOT);
        for (MidiDevice device : devices) {
            if (device.getDeviceInfo().getName().toLowerCase(Locale.ROOT).contains(wanted)
                    && device.getMaxReceivers() != 0) {
                return device;
            }
        }
        throw new MidiUnavailableException(
                "No MIDI output containing \"" + nameContains + "\"; run listMidiDevices");
    }

    static void probe(Receiver receiver, int channel, int program, long durationMillis)
            throws InvalidMidiDataException, InterruptedException {
        receiver.send(new ShortMessage(ShortMessage.PROGRAM_CHANGE, channel, program, 0), -1);
        try {
            receiver.send(new ShortMessage(ShortMessage.NOTE_ON, channel, TEST_PITCH, TEST_VELOCITY), -1);
            Thread.sleep(durationMillis);
        } finally {
            receiver.send(new ShortMessage(ShortMessage.NOTE_OFF, channel, TEST_PITCH, 0), -1);
            receiver.send(new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, 120, 0), -1);
        }
    }

    private static List<MidiDevice> devices() throws MidiUnavailableException {
        List<MidiDevice> devices = new ArrayList<>();
        for (MidiDevice.Info info : MidiSystem.getMidiDeviceInfo()) {
            devices.add(MidiSystem.getMidiDevice(info));
        }
        return devices;
    }
}

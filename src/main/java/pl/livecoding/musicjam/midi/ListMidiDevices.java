package pl.livecoding.musicjam.midi;

import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiSystem;

public class ListMidiDevices {
    public static void main(String[] args) throws Exception {
        for (MidiDevice.Info info : MidiSystem.getMidiDeviceInfo()) {
            MidiDevice device = MidiSystem.getMidiDevice(info);
            System.out.printf("%-40s %-30s maxReceivers=%d%n",
                    info.getName(), info.getDescription(), device.getMaxReceivers());
        }
    }
}

package pl.livecoding.musicjam.step3;

import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiSystem;

class ListMidiDevices {

    static void main(String[] args) throws Exception {
        System.out.printf("%-32s %-32s %s%n", "name", "description", "takes messages");
        for (MidiDevice.Info info : MidiSystem.getMidiDeviceInfo()) {
            boolean receives = MidiSystem.getMidiDevice(info).getMaxReceivers() != 0;
            System.out.printf("%-32s %-32s %s%n", info.getName(), info.getDescription(), receives ? "yes" : "no");
        }
    }
}

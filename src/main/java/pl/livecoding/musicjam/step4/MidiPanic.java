package pl.livecoding.musicjam.step4;

import pl.livecoding.musicjam.Config;
import pl.livecoding.musicjam.midi.ExternalMidiOutput;

/**
 * Silences the config's MIDI device - All Sound Off, All Notes Off and a note-off for every pitch, on
 * every channel - when a run stopped with IntelliJ's Stop left a note hanging: Stop kills the JVM
 * without running the shutdown hook that would have done this.
 */
class MidiPanic {

    static void main(String[] args) throws Exception {
        Config config = Config.fromArgs(args);
        String device = config.midiDevice().orElseThrow(() -> new IllegalArgumentException(
                "No MIDI device to silence: set midiDevice= in the properties file or pass --midiDevice <name>"));
        ExternalMidiOutput.panic(device);
        System.out.printf("\"%s\": every channel silenced%n", device);
    }
}

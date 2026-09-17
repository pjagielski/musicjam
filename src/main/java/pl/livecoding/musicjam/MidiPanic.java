package pl.livecoding.musicjam;

import pl.livecoding.musicjam.midi.ExternalMidiOutput;

import java.nio.file.Path;

/**
 * Silences a MIDI device - All Sound Off, All Notes Off and a note-off for every pitch, on every
 * channel - when a run stopped with IntelliJ's Stop left a note hanging. With no arguments it takes
 * the device from src/main/resources/jam.properties; otherwise pass part of the device's name.
 */
public final class MidiPanic {

    private MidiPanic() {
    }

    public static void main(String[] args) throws Exception {
        String device = args.length > 0
                ? args[0]
                : PhraseRequest.fromPropertiesFile(Path.of("src/main/resources/jam.properties")).midiDevice();
        if (device == null) {
            throw new IllegalArgumentException(
                    "No MIDI device to silence: pass part of its name, or set midiDevice= in jam.properties");
        }
        ExternalMidiOutput.panic(device);
        System.out.printf("\"%s\": every channel silenced%n", device);
    }
}

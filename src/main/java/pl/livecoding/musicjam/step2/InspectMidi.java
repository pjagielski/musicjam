package pl.livecoding.musicjam.step2;

import pl.livecoding.musicjam.Config;
import pl.livecoding.musicjam.midi.MidiFileInspector;
import pl.livecoding.musicjam.midi.MidiFileReader;

class InspectMidi {

    static void main(String[] args) throws Exception {
        Config config = Config.fromArgs(args);
        MidiFileInspector inspector = new MidiFileInspector(MidiFileReader.read(config.file()));

        inspector.printOverview();
        if (config.track().isPresent()) {
            System.out.println();
            inspector.printTrack(config.track().getAsInt());
        }
    }
}

package pl.livecoding.musicjam.step1;

import pl.livecoding.musicjam.Config;

import javax.sound.midi.MidiSystem;

public class SequencerDemo {

    public static void main(String[] args) throws Exception {
        var file = Config.fromArgs(args).file();

        var sequence = MidiSystem.getSequence(file.toFile());
        try (var sequencer = MidiSystem.getSequencer()) {
            sequencer.open();
            sequencer.setSequence(sequence);
            sequencer.start();
            Thread.sleep(sequencer.getMicrosecondLength() / 1000);
            sequencer.stop();
        }
    }
}

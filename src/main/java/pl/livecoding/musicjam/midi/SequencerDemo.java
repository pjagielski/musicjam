package pl.livecoding.musicjam.midi;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import java.nio.file.Path;

class SequencerDemo {
    static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java SequencerDemo.java <file.mid>");
            return;
        }
        Sequence sequence = MidiSystem.getSequence(Path.of(args[0]).toFile());
        Sequencer sequencer = MidiSystem.getSequencer();
        sequencer.open();
        sequencer.setSequence(sequence);
        sequencer.start();
        Thread.sleep(sequencer.getMicrosecondLength() / 1000);
        sequencer.stop();
        sequencer.close();
    }
}

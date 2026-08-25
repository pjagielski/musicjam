package pl.livecoding.musicjam.midi;

import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Synthesizer;
final class MidiNoteOutput implements NoteOutput {


    private final Synthesizer synthesizer;
    private final MidiChannel channel;

    private MidiNoteOutput(Synthesizer synthesizer, MidiChannel channel) {
        this.synthesizer = synthesizer;
        this.channel = channel;
    }

    static MidiNoteOutput open(int channelNumber, int program) throws MidiUnavailableException {
        Synthesizer synthesizer = MidiSystem.getSynthesizer();
        synthesizer.open();
        MidiChannel[] channels = synthesizer.getChannels();
        if (channelNumber < 0 || channelNumber >= channels.length || channels[channelNumber] == null) {
            synthesizer.close();
            throw new MidiUnavailableException("Synthesizer has no channel " + channelNumber);
        }
        MidiChannel channel = channels[channelNumber];
        channel.programChange(program);
        warmUp(channel);
        return new MidiNoteOutput(synthesizer, channel);
    }

    private static void warmUp(MidiChannel channel) {
        channel.noteOn(0, 1);
        channel.noteOff(0);
        try {
            Thread.sleep(30);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public synchronized void noteOn(int pitch, int velocity) {
        channel.noteOn(pitch, velocity);
    }

    @Override
    public synchronized void noteOff(int pitch) {
        channel.noteOff(pitch);
    }

    @Override
    public synchronized void close() {
        channel.allNotesOff();
        synthesizer.close();
    }
}

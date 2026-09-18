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

    /** Java's own synthesizer - Gervill, the one Sequencer played through in step 1. */
    static MidiNoteOutput open(int channelNumber, int program) throws MidiUnavailableException {
        MidiNoteOutput output = open(MidiSystem.getSynthesizer(), channelNumber, program);
        output.warmUp();
        return output;
    }

    /**
     * The synthesizer, open, with the track's instrument selected on the track's channel - the
     * channel and the program step 2 read from the file.
     */
    static MidiNoteOutput open(Synthesizer synthesizer, int channelNumber, int program)
            throws MidiUnavailableException {
        synthesizer.open();
        MidiChannel channel = channelOf(synthesizer, channelNumber);
        channel.programChange(program);
        return new MidiNoteOutput(synthesizer, channel);
    }

    /**
     * The synthesizer's channel number {@code channelNumber}. When the synthesizer has no such
     * channel - out of range, or null - it is closed again before the exception leaves, so a failed
     * open does not keep hold of the sound card.
     */
    static MidiChannel channelOf(Synthesizer synthesizer, int channelNumber) throws MidiUnavailableException {
        MidiChannel[] channels = synthesizer.getChannels();
        if (channelNumber < 0 || channelNumber >= channels.length || channels[channelNumber] == null) {
            synthesizer.close();
            throw new MidiUnavailableException("Synthesizer has no channel " + channelNumber);
        }
        return channels[channelNumber];
    }

    /**
     * Gervill loads an instrument the first time one of its notes plays, and that first note comes
     * out late. A silent note right after opening gets the loading out of the way.
     */
    private void warmUp() {
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

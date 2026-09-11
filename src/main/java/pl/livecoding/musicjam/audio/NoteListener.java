package pl.livecoding.musicjam.audio;

/**
 * Where {@link AudioEngine#playLive} sends melody notes that should sound on something other than
 * its built-in synths - typically an external MIDI device.
 */
public interface NoteListener {

    void noteOn(int midiNote, int velocity);

    void noteOff(int midiNote);
}

package pl.livecoding.musicjam.audio;

/**
 * A voice that makes its samples up as it goes, instead of playing a {@link Sample} rendered in
 * advance. The engine asks for one frame at a time, in order, from the frame the note starts on,
 * which is what lets a knob turned now be heard in a note that is already sounding.
 */
public interface VoiceSource {

    /** The next frame of this voice. Called exactly once per frame, in order. */
    float next();

    /** True once the note has finished ringing and the engine can drop the voice. */
    boolean finished();
}

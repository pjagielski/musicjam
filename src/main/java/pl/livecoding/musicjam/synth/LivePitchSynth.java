package pl.livecoding.musicjam.synth;

import pl.livecoding.musicjam.audio.AudioEffect;
import pl.livecoding.musicjam.audio.VoiceSource;

/**
 * A synth whose notes the engine can play as they are made, rather than rendering each note to a
 * {@link pl.livecoding.musicjam.audio.Sample} first. That is the difference between a knob heard
 * from the next loop and a knob heard right now, in the note already sounding.
 */
public interface LivePitchSynth extends PitchSynth {

    /** A voice for one note: {@code heldFrames} long, ringing on for its release after that. */
    VoiceSource voice(int midiNote, int heldFrames, int sampleRate);

    /**
     * What this synth's voices are heard through — a delay, a reverb — or null for none. The engine
     * asks once per session and keeps it, so the tail survives between notes and between loops.
     */
    default AudioEffect effects(int sampleRate) {
        return null;
    }
}

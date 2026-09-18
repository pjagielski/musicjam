package pl.livecoding.musicjam.synth;

import pl.livecoding.musicjam.audio.Sample;

/**
 * Synthesizes one pitched tone (not a sequence — see {@code Voice.Pitch}, the note-model
 * counterpart this renders). Implemented by {@link NovasawSynth} and its patches.
 */
@FunctionalInterface
public interface PitchSynth {

    Sample render(int midiNote, int frameCount, int sampleRate);
}

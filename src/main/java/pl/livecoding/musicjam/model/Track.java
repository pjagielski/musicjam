package pl.livecoding.musicjam.model;

/**
 * One layer of a {@link Song}: either a hand-authored drum pattern ({@link DrumTrack}) or a
 * pre-loaded phrase of notes ({@link MelodyTrack}, typically read from a MIDI file). Both
 * contribute {@link Note}s when the song is compiled — see {@link PatternCompiler}.
 */
public sealed interface Track permits DrumTrack, MelodyTrack {

    float gain();
}

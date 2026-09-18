package pl.livecoding.musicjam.midi;

import pl.livecoding.musicjam.model.Note;

import java.util.List;
import java.util.OptionalInt;

public record TrackData(int index, String name, OptionalInt channel, OptionalInt program, List<Note> notes) {

    public List<Note> window(double fromBeat, double lengthBeats) {
        return notes.stream()
                .filter(note -> note.beat() >= fromBeat && note.beat() < fromBeat + lengthBeats)
                .map(note -> new Note(note.beat() - fromBeat, note.voice(), note.durationBeats(), note.velocity()))
                .toList();
    }
}

package pl.livecoding.musicjam.midi;

import pl.livecoding.musicjam.model.Note;

import java.util.List;
import java.util.OptionalInt;

public record TrackData(int index, String name, OptionalInt channel, OptionalInt program, List<Note> notes) {
}

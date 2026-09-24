package pl.livecoding.musicjam.studio;

import pl.livecoding.musicjam.model.MelodyTrack;
import pl.livecoding.musicjam.model.Note;
import pl.livecoding.musicjam.model.Song;
import pl.livecoding.musicjam.model.Track;

import java.util.ArrayList;
import java.util.List;

/**
 * The studio's tracks in the order the panel shows them, and which of them is selected — the one
 * the editor below the list is showing. The drum track is always there: it can be moved, muted
 * and renamed, but not removed.
 */
final class TrackList {

    /** The notes of a MIDI window over a loop of {@code lengthBeats}. */
    @FunctionalInterface
    interface Windows {
        List<Note> notes(MidiWindow window, double lengthBeats);
    }

    private final List<StudioTrack> tracks = new ArrayList<>();
    private int selected;

    private TrackList() {
    }

    /** What a jam config makes: the grid, then the one melody it names — with the grid selected. */
    static TrackList startingWith(String melodyName, MidiWindow melody, Instrument instrument) {
        TrackList list = new TrackList();
        list.tracks.add(new StudioTrack.Drums("Drums", 1.0f, false));
        list.tracks.add(new StudioTrack.Melody(melodyName, 1.0f, false, melody, instrument));
        return list;
    }

    static TrackList startingWith(String melodyName, MidiWindow melody) {
        return startingWith(melodyName, melody, null);
    }

    List<StudioTrack> tracks() {
        return List.copyOf(tracks);
    }

    int size() {
        return tracks.size();
    }

    StudioTrack get(int index) {
        return tracks.get(index);
    }

    int selectedIndex() {
        return selected;
    }

    StudioTrack selected() {
        return tracks.get(selected);
    }

    void select(int index) {
        checkIndex(index);
        selected = index;
    }

    /** The first melody track, which is the one a jam config describes, if any is left. */
    StudioTrack.Melody firstMelody() {
        for (StudioTrack track : tracks) {
            if (track instanceof StudioTrack.Melody melody) {
                return melody;
            }
        }
        return null;
    }

    /** Adds a track at the end and selects it. */
    void add(StudioTrack.Melody track) {
        tracks.add(track);
        selected = tracks.size() - 1;
    }

    boolean canRemove(int index) {
        return index >= 0 && index < tracks.size() && !(tracks.get(index) instanceof StudioTrack.Drums);
    }

    /** Takes a melody track out; the selection moves to the one that took its place, or the one above. */
    void remove(int index) {
        if (!canRemove(index)) {
            throw new IllegalArgumentException("Track " + index + " cannot be removed");
        }
        tracks.remove(index);
        if (selected > index || selected == tracks.size()) {
            selected--;
        }
    }

    boolean canMove(int index, int by) {
        int target = index + by;
        return index >= 0 && index < tracks.size() && target >= 0 && target < tracks.size();
    }

    /** Moves a track up (negative) or down (positive) the list; a selected track stays selected. */
    void move(int index, int by) {
        if (!canMove(index, by)) {
            throw new IllegalArgumentException("Track " + index + " cannot move by " + by);
        }
        StudioTrack track = tracks.remove(index);
        tracks.add(index + by, track);
        if (selected == index) {
            selected = index + by;
        } else if (index < selected && selected <= index + by) {
            selected--;
        } else if (index + by <= selected && selected < index) {
            selected++;
        }
    }

    void replace(int index, StudioTrack track) {
        checkIndex(index);
        if (track.getClass() != tracks.get(index).getClass()) {
            throw new IllegalArgumentException("A track keeps its kind");
        }
        tracks.set(index, track);
    }

    /**
     * The jam as the engine plays it: a track for each of the panel's, in its order, every one of
     * them as long as the loop. The drum track plays the grid's notes, a melody track its window.
     */
    Song song(double bpm, int beatsPerBar, double lengthBeats, List<Note> drumNotes, Windows windows) {
        List<Track> songTracks = new ArrayList<>();
        for (StudioTrack track : tracks) {
            List<Note> notes = switch (track) {
                case StudioTrack.Drums drums -> drumNotes;
                case StudioTrack.Melody melody -> windows.notes(melody.source(), lengthBeats);
            };
            songTracks.add(new MelodyTrack(notes, lengthBeats, track.audibleGain()));
        }
        return new Song(bpm, beatsPerBar, songTracks);
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= tracks.size()) {
            throw new IndexOutOfBoundsException("No track " + index + " of " + tracks.size());
        }
    }
}

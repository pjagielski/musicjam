package pl.livecoding.musicjam.studio;

import pl.livecoding.musicjam.model.Drum;
import pl.livecoding.musicjam.model.MelodyTrack;
import pl.livecoding.musicjam.model.Note;
import pl.livecoding.musicjam.model.Song;
import pl.livecoding.musicjam.model.Voice;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackListTest {

    private static final MidiWindow LEAD = new MidiWindow(Path.of("lead.mid"), 1, 4);
    private static final MidiWindow BASS = new MidiWindow(Path.of("bass.mid"), 2, 0);
    private static final List<Note> DRUMS = List.of(new Note(0, Drum.KICK, 0.25, 1.0f));

    /** Every window's notes: one note, pitched by the track it came from, so they can be told apart. */
    private static final TrackList.Windows WINDOWS = (window, lengthBeats) ->
            List.of(new Note(0, new Voice.Pitch(60 + window.trackIndex()), lengthBeats, 0.9f));

    @Test
    void aJamConfigMakesTheSameSongAsTheStudioAlwaysDid() {
        TrackList tracks = TrackList.startingWith("Lead", LEAD);

        Song song = tracks.song(128, 4, 32, DRUMS, WINDOWS);

        assertEquals(new Song(128, 4, List.of(
                new MelodyTrack(DRUMS, 32, 1.0f),
                new MelodyTrack(WINDOWS.notes(LEAD, 32), 32, 1.0f))), song);
        assertInstanceOf(StudioTrack.Drums.class, tracks.selected());
    }

    @Test
    void aMutedTrackIsHandedOverSilent() {
        TrackList tracks = TrackList.startingWith("Lead", LEAD);
        tracks.replace(1, tracks.get(1).withGain(0.6f).withMuted(true));
        tracks.replace(0, tracks.get(0).withGain(0.8f));

        Song song = tracks.song(120, 4, 16, DRUMS, WINDOWS);

        assertEquals(0.8f, song.tracks().get(0).gain());
        assertEquals(0.0f, song.tracks().get(1).gain());
        tracks.replace(1, tracks.get(1).withMuted(false));
        assertEquals(0.6f, tracks.song(120, 4, 16, DRUMS, WINDOWS).tracks().get(1).gain());
    }

    @Test
    void everyMelodyTrackPlaysItsOwnWindowForTheLengthOfTheLoop() {
        TrackList tracks = TrackList.startingWith("Lead", LEAD);
        tracks.add(new StudioTrack.Melody("Bass", 0.7f, false, BASS));

        Song song = tracks.song(120, 4, 8, DRUMS, WINDOWS);

        assertEquals(3, song.tracks().size());
        assertEquals(new MelodyTrack(WINDOWS.notes(BASS, 8), 8, 0.7f), song.tracks().get(2));
        assertEquals(2, tracks.selectedIndex(), "a new track is the one selected");
    }

    @Test
    void theDrumTrackCannotBeRemoved() {
        TrackList tracks = TrackList.startingWith("Lead", LEAD);

        assertFalse(tracks.canRemove(0));
        assertThrows(IllegalArgumentException.class, () -> tracks.remove(0));
        assertTrue(tracks.canRemove(1));
        tracks.remove(1);
        assertEquals(1, tracks.size());
        assertEquals(null, tracks.firstMelody());
        assertEquals(1, tracks.song(120, 4, 4, DRUMS, WINDOWS).tracks().size());
    }

    @Test
    void removingATrackSelectsTheOneThatTookItsPlace() {
        TrackList tracks = threeMelodies();
        tracks.select(2);

        tracks.remove(2);
        assertEquals("Pad", tracks.selected().name());

        tracks.remove(tracks.size() - 1);
        assertEquals("Lead", tracks.selected().name(), "or the one above, at the end of the list");
    }

    @Test
    void removingATrackAboveKeepsTheSameOneSelected() {
        TrackList tracks = threeMelodies();
        tracks.select(3);

        tracks.remove(1);

        assertEquals("Pad", tracks.selected().name());
    }

    @Test
    void movingKeepsTheSelectionOnTheSameTrack() {
        TrackList tracks = threeMelodies();
        tracks.select(2);

        tracks.move(2, -2);
        assertEquals(List.of("Bass", "Drums", "Lead", "Pad"), names(tracks));
        assertEquals("Bass", tracks.selected().name());

        tracks.move(3, -1);
        assertEquals(List.of("Bass", "Drums", "Pad", "Lead"), names(tracks));
        assertEquals("Bass", tracks.selected().name(), "another track moving past it");

        tracks.select(2);
        tracks.move(0, 3);
        assertEquals(List.of("Drums", "Pad", "Lead", "Bass"), names(tracks));
        assertEquals("Pad", tracks.selected().name());
        assertFalse(tracks.canMove(3, 1));
        assertFalse(tracks.canMove(0, -1));
    }

    @Test
    void aTrackKeepsItsKind() {
        TrackList tracks = TrackList.startingWith("Lead", LEAD);

        assertThrows(IllegalArgumentException.class,
                () -> tracks.replace(0, new StudioTrack.Melody("Drums", 1, false, LEAD)));
    }

    private static TrackList threeMelodies() {
        TrackList tracks = TrackList.startingWith("Lead", LEAD);
        tracks.add(new StudioTrack.Melody("Bass", 1, false, BASS));
        tracks.add(new StudioTrack.Melody("Pad", 1, false, LEAD));
        return tracks;
    }

    private static List<String> names(TrackList tracks) {
        return tracks.tracks().stream().map(StudioTrack::name).toList();
    }
}

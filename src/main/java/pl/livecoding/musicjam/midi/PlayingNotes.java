package pl.livecoding.musicjam.midi;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

final class PlayingNotes {
    private final Map<Integer, Deque<PlayingNote>> byPitch = new HashMap<>();

    void start(int pitch, long tick, int velocity) {
        byPitch.computeIfAbsent(pitch, key -> new ArrayDeque<>())
                .addLast(new PlayingNote(tick, velocity));
    }

    Optional<PlayingNote> finish(int pitch) {
        Deque<PlayingNote> playing = byPitch.get(pitch);
        if (playing == null || playing.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(playing.removeFirst());
    }

    record PlayingNote(long tick, int velocity) {
    }
}

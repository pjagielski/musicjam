package pl.livecoding.musicjam.studio;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * What Ctrl+Z walks back through: the states an editor was in before each change, and the ones it
 * was in before each undo. A state is whatever the editor hands over — here the studio's tracks,
 * its grid and its code — and this only stacks them.
 *
 * <p>Only so many are kept: a long session of small edits would otherwise hold every version of
 * every track's notes for as long as the window is open.
 */
final class EditHistory<T> {

    private final Deque<T> past = new ArrayDeque<>();
    private final Deque<T> future = new ArrayDeque<>();
    private final int remembered;

    EditHistory(int remembered) {
        if (remembered < 1) {
            throw new IllegalArgumentException("A history remembers at least one state");
        }
        this.remembered = remembered;
    }

    /**
     * Keeps {@code state} as what the editor was before the change about to be made. A change made
     * by hand is a new path: whatever was undone is no longer ahead to be redone.
     */
    void remember(T state) {
        past.addLast(state);
        if (past.size() > remembered) {
            past.removeFirst();
        }
        future.clear();
    }

    boolean canUndo() {
        return !past.isEmpty();
    }

    boolean canRedo() {
        return !future.isEmpty();
    }

    /**
     * The state to go back to, with {@code current} kept to come forward to; null when there is
     * nothing behind.
     */
    T undo(T current) {
        if (past.isEmpty()) {
            return null;
        }
        future.addLast(current);
        return past.removeLast();
    }

    /** The state to come forward to, with {@code current} kept to go back to; null when there is nothing ahead. */
    T redo(T current) {
        if (future.isEmpty()) {
            return null;
        }
        past.addLast(current);
        return future.removeLast();
    }
}

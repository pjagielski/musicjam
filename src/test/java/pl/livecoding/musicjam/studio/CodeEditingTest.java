package pl.livecoding.musicjam.studio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodeEditingTest {

    @Test
    void commentsOutTheLineTheCaretIsOn() {
        String text = "s(\"bd\")\ns(\"sd\")";

        assertEquals("s(\"bd\")\n// s(\"sd\")", toggle(text, 10, 10));
    }

    @Test
    void commentsOutEverySelectedLineAtTheirCommonIndentation() {
        String text = "stack(\n    s(\"bd\"),\n      s(\"sd\")\n)";

        assertEquals("stack(\n    // s(\"bd\"),\n    //   s(\"sd\")\n)", toggle(text, 8, 25));
    }

    @Test
    void uncommentsWhenEverySelectedLineIsAlreadyCommented() {
        String text = "    // s(\"bd\"),\n    //s(\"sd\")";

        assertEquals("    s(\"bd\"),\n    s(\"sd\")", toggle(text, 0, text.length()));
    }

    @Test
    void aMixOfCommentedAndPlainLinesGetsCommentedOut() {
        assertEquals("// // a\n// b", toggle("// a\nb", 0, 6));
    }

    @Test
    void leavesBlankLinesAndTheLineAfterASelectedNewlineAlone() {
        assertEquals("// a\n\n// b\nc", toggle("a\n\nb\nc", 0, 5));
    }

    @Test
    void theCaretStaysWithTheTextItWasIn() {
        var edit = CodeEditing.toggleComment("s(\"bd\")", 3, 3);

        assertEquals(6, edit.selectionStart());
    }

    private static String toggle(String text, int selectionStart, int selectionEnd) {
        return CodeEditing.toggleComment(text, selectionStart, selectionEnd).applyTo(text);
    }
}

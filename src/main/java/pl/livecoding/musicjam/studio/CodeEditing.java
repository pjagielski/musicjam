package pl.livecoding.musicjam.studio;

import java.util.Arrays;

/** Editor commands for the studio's code area, as plain text in and out so they can be tested. */
final class CodeEditing {

    /** Replace {@code [from, to)} with {@code replacement}, then select {@code [selectionStart, selectionEnd)}. */
    record Edit(int from, int to, String replacement, int selectionStart, int selectionEnd) {

        String applyTo(String text) {
            return text.substring(0, from) + replacement + text.substring(to);
        }
    }

    private CodeEditing() {
    }

    /**
     * Ctrl+/: comments out every line the selection touches, at their common indentation, or
     * uncomments them if they all already are. Blank lines are left alone.
     */
    static Edit toggleComment(String text, int selectionStart, int selectionEnd) {
        int from = text.lastIndexOf('\n', selectionStart - 1) + 1;
        boolean endsAfterNewline = selectionEnd > selectionStart && text.charAt(selectionEnd - 1) == '\n';
        int lastPosition = endsAfterNewline ? selectionEnd - 1 : selectionEnd;
        int to = text.indexOf('\n', lastPosition);
        if (to < 0) {
            to = text.length();
        }

        String[] lines = text.substring(from, to).split("\n", -1);
        boolean anyCode = Arrays.stream(lines).anyMatch(line -> !line.isBlank());
        boolean uncomment = anyCode && Arrays.stream(lines)
                .filter(line -> !line.isBlank())
                .allMatch(line -> line.stripLeading().startsWith("//"));
        int indent = Arrays.stream(lines)
                .filter(line -> !line.isBlank())
                .mapToInt(line -> line.length() - line.stripLeading().length())
                .min()
                .orElse(0);

        StringBuilder replacement = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i > 0) {
                replacement.append('\n');
            }
            if (line.isBlank()) {
                replacement.append(line);
            } else if (uncomment) {
                int slashes = line.indexOf("//");
                int after = slashes + 2;
                if (after < line.length() && line.charAt(after) == ' ') {
                    after++;
                }
                replacement.append(line, 0, slashes).append(line.substring(after));
            } else {
                replacement.append(line, 0, indent).append("// ").append(line.substring(indent));
            }
        }

        String result = replacement.toString();
        if (selectionStart == selectionEnd) {
            int shift = result.length() - (to - from);
            int caret = selectionStart > from + indent ? selectionStart + shift : selectionStart;
            caret = Math.max(from, Math.min(caret, from + result.length()));
            return new Edit(from, to, result, caret, caret);
        }
        return new Edit(from, to, result, from, from + result.length());
    }
}

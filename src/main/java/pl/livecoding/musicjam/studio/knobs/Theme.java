package pl.livecoding.musicjam.studio.knobs;

import javafx.scene.paint.Color;

/**
 * Every colour the panel draws with, in one place, so the same controls render as a dark studio
 * rack or as a light panel that survives a projector in a bright room. Accents are part of the
 * theme too: the orange and blue that glow on black are too pale on white.
 */
record Theme(String name, Color background, Color panel, Color panelBorder, Color canvas, Color grid,
             Color track, Color dialFace, Color dialEdge, Color dialEdgeFocused, Color pointer,
             Color text, Color mutedText, Color buttonFace, Color buttonText, Color accentButton,
             Color osc, Color filter, Color amp, Color fx) {

    enum Accent { OSC, FILTER, AMP, FX }

    static final Theme DARK = new Theme("dark",
            Color.web("#101216"), Color.web("#1a1d22"), Color.web("#2b3035"), Color.web("#16181d"),
            Color.web("#2b3035"), Color.web("#343a40"), Color.web("#212529"), Color.web("#495057"),
            Color.web("#f8f9fa"), Color.web("#f8f9fa"), Color.web("#e9ecef"), Color.web("#868e96"),
            Color.web("#2b3035"), Color.web("#e9ecef"), Color.web("#364fc7"),
            Color.web("#f59f00"), Color.web("#4dabf7"), Color.web("#69db7c"), Color.web("#cc5de8"));

    static final Theme LIGHT = new Theme("light",
            Color.web("#f1f3f5"), Color.web("#ffffff"), Color.web("#dee2e6"), Color.web("#ffffff"),
            Color.web("#e9ecef"), Color.web("#dee2e6"), Color.web("#f8f9fa"), Color.web("#adb5bd"),
            Color.web("#212529"), Color.web("#343a40"), Color.web("#212529"), Color.web("#868e96"),
            Color.web("#e9ecef"), Color.web("#212529"), Color.web("#4263eb"),
            Color.web("#e8590c"), Color.web("#1971c2"), Color.web("#2f9e44"), Color.web("#9c36b5"));

    Color accent(Accent accent) {
        return switch (accent) {
            case OSC -> osc;
            case FILTER -> filter;
            case AMP -> amp;
            case FX -> fx;
        };
    }

    Theme other() {
        return this == DARK ? LIGHT : DARK;
    }

    static String web(Color color) {
        return String.format("#%02x%02x%02x", Math.round(color.getRed() * 255),
                Math.round(color.getGreen() * 255), Math.round(color.getBlue() * 255));
    }
}

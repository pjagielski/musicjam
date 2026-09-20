package pl.livecoding.musicjam.studio.knobs;

import javafx.application.Application;

/** JavaFX on the classpath refuses a main class that extends {@link Application}, so main lives here. */
public final class KnobsLauncher {

    private KnobsLauncher() {
    }

    public static void main(String[] args) {
        Application.launch(SynthPanel.class, args);
    }
}

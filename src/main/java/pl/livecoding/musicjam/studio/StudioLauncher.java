package pl.livecoding.musicjam.studio;

import javafx.application.Application;

/**
 * With JavaFX on the classpath rather than the module path, the Java launcher refuses a main class
 * that extends {@link Application} ("JavaFX runtime components are missing"), so main lives here.
 */
final class StudioLauncher {

    private StudioLauncher() {
    }

    static void main(String[] args) {
        Application.launch(BeatStudio.class, args);
    }
}

package pl.livecoding.musicjam.step5;

import javafx.application.Application;

/**
 * Opens {@link SampleViewer}. A separate main class, because JavaFX refuses to start when the main
 * class itself is an {@code Application} loaded from the class path.
 */
class ShowSamples {

    static void main(String[] args) {
        Application.launch(SampleViewer.class, args);
    }
}

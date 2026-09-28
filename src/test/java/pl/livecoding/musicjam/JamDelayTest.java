package pl.livecoding.musicjam;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JamDelayTest {

    @Test
    void aJamCanAskForItsDelayToBeHeardFromTheStart() throws Exception {
        Path config = Files.createTempFile("jam", ".properties");
        Files.writeString(config, """
                file=src/main/resources/song_insomnia.mid
                synth=anthem
                delay=0.2
                """);

        PhraseRequest request = PhraseRequest.fromPropertiesFile(config);

        assertEquals("anthem", request.synth());
        assertEquals(0.2, request.delayMix(), 1e-9);
        Files.delete(config);
    }

    @Test
    void aJamThatSaysNothingAboutItGetsNone() throws Exception {
        Path config = Files.createTempFile("jam", ".properties");
        Files.writeString(config, "file=src/main/resources/song_insomnia.mid\n");

        assertEquals(0.0, PhraseRequest.fromPropertiesFile(config).delayMix());
        Files.delete(config);
    }

    @Test
    void theInsomniaJamIsAnAnthemWithALittleDelay() throws Exception {
        PhraseRequest request =
                PhraseRequest.fromPropertiesFile(Path.of("src/main/resources/jam-insomnia.properties"));

        assertEquals("anthem", request.synth());
        assertEquals(0.2, request.delayMix(), 1e-9);
        assertEquals("insomnia", request.drums());
    }
}

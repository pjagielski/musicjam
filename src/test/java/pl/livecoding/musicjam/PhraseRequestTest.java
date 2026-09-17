package pl.livecoding.musicjam;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PhraseRequestTest {

    @Test
    void builderAppliesDefaults() {
        PhraseRequest request = PhraseRequest.forFile("song.mid").build();

        assertEquals(Path.of("song.mid"), request.file());
        assertEquals(1, request.trackIndex());
        assertEquals(0, request.startBar());
        assertEquals(2, request.bars());
        assertEquals(4, request.loops());
        assertEquals("anthem", request.synth());
        assertNull(request.midiDevice());
        assertEquals("loop", request.midiSync());
        assertEquals(50, request.midiLatencyMillis());
        assertEquals(1, request.midiChannel());
    }

    @Test
    void builderOverridesEveryField() {
        PhraseRequest request = PhraseRequest.forFile("song.mid")
                .track(3)
                .fromBar(36)
                .bars(4)
                .loops(8)
                .synth("pad")
                .midiDevice("loopMIDI")
                .midiSync("live")
                .midiLatency(12)
                .build();

        assertEquals(3, request.trackIndex());
        assertEquals(36, request.startBar());
        assertEquals(4, request.bars());
        assertEquals(8, request.loops());
        assertEquals("pad", request.synth());
        assertEquals("loopMIDI", request.midiDevice());
        assertEquals("live", request.midiSync());
        assertEquals(12, request.midiLatencyMillis());
    }

    @Test
    void rejectsNonPositiveBarsAndLoops() {
        assertThrows(IllegalArgumentException.class,
                () -> PhraseRequest.forFile("song.mid").bars(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> PhraseRequest.forFile("song.mid").loops(-1).build());
    }

    @Test
    void rejectsUnknownMidiSync() {
        assertThrows(IllegalArgumentException.class,
                () -> PhraseRequest.forFile("song.mid").midiSync("sometimes").build());
    }

    @Test
    void rejectsNegativeMidiLatency() {
        assertThrows(IllegalArgumentException.class,
                () -> PhraseRequest.forFile("song.mid").midiLatency(-1).build());
    }

    @Test
    void loadsFromPropertiesFile(@TempDir Path directory) throws IOException {
        Path config = directory.resolve("jam.properties");
        Files.writeString(config, """
                file=song.mid
                track=3
                fromBar=36
                bars=4
                loops=8
                synth=pad
                midiDevice=loopMIDI
                midiSync=live
                midiLatency=12
                midiChannel=2
                """);

        PhraseRequest request = PhraseRequest.fromPropertiesFile(config);

        assertEquals(Path.of("song.mid"), request.file());
        assertEquals(3, request.trackIndex());
        assertEquals(36, request.startBar());
        assertEquals(4, request.bars());
        assertEquals(8, request.loops());
        assertEquals("pad", request.synth());
        assertEquals("loopMIDI", request.midiDevice());
        assertEquals("live", request.midiSync());
        assertEquals(12, request.midiLatencyMillis());
        assertEquals(2, request.midiChannel());
        assertEquals(1, request.midiChannelIndex());
    }

    @Test
    void propertiesFileOnlyNeedsFile(@TempDir Path directory) throws IOException {
        Path config = directory.resolve("jam.properties");
        Files.writeString(config, "file=song.mid\n");

        PhraseRequest request = PhraseRequest.fromPropertiesFile(config);

        assertEquals(Path.of("song.mid"), request.file());
        assertEquals(1, request.trackIndex());
        assertEquals(2, request.bars());
        assertEquals(4, request.loops());
        assertEquals("anthem", request.synth());
        assertNull(request.midiDevice());
        assertEquals(50, request.midiLatencyMillis());
        assertEquals(1, request.midiChannel());
    }

    @Test
    void propertiesFileRequiresFile(@TempDir Path directory) throws IOException {
        Path config = directory.resolve("jam.properties");
        Files.writeString(config, "track=3\n");

        assertThrows(IllegalArgumentException.class, () -> PhraseRequest.fromPropertiesFile(config));
    }
}

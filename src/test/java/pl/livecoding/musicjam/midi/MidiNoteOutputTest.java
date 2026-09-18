package pl.livecoding.musicjam.midi;

import org.junit.jupiter.api.Test;

import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Synthesizer;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step 3's first exercise, tested without making a sound: a stand-in synthesizer with sixteen
 * stand-in channels, each of which only writes down what it was asked to do. Checking the channel
 * comes finished, so its two tests are green from the start; closing comes finished too, so its test
 * turns green as soon as opening works.
 */
class MidiNoteOutputTest {

    private final Recording[] channelCalls = new Recording[16];
    private final MidiChannel[] channels = new MidiChannel[16];
    private final Recording synthesizerCalls = new Recording(channels);
    private final Synthesizer synthesizer = standIn(Synthesizer.class, synthesizerCalls);

    MidiNoteOutputTest() {
        for (int i = 0; i < channels.length; i++) {
            channelCalls[i] = new Recording(null);
            channels[i] = standIn(MidiChannel.class, channelCalls[i]);
        }
    }

    @Test
    void opensTheSynthesizerAndSelectsTheTracksInstrumentOnItsChannel() throws Exception {
        MidiNoteOutput.open(synthesizer, 1, 45);

        assertEquals("open", synthesizerCalls.calls.getFirst(), "a synthesizer plays nothing until it is open");
        assertEquals(List.of("programChange(45)"), channelCalls[1].calls);
        assertEquals(List.of(), channelCalls[0].calls, "the other channels keep their instruments");
    }

    @Test
    void refusesAChannelTheSynthesizerDoesNotHaveAndClosesItAgain() {
        assertThrows(MidiUnavailableException.class, () -> MidiNoteOutput.channelOf(synthesizer, 16));

        assertEquals("close", synthesizerCalls.calls.getLast(), "a failed open must not keep the sound card");
    }

    @Test
    void refusesAChannelThatIsMissingToo() {
        channels[3] = null;

        assertThrows(MidiUnavailableException.class, () -> MidiNoteOutput.channelOf(synthesizer, 3));

        assertEquals("close", synthesizerCalls.calls.getLast());
    }

    @Test
    void aNoteOnIsPlayedOnTheTracksChannel() throws Exception {
        MidiNoteOutput output = MidiNoteOutput.open(synthesizer, 1, 45);

        output.noteOn(60, 100);

        assertEquals("noteOn(60, 100)", channelCalls[1].calls.getLast());
    }

    @Test
    void aNoteOffReleasesItOnTheSameChannel() throws Exception {
        MidiNoteOutput output = MidiNoteOutput.open(synthesizer, 1, 45);

        output.noteOn(60, 100);
        output.noteOff(60);

        assertEquals("noteOff(60)", channelCalls[1].calls.getLast());
    }

    @Test
    void closingSilencesTheChannelAndLetsGoOfTheSynthesizer() throws Exception {
        MidiNoteOutput output = MidiNoteOutput.open(synthesizer, 1, 45);

        output.close();

        assertTrue(channelCalls[1].calls.contains("allNotesOff"));
        assertEquals("close", synthesizerCalls.calls.getLast());
    }

    private static <T> T standIn(Class<T> type, Recording recording) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, recording));
    }

    /** Writes down every call as "name(arguments)"; a synthesizer's recording also hands out channels. */
    private static final class Recording implements InvocationHandler {
        private final List<String> calls = new ArrayList<>();
        private final MidiChannel[] channels;

        Recording(MidiChannel[] channels) {
            this.channels = channels;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String arguments = args == null ? ""
                    : "(" + String.join(", ", Arrays.stream(args).map(String::valueOf).toList()) + ")";
            calls.add(method.getName() + arguments);
            if (method.getName().equals("getChannels")) {
                return channels;
            }
            Class<?> type = method.getReturnType();
            if (type == int.class) {
                return 0;
            }
            if (type == long.class) {
                return 0L;
            }
            if (type == boolean.class) {
                return false;
            }
            return null;
        }
    }
}

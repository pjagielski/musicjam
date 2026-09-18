package pl.livecoding.musicjam.step4;

import org.junit.jupiter.api.Test;

import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CheckMidiSetupTest {

    @Test
    void selectsTheReceivingEndOfTheCable() throws Exception {
        MidiDevice inputOnly = device("loopMIDI Port", 0);
        MidiDevice output = device("loopMIDI Port", -1);

        assertSame(output, CheckMidiSetup.find("LOOPmidi", List.of(inputOnly, output)));
    }

    @Test
    void sendsAProbeOnTheConfiguredChannelAndStopsIt() throws Exception {
        RecordingReceiver receiver = new RecordingReceiver();

        CheckMidiSetup.probe(receiver, 1, 45, 0);

        assertEquals(List.of(ShortMessage.PROGRAM_CHANGE, ShortMessage.NOTE_ON,
                ShortMessage.NOTE_OFF, ShortMessage.CONTROL_CHANGE),
                receiver.messages.stream().map(ShortMessage::getCommand).toList());
        assertEquals(List.of(1, 1, 1, 1), receiver.messages.stream().map(ShortMessage::getChannel).toList());
        assertEquals(45, receiver.messages.get(0).getData1());
        assertEquals(60, receiver.messages.get(1).getData1());
        assertEquals(100, receiver.messages.get(1).getData2());
        assertEquals(120, receiver.messages.get(3).getData1());
        assertEquals(List.of(-1L, -1L, -1L, -1L), receiver.timestamps);
    }

    private static MidiDevice device(String name, int maxReceivers) {
        MidiDevice.Info info = new MidiDevice.Info(name, "test", "test device", "1") {};
        return (MidiDevice) Proxy.newProxyInstance(MidiDevice.class.getClassLoader(),
                new Class<?>[] {MidiDevice.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getDeviceInfo" -> info;
                    case "getMaxReceivers" -> maxReceivers;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class RecordingReceiver implements Receiver {
        final List<ShortMessage> messages = new ArrayList<>();
        final List<Long> timestamps = new ArrayList<>();

        @Override
        public void send(MidiMessage message, long timestamp) {
            messages.add((ShortMessage) message);
            timestamps.add(timestamp);
        }

        @Override
        public void close() {
        }
    }
}

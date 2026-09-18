package pl.livecoding.musicjam.midi;

import org.junit.jupiter.api.Test;

import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Transmitter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step 4's exercise, tested without a synth or a cable: a stand-in device hands out a receiver that
 * only remembers what it was sent. The first three tests are green from the start, because opening
 * the device and shutting out late note-ons come finished.
 */
class ExternalMidiOutputTest {

    @Test
    void opensTheDeviceAndSelectsTheTracksInstrument() throws Exception {
        StandInDevice device = new StandInDevice("loopMIDI Port", -1);

        try (ExternalMidiOutput ignored = ExternalMidiOutput.open(device, 3, 45)) {
            assertTrue(device.isOpen());
            assertEquals("PROGRAM_CHANGE ch=3 45 0", device.receiver.sent.getLast());
        }
    }

    @Test
    void aNoteLeftHangingByAKilledRunEndsWhenTheDeviceIsOpened() throws Exception {
        StandInDevice device = new StandInDevice("NTS-1 digital kit", -1);

        try (ExternalMidiOutput ignored = ExternalMidiOutput.open(device, 1, 0)) {
            List<String> beforeTheInstrument = device.receiver.sent.subList(0, device.receiver.sent.size() - 1);
            assertTrue(beforeTheInstrument.contains("CONTROL_CHANGE ch=1 123 0"), "All Notes Off");
            assertTrue(beforeTheInstrument.contains("NOTE_OFF ch=1 60 0"), "and a note-off for every pitch");
            assertEquals(129, beforeTheInstrument.size());
        }
    }

    @Test
    void noNoteOnGetsOutOnceClosingHasBegun() throws Exception {
        var recording = new RecordingReceiver();
        var gate = new ExternalMidiOutput.NoteGate(recording);

        gate.send(new ShortMessage(ShortMessage.NOTE_ON, 3, 60, 100), -1);
        gate.shutNoteOns();
        // the scheduler's threads keep running while the JVM shuts down
        gate.send(new ShortMessage(ShortMessage.NOTE_ON, 3, 64, 100), -1);
        gate.send(new ShortMessage(ShortMessage.NOTE_OFF, 3, 60, 0), -1);

        assertEquals(List.of("NOTE_ON ch=3 60 100", "NOTE_OFF ch=3 60 0"), recording.sent);
    }

    @Test
    void aNoteOnGoesOutOnTheOutputsChannelRightAway() throws Exception {
        StandInDevice device = new StandInDevice("loopMIDI Port", -1);

        try (ExternalMidiOutput output = ExternalMidiOutput.open(device, 3, 45)) {
            output.noteOn(60, 100);

            assertEquals("NOTE_ON ch=3 60 100", device.receiver.sent.getLast());
            assertEquals(-1, device.receiver.lastTimeStamp, "-1 asks the device to play it now");
        }
    }

    @Test
    void aNoteOffGoesOutOnTheOutputsChannel() throws Exception {
        StandInDevice device = new StandInDevice("loopMIDI Port", -1);

        try (ExternalMidiOutput output = ExternalMidiOutput.open(device, 3, 45)) {
            output.noteOn(60, 100);
            output.noteOff(60);

            assertEquals("NOTE_OFF ch=3 60 0", device.receiver.sent.getLast());
        }
    }

    @Test
    void closingSilencesTheSynthBeforeLettingGoOfTheDevice() throws Exception {
        StandInDevice device = new StandInDevice("loopMIDI Port", -1);
        ExternalMidiOutput output = ExternalMidiOutput.open(device, 3, 45);
        output.noteOn(60, 100);

        output.close();

        List<String> sent = device.receiver.sent;
        List<String> afterTheNote = sent.subList(sent.lastIndexOf("NOTE_ON ch=3 60 100") + 1, sent.size());
        assertEquals("CONTROL_CHANGE ch=3 120 0", afterTheNote.getFirst(),
                "All Sound Off, so no note keeps ringing on a synth that outlives us");
        assertTrue(afterTheNote.contains("NOTE_OFF ch=3 60 0"), "then a note-off for every pitch");
        assertTrue(device.receiver.closed);
        assertFalse(device.isOpen());
    }

    @Test
    void findsTheDeviceByPartOfItsNameIgnoringCase() throws Exception {
        StandInDevice gervill = new StandInDevice("Gervill", -1);
        StandInDevice cable = new StandInDevice("loopMIDI Port", -1);

        assertSame(cable, ExternalMidiOutput.find("loopmidi", List.of(gervill, cable)));
    }

    @Test
    void skipsTheEndOfACableThatOnlySends() throws Exception {
        // how loopMIDI shows up on Windows: the same name twice, and only one of them takes messages
        StandInDevice readingEnd = new StandInDevice("loopMIDI Port", 0);
        StandInDevice sendingEnd = new StandInDevice("loopMIDI Port", -1);

        assertSame(sendingEnd, ExternalMidiOutput.find("loopMIDI", List.of(readingEnd, sendingEnd)));
    }

    @Test
    void saysWhatItLookedForWhenNoDeviceFits() {
        List<MidiDevice> devices = List.of(new StandInDevice("Gervill", -1));

        MidiUnavailableException failure = assertThrows(MidiUnavailableException.class,
                () -> ExternalMidiOutput.find("Surge", devices));
        assertTrue(failure.getMessage().contains("Surge"), failure.getMessage());
    }

    private static final class StandInDevice implements MidiDevice {
        private final Info info;
        private final int maxReceivers;
        private final RecordingReceiver receiver = new RecordingReceiver();
        private boolean open;

        StandInDevice(String name, int maxReceivers) {
            this.info = new Info(name, "stand-in", "a device for tests", "1") {
            };
            this.maxReceivers = maxReceivers;
        }

        @Override
        public Info getDeviceInfo() {
            return info;
        }

        @Override
        public void open() {
            open = true;
        }

        @Override
        public void close() {
            open = false;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public long getMicrosecondPosition() {
            return -1;
        }

        @Override
        public int getMaxReceivers() {
            return maxReceivers;
        }

        @Override
        public int getMaxTransmitters() {
            return 0;
        }

        @Override
        public Receiver getReceiver() {
            return receiver;
        }

        @Override
        public List<Receiver> getReceivers() {
            return List.of(receiver);
        }

        @Override
        public Transmitter getTransmitter() throws MidiUnavailableException {
            throw new MidiUnavailableException("A stand-in device only receives");
        }

        @Override
        public List<Transmitter> getTransmitters() {
            return List.of();
        }
    }

    private static final class RecordingReceiver implements Receiver {
        private final List<String> sent = new ArrayList<>();
        private long lastTimeStamp;
        private boolean closed;

        @Override
        public void send(MidiMessage message, long timeStamp) {
            sent.add(describe((ShortMessage) message));
            lastTimeStamp = timeStamp;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static String describe(ShortMessage message) {
        String command = switch (message.getCommand()) {
            case ShortMessage.NOTE_ON -> "NOTE_ON";
            case ShortMessage.NOTE_OFF -> "NOTE_OFF";
            case ShortMessage.CONTROL_CHANGE -> "CONTROL_CHANGE";
            case ShortMessage.PROGRAM_CHANGE -> "PROGRAM_CHANGE";
            default -> "command " + message.getCommand();
        };
        return command + " ch=" + message.getChannel() + " " + message.getData1() + " " + message.getData2();
    }
}

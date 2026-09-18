# External MIDI setup (Windows, macOS, Linux)

The route for step 4 is `musicjam → MIDI output → synth → speakers`. MIDI carries notes and
controls, not audio. Start the synth, select an audible patch and check its audio output first.
For a hardware synth connected by USB or a MIDI interface, use its output name from
`listMidiDevices` directly; a virtual cable is only needed to send notes to another app.

## Windows: loopMIDI

1. Install [loopMIDI](https://www.tobias-erichsen.de/software/loopmidi.html), start it and add a
   port, for example `MusicJam`. Leave loopMIDI running: its ports exist only while it runs.
2. Start Surge XT (or another synth) and select `MusicJam` as its MIDI **input**. Set the synth to
   receive on the channel used by the song, or to receive on all channels.
3. Run `./gradlew listMidiDevices` (on Windows, `gradlew.bat listMidiDevices`). Java may list the
   port twice. The usable **output** is the entry marked `takes messages: yes` (or with
   `maxReceivers` other than `0` on branches that print that value).
4. Set `midiDevice=MusicJam` in your jam config, or pass `--midiDevice MusicJam` to the app.

## macOS: IAC Driver

1. Open **Audio MIDI Setup → Window → Show MIDI Studio** and double-click **IAC Driver**.
2. Check **Device is online**, add a bus named `MusicJam` (or rename an existing bus), then click
   **Apply**. [Apple's IAC instructions](https://support.apple.com/guide/audio-midi-setup/ams1013/mac)
   describe the same setup.
3. In the synth, select the `MusicJam` IAC bus as MIDI input and check the receive channel.
4. Run `./gradlew listMidiDevices`; use the exact visible name or a distinctive part of it as
   `midiDevice=MusicJam`. If there are duplicate entries, choose one that takes messages.

## Linux: ALSA Virtual Raw MIDI

1. Load the kernel's virtual MIDI card (you need `alsa-utils` for `aconnect`):

   ```bash
   sudo modprobe snd-virmidi midi_devs=1
   aconnect -i
   aconnect -o
   ```

   `snd-virmidi` exposes a raw MIDI device to Java and corresponding ALSA sequencer ports.
   [Kernel documentation](https://docs.kernel.org/sound/alsa-configuration.html#module-snd-virmidi)
   describes the module and its `midi_devs` option.
2. Start the synth. In `aconnect -i`, find the **Virtual Raw MIDI** source. In `aconnect -o`, find
   the synth's receiving port. Connect the actual IDs printed on your machine, for example:

   ```bash
   aconnect 20:0 128:0
   aconnect -l
   ```

   The first ID sends, the second receives. The numbers are examples and may change after a
   restart. [`aconnect` manual](https://man.archlinux.org/man/aconnect.1.en).
3. Run `./gradlew listMidiDevices` and set `midiDevice=` to the Java-visible **Virtual Raw MIDI**
   output name. Again, it must be an entry that takes messages.

If `modprobe` says the module is missing, install a kernel package that contains `snd-virmidi`
for your distribution/kernel. If the synth only exposes JACK/PipeWire MIDI ports, connect the
Virtual Raw MIDI source to that port in your system's patchbay or bridge it to ALSA MIDI first.

## Check the complete route

Use the same device name and MIDI channel for the probe and the actual player. `midiChannel`
counts from **1 to 16**; it must match the synth's receive channel. For example, on step 4:

```bash
./gradlew checkMidiSetup --args="--config src/main/resources/jam-dre.properties --midiDevice MusicJam --midiChannel 1"
./gradlew playOnSynth --args="--config src/main/resources/jam-dre.properties --midiDevice MusicJam --midiChannel 1"
```

On Linux, replace `MusicJam` with the Virtual Raw MIDI name shown by `listMidiDevices`. A
hardware synth needs its own Java-visible output name. If the probe prints the correct device and
channel but is silent, check the synth's MIDI input, receive channel, patch, volume and audio
output. `--midiDevice Gervill` checks the Java side without a cable or external synth.

On the `final` branch, `checkMidiSetup` and `playOnSynth` are workshop-only commands. For the
Studio, set `midiDevice=MusicJam` (or your actual port name) and `midiChannel=1` in the selected
jam's `.properties`, then run `./gradlew studio`, click **Połącz MIDI** and enable **Melodia przez
MIDI**. The same routing also applies to `BeatApp` (`./gradlew run`).

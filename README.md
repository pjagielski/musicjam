# MusicJam

A small Java 25 workshop about musical time: MIDI, scheduler jitter, and sample-accurate audio.

## Step 1: the black box

`javax.sound.midi`'s `Sequencer` plays a whole MIDI file for you — four lines, no code of ours involved:

```bash
./gradlew sequencerDemo --args="path/to/song.mid"
```

With no arguments at all, it loads `src/main/resources/jam.properties` by default:

```bash
./gradlew sequencerDemo
```

Or point it at a different `.properties` file (only `file` is required):

```bash
./gradlew sequencerDemo --args="--config src/main/resources/jam.properties"
```

```properties
file=src/main/resources/song_still_dre.mid
```

`Config` is what turns those arguments and that file into a path; every later step reuses it and
adds a field.

`src/main/resources/` has four MIDI fixtures to try: `song_shape.mid`, `song_child.mid`, `song_giorgioby.mid`, `song_still_dre.mid`.

Everything the rest of this workshop builds exists to answer one question: what is `Sequencer` actually doing, and can we do better?

plugins {
    application
}

group = "pl.livecoding"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.register<JavaExec>("sequencerDemo") {
    group = "workshop"
    description = "Step 1: hand a MIDI file to javax.sound.midi's Sequencer and let it play."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "pl.livecoding.musicjam.step1.SequencerDemo"
}

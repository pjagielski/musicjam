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

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "pl.livecoding.musicjam.step2.InspectMidi"
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("sequencerDemo") {
    group = "workshop"
    description = "Step 1: hand a MIDI file to javax.sound.midi's Sequencer and let it play."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "pl.livecoding.musicjam.step1.SequencerDemo"
}

tasks.register<JavaExec>("inspectMidi") {
    group = "workshop"
    description = "Step 2: read the same file ourselves and print its tracks and notes."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "pl.livecoding.musicjam.step2.InspectMidi"
}

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

// StructuredTaskScope is still a preview API in Java 25. The toolchain above pins the JDK, which
// matters more than usual here: classes compiled with --enable-preview only run on that exact
// version.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("--enable-preview")
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs("--enable-preview")
}

application {
    mainClass = "pl.livecoding.musicjam.step2.InspectMidi"
    applicationDefaultJvmArgs = listOf("--enable-preview")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-preview")
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

tasks.register<JavaExec>("playNotes") {
    group = "workshop"
    description = "Step 3: schedule the notes we parsed ourselves, one thread per event."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "pl.livecoding.musicjam.step3.PlayNotes"
}

tasks.register<JavaExec>("listMidiDevices") {
    group = "workshop"
    description = "Step 3: list the MIDI devices Java can see - Gervill among them."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "pl.livecoding.musicjam.step3.ListMidiDevices"
}

tasks.register<JavaExec>("playOnSynth") {
    group = "workshop"
    description = "Step 4: play the same notes on an external synth, through a virtual MIDI cable."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "pl.livecoding.musicjam.step4.PlayOnSynth"
}

tasks.register<JavaExec>("midiPanic") {
    group = "workshop"
    description = "Step 4: silence the config's MIDI device on every channel, after a run killed with a note hanging."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "pl.livecoding.musicjam.step4.MidiPanic"
}

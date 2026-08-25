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
    mainClass = "pl.livecoding.musicjam.BeatApp"
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("naivePlayerDemo") {
    group = "workshop"
    description = "Krok 3 warsztatu: parsowanie MIDI + wlasny NaivePlayer, bez bebnow " +
            "(NaivePlayerDemo uzywa MidiFileReader/NaivePlayer, wiec nie da sie go uruchomic " +
            "przez samo 'java NaivePlayerDemo.java' — stad osobne zadanie zamiast source-launch)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "pl.livecoding.musicjam.midi.NaivePlayerDemo"
}

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

// JavaFX nie jest czescia JDK. Artefakty sa per platforma, a Gradle nie rozwiazuje profili
// Mavena, ktorymi openjfx wybiera klasyfikator - stad jawna lista modulow z klasyfikatorem.
val javafxVersion = "21.0.12"
val javafxPlatform = System.getProperty("os.name").lowercase().let { os ->
    val arm = System.getProperty("os.arch").contains("aarch64")
    when {
        os.contains("win") -> "win"
        os.contains("mac") -> if (arm) "mac-aarch64" else "mac"
        else -> if (arm) "linux-aarch64" else "linux"
    }
}

dependencies {
    listOf("base", "graphics", "controls").forEach {
        implementation("org.openjfx:javafx-$it:$javafxVersion:$javafxPlatform")
    }
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

tasks.register<JavaExec>("beat") {
    group = "workshop"
    description = "Beat.java: blokowy renderer perkusji z samples/ plus melodia z pliku MIDI."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "pl.livecoding.musicjam.Beat"
}

tasks.register<JavaExec>("studio") {
    group = "workshop"
    description = "BeatStudio: okno JavaFX do edycji jamu na zywo."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "pl.livecoding.musicjam.studio.StudioLauncher"
}

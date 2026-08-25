package pl.livecoding.musicjam.model;

public enum Drum implements Voice {
    KICK("bd.wav"),
    SNARE("sd.wav"),
    CLOSED_HAT("hh.wav"),
    OPEN_HAT("oh.wav"),
    CLAP("cp.wav");

    private final String sampleFile;

    Drum(String sampleFile) {
        this.sampleFile = sampleFile;
    }

    public String sampleFile() {
        return sampleFile;
    }

    public int gmPercussionNote() {
        return switch (this) {
            case KICK -> 36;
            case SNARE -> 38;
            case CLAP -> 39;
            case CLOSED_HAT -> 42;
            case OPEN_HAT -> 46;
        };
    }
}

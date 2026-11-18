package voidbeat;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** A song available in the song select: audio + metadata + the events charts are built from. */
final class SongEntry {
    final String id;
    final String title;
    final String artist;
    final String tag;          // "ORIGINAL" or "CUSTOM"
    double bpm;
    double beatOffset;         // seconds of the first beat (grid phase)
    double lengthSec;
    double previewSec;

    volatile short[] audio;    // interleaved stereo 16-bit @ 44.1k
    volatile boolean ready;
    volatile String error;
    final List<ChartEvent> events = new ArrayList<>();
    final List<Double> pulses = new ArrayList<>();  // seconds, used for background beat pulse
    java.nio.file.Path sourcePath;   // custom songs
    Arranger.Def def;                // built-in songs
    final Map<Difficulty, Chart> charts = new EnumMap<>(Difficulty.class);

    SongEntry(String id, String title, String artist, String tag) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.tag = tag;
    }

    synchronized Chart chart(Difficulty d) {
        return charts.computeIfAbsent(d, k -> ChartGenerator.generate(this, k));
    }
}

package voidbeat;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** User settings and personal best scores, stored in ~/.voidbeat/. */
final class Settings {
    int scrollSpeed = 24;      // 10..40 (osu!mania-like scale)
    int offsetMs = 0;          // + = notes judged later (use if your audio lags)
    boolean hitsounds = true;
    int musicVol = 80;         // 0..100
    int sfxVol = 55;
    boolean showErrorBar = true;
    boolean autoplay = false;
    int lastSong = 0;
    int lastDiff = 1;

    private final Properties scores = new Properties();
    private final Path dir = Paths.get(System.getProperty("user.home"), ".voidbeat");

    Settings() { load(); }

    /** Travel time (ms) for a note from the top of the screen to the receptors. */
    double travelMs() { return 13720.0 / scrollSpeed * 1.25; }

    void load() {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(dir.resolve("settings.properties"))) {
            p.load(in);
            scrollSpeed = clamp(Integer.parseInt(p.getProperty("scrollSpeed", "" + scrollSpeed)), 10, 40);
            offsetMs = clamp(Integer.parseInt(p.getProperty("offsetMs", "" + offsetMs)), -300, 300);
            hitsounds = Boolean.parseBoolean(p.getProperty("hitsounds", "" + hitsounds));
            musicVol = clamp(Integer.parseInt(p.getProperty("musicVol", "" + musicVol)), 0, 100);
            sfxVol = clamp(Integer.parseInt(p.getProperty("sfxVol", "" + sfxVol)), 0, 100);
            showErrorBar = Boolean.parseBoolean(p.getProperty("showErrorBar", "" + showErrorBar));
            lastSong = Integer.parseInt(p.getProperty("lastSong", "0"));
            lastDiff = clamp(Integer.parseInt(p.getProperty("lastDiff", "1")), 0, 3);
        } catch (Exception ignored) { }
        try (InputStream in = Files.newInputStream(dir.resolve("scores.properties"))) {
            scores.load(in);
        } catch (Exception ignored) { }
    }

    void save() {
        try {
            Files.createDirectories(dir);
            Properties p = new Properties();
            p.setProperty("scrollSpeed", "" + scrollSpeed);
            p.setProperty("offsetMs", "" + offsetMs);
            p.setProperty("hitsounds", "" + hitsounds);
            p.setProperty("musicVol", "" + musicVol);
            p.setProperty("sfxVol", "" + sfxVol);
            p.setProperty("showErrorBar", "" + showErrorBar);
            p.setProperty("lastSong", "" + lastSong);
            p.setProperty("lastDiff", "" + lastDiff);
            try (OutputStream out = Files.newOutputStream(dir.resolve("settings.properties"))) {
                p.store(out, "VOIDBEAT settings");
            }
            try (OutputStream out = Files.newOutputStream(dir.resolve("scores.properties"))) {
                scores.store(out, "VOIDBEAT personal bests: score;accuracy;maxCombo;grade;fc");
            }
        } catch (IOException e) {
            System.err.println("[voidbeat] could not save settings: " + e.getMessage());
        }
    }

    record Best(int score, double acc, int combo, String grade, boolean fc) {}

    Best best(String songId, Difficulty d) {
        String v = scores.getProperty(songId + "." + d.name());
        if (v == null) return null;
        try {
            String[] s = v.split(";");
            return new Best(Integer.parseInt(s[0]), Double.parseDouble(s[1]), Integer.parseInt(s[2]), s[3],
                    Boolean.parseBoolean(s[4]));
        } catch (Exception e) {
            return null;
        }
    }

    /** Stores the result if it beats the previous best; returns true if it is a new best. */
    boolean submit(String songId, Difficulty d, Best b) {
        Best old = best(songId, d);
        if (old != null && old.score >= b.score) return false;
        scores.setProperty(songId + "." + d.name(),
                b.score + ";" + b.acc + ";" + b.combo + ";" + b.grade + ";" + b.fc);
        save();
        return true;
    }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}

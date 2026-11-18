package voidbeat;

import java.util.ArrayList;
import java.util.List;

/** A playable note chart for one difficulty. Times are in milliseconds. */
final class Chart {
    static final class Note {
        final int lane;
        final double time;
        double end;          // == time for taps
        final boolean hold;

        // runtime state (reset per play)
        boolean headDone, tailDone, holding, dead;
        Judgement head, tail;

        Note(int lane, double time, double end) {
            this.lane = lane;
            this.time = time;
            this.end = end;
            this.hold = end > time + 1;
        }

        void reset() {
            headDone = tailDone = holding = dead = false;
            head = tail = null;
        }
    }

    final Difficulty difficulty;
    final List<Note> notes = new ArrayList<>();
    double stars;

    Chart(Difficulty d) { this.difficulty = d; }

    int judgementCount() {
        int n = 0;
        for (Note no : notes) n += no.hold ? 2 : 1;
        return n;
    }

    int holdCount() {
        int n = 0;
        for (Note no : notes) if (no.hold) n++;
        return n;
    }

    double lastTime() {
        double t = 0;
        for (Note n : notes) t = Math.max(t, n.end);
        return t;
    }

    void reset() { for (Note n : notes) n.reset(); }

    void computeStars() {
        if (notes.isEmpty()) { stars = 0; return; }
        double first = notes.get(0).time, last = lastTime();
        double len = Math.max(1, (last - first) / 1000.0);
        double avgNps = judgementCount() / len;
        // peak density over a 4 second window
        double peak = 0;
        int j = 0;
        for (int i = 0; i < notes.size(); i++) {
            while (notes.get(i).time - notes.get(j).time > 4000) j++;
            peak = Math.max(peak, (i - j + 1) / 4.0);
        }
        double strict = switch (difficulty) {
            case EASY -> 0.75; case NORMAL -> 0.95; case HARD -> 1.1; case EXPERT -> 1.2;
        };
        stars = Math.round((0.35 + Math.sqrt(avgNps * 0.6 + peak * 0.4) * 1.05 * strict) * 100) / 100.0;
    }
}

package voidbeat;

import java.util.Collections;
import java.util.List;

/** Fires background pulses when the song clock crosses a kick drum. */
final class PulseTracker {
    private SongEntry song;
    private int idx;
    private double last = -1e9;

    /** @return true if a pulse fired this frame */
    boolean update(SongEntry s, double tSec, Background bg) {
        if (s == null || !s.ready) return false;
        List<Double> p = s.pulses;
        if (s != song || tSec < last - 0.05 || tSec > last + 1.0) {
            song = s;
            int i = Collections.binarySearch(p, tSec);
            idx = i >= 0 ? i : -i - 1;
            last = tSec;
            return false;
        }
        boolean fired = false;
        while (idx < p.size() && p.get(idx) <= tSec) {
            if (tSec - p.get(idx) < 0.08) { bg.kick(1.0); fired = true; }
            idx++;
        }
        last = tSec;
        return fired;
    }
}

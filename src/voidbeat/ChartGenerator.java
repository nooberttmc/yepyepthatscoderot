package voidbeat;

import java.util.*;

/**
 * Turns musical events into a 4K chart. Notes are chosen by musical importance (strong beats,
 * melody, kicks/snares first) under a per-difficulty minimum spacing, then lanes are assigned by
 * following the melody's pitch contour, avoiding fast jacks and lanes blocked by hold notes.
 */
final class ChartGenerator {
    private ChartGenerator() {}

    private static final class Slot {
        long idx;
        double t;
        double weight;
        int energy;
        int count;
        boolean kick, snare, crash;
        ChartEvent prim, mel, longest;
    }

    private record Params(double thr, double gapBeats, double gapMs, double holdMinBeats,
                          double holdCapBeats, double jackMs) {}

    private static Params params(Difficulty d) {
        return switch (d) {
            case EASY   -> new Params(0.85, 1.0, 330, 2.0, 3.0, 0);
            case NORMAL -> new Params(0.62, 0.5, 190, 1.0, 4.0, 260);
            case HARD   -> new Params(0.40, 0.25, 108, 0.75, 8.0, 175);
            case EXPERT -> new Params(0.26, 0.25, 80, 0.5, 8.0, 130);
        };
    }

    static Chart generate(SongEntry s, Difficulty d) {
        Chart chart = new Chart(d);
        Params p = params(d);
        double spb = 60.0 / s.bpm;
        double step = spb / 4.0;
        Random rng = new Random(s.id.hashCode() * 31L + d.ordinal());

        // ---- 1. collect events onto a 16th-note grid
        TreeMap<Long, Slot> slots = new TreeMap<>();
        for (ChartEvent ev : s.events) {
            long idx = Math.round((ev.t - s.beatOffset) / step);
            double qt = s.beatOffset + idx * step;
            Slot sl = slots.get(idx);
            if (sl == null) {
                sl = new Slot();
                sl.idx = idx;
                sl.t = Math.abs(qt - ev.t) < 0.04 ? qt : ev.t;
                slots.put(idx, sl);
            }
            sl.count++;
            sl.energy = Math.max(sl.energy, ev.energy);
            if (sl.prim == null || ev.weight > sl.prim.weight) sl.prim = ev;
            if (ev.melodic() && ev.kind != ChartEvent.Kind.BASS && (sl.mel == null || ev.weight > sl.mel.weight)) sl.mel = ev;
            if ((ev.kind == ChartEvent.Kind.LEAD || ev.kind == ChartEvent.Kind.PAD)
                    && (sl.longest == null || ev.dur > sl.longest.dur)) sl.longest = ev;
            switch (ev.kind) {
                case KICK -> sl.kick = true;
                case SNARE -> sl.snare = true;
                case CRASH -> sl.crash = true;
                default -> { }
            }
        }
        for (Slot sl : slots.values()) {
            double w = sl.prim.weight + Math.min(0.3, 0.12 * (sl.count - 1));
            long pos = Math.floorMod(sl.idx, 16);
            if (pos % 4 == 0) w += 0.25;
            else if (pos % 2 == 0) w += 0.08;
            if (sl.mel != null) w += 0.05;
            sl.weight = w;
        }

        // ---- 2. pick slots by importance under spacing constraints
        List<Slot> cand = new ArrayList<>(slots.values());
        cand.sort((a, b) -> a.weight != b.weight ? Double.compare(b.weight, a.weight) : Double.compare(a.t, b.t));
        TreeMap<Double, Slot> chosen = new TreeMap<>();
        for (Slot sl : cand) {
            if (sl.weight < p.thr) continue;
            double gap = Math.max(p.gapBeats * spb, p.gapMs / 1000.0);
            if (sl.energy == 0 && (d == Difficulty.EASY || d == Difficulty.NORMAL)) gap *= 2;
            else if (sl.energy == 0) gap *= 1.5;
            gap -= 0.004;
            Double lo = chosen.floorKey(sl.t), hi = chosen.ceilingKey(sl.t);
            if (lo != null && sl.t - lo < gap) continue;
            if (hi != null && hi - sl.t < gap) continue;
            chosen.put(sl.t, sl);
        }

        // ---- 3. lane assignment, holds and chords in time order
        double[] busyUntil = new double[4];
        double[] lastHit = {-1e9, -1e9, -1e9, -1e9};
        Arrays.fill(busyUntil, -1e9);
        int prevLane = -1, prevPitch = -1;
        double holdEndAll = -1e9;

        for (Slot sl : chosen.values()) {
            double tMs = sl.t * 1000.0;
            long pos = Math.floorMod(sl.idx, 16);
            if (d == Difficulty.EASY && tMs < holdEndAll + 40) continue;
            if (d == Difficulty.NORMAL && tMs < holdEndAll && pos % 4 != 0) continue;

            int lane = pickLane(sl, prevLane, prevPitch, tMs, busyUntil, lastHit, p.jackMs, rng);
            if (lane < 0) continue;

            double end = tMs;
            ChartEvent hs = sl.longest;
            if (hs != null && hs.dur >= p.holdMinBeats * spb - 1e-6) {
                double dur = Math.min(hs.dur, p.holdCapBeats * spb);
                double release = Math.max(spb * 0.25, dur * 0.2);
                double e = (sl.t + dur - release) * 1000.0;
                if (e - tMs >= spb * 500) end = e;
            }
            chart.notes.add(new Chart.Note(lane, tMs, end));
            busyUntil[lane] = end + Math.max(60, step * 1000);
            lastHit[lane] = end;
            if (end > tMs) holdEndAll = Math.max(holdEndAll, end);

            int extra = chordCount(d, sl, pos);
            List<Integer> used = new ArrayList<>();
            used.add(lane);
            for (int k = 0; k < extra; k++) {
                int best = -1;
                double bestScore = -1e9;
                for (int l = 0; l < 4; l++) {
                    if (used.contains(l) || busyUntil[l] > tMs || tMs - lastHit[l] < p.jackMs * 0.6) continue;
                    double sc = 0;
                    for (int u : used) sc += Math.abs(l - u);
                    sc += rng.nextDouble() * 0.5;
                    if (sc > bestScore) { bestScore = sc; best = l; }
                }
                if (best < 0) break;
                used.add(best);
                chart.notes.add(new Chart.Note(best, tMs, tMs));
                busyUntil[best] = tMs + Math.max(60, step * 1000);
                lastHit[best] = tMs;
            }
            prevLane = lane;
            if (sl.mel != null) prevPitch = sl.mel.pitch;
        }
        chart.notes.sort(Comparator.comparingDouble((Chart.Note n) -> n.time).thenComparingInt(n -> n.lane));
        chart.computeStars();
        return chart;
    }

    private static int chordCount(Difficulty d, Slot sl, long pos) {
        boolean drop = sl.energy >= 2;
        return switch (d) {
            case EASY -> 0;
            case NORMAL -> (sl.crash || (drop && sl.kick && pos == 0)) ? 1 : 0;
            case HARD -> sl.crash ? 2 : (drop && sl.kick && pos % 8 == 0) ? 1 : 0;
            case EXPERT -> {
                int c = sl.crash ? 2 : 0;
                if (!sl.crash && drop && sl.kick && pos % 4 == 0) c = 1;
                if (!sl.crash && drop && sl.kick && sl.snare) c = 2;
                if (!sl.crash && drop && pos == 0 && sl.kick) c = 2;
                yield c;
            }
        };
    }

    private static boolean free(int l, double tMs, double[] busy, double[] lastHit, double jackMs) {
        return busy[l] <= tMs && tMs - lastHit[l] >= jackMs;
    }

    private static int pickLane(Slot sl, int prevLane, int prevPitch, double tMs, double[] busy,
                                double[] lastHit, double jackMs, Random rng) {
        int want;
        if (sl.mel != null && prevLane >= 0 && prevPitch >= 0) {
            int dp = sl.mel.pitch - prevPitch;
            if (dp == 0) want = prevLane;
            else {
                int dir = Integer.signum(dp);
                int stepL = Math.abs(dp) >= 7 ? 2 : 1;
                want = prevLane + dir * stepL;
                if (want > 3) want = Math.max(0, prevLane - 1 - rng.nextInt(2));
                if (want < 0) want = Math.min(3, prevLane + 1 + rng.nextInt(2));
            }
        } else if (sl.mel != null) {
            want = rng.nextInt(4);
        } else if (sl.kick && sl.snare) {
            want = 0;
            for (int l = 1; l < 4; l++) if (lastHit[l] < lastHit[want] && l != prevLane) want = l;
        } else if (sl.kick) {
            want = lastHit[0] <= lastHit[3] ? 0 : 3;
            if (rng.nextDouble() < 0.25) want = 3 - want;
        } else if (sl.snare) {
            want = lastHit[1] <= lastHit[2] ? 1 : 2;
            if (rng.nextDouble() < 0.25) want = 3 - want;
        } else {
            want = rng.nextInt(4);
            if (want == prevLane) want = (want + 1 + rng.nextInt(3)) % 4;
        }
        if (free(want, tMs, busy, lastHit, jackMs)) return want;
        int best = -1;
        double bestD = 1e9;
        for (int l = 0; l < 4; l++) {
            if (!free(l, tMs, busy, lastHit, jackMs)) continue;
            double dd = Math.abs(l - want) + rng.nextDouble() * 0.6;
            if (dd < bestD) { bestD = dd; best = l; }
        }
        return best;
    }
}

package voidbeat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/** Holds built-in + custom songs and loads/renders their audio on a background worker. */
final class SongLibrary {
    private final List<SongEntry> builtins = new ArrayList<>();
    private volatile List<SongEntry> songs = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "voidbeat-loader");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final ConcurrentHashMap<SongEntry, Future<?>> jobs = new ConcurrentHashMap<>();

    SongLibrary() {
        for (Arranger.Def d : Arranger.DEFS) builtins.add(Arranger.placeholder(d));
        rescan();
        for (SongEntry e : builtins) request(e);
    }

    List<SongEntry> songs() { return songs; }

    void rescan() {
        List<SongEntry> all = new ArrayList<>(builtins);
        all.addAll(CustomSongs.scan());
        songs = all;
    }

    /** Queue loading of a song if it isn't loaded yet. */
    void request(SongEntry e) {
        if (e.ready || e.error != null) return;
        jobs.computeIfAbsent(e, k -> worker.submit(() -> {
            long t0 = System.nanoTime();
            if (k.def != null) Arranger.render(k.def, k);
            else CustomSongs.load(k);
            if (k.ready) {
                for (Difficulty d : Difficulty.values()) k.chart(d);
                System.out.printf("[voidbeat] loaded %-18s %.0f ms%n", k.title, (System.nanoTime() - t0) / 1e6);
            }
        }));
    }

    /** Blocks until a song is loaded (used by the self-test). */
    void await(SongEntry e) throws Exception {
        request(e);
        Future<?> f = jobs.get(e);
        if (f != null) f.get();
    }
}

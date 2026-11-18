package voidbeat;

/** A musical event (drum hit, melody note...) that the chart generator can turn into notes. */
final class ChartEvent {
    enum Kind { KICK, SNARE, HAT, CRASH, LEAD, ARP, BASS, PAD }

    final Kind kind;
    final double t;       // seconds
    final double dur;     // seconds
    final int pitch;      // midi note or -1
    final double weight;  // importance 0..~1.3
    final int energy;     // 0 = calm, 1 = medium, 2 = drop

    ChartEvent(Kind kind, double t, double dur, int pitch, double weight, int energy) {
        this.kind = kind;
        this.t = t;
        this.dur = dur;
        this.pitch = pitch;
        this.weight = weight;
        this.energy = energy;
    }

    boolean melodic() { return pitch >= 0; }
}

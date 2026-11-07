package io.github.jqssun.airplay.audio;

import java.util.HashSet;
import java.util.Set;

/** Main-thread session ownership. Transport probes never acquire audio ownership. */
public final class AudioSessionGate {
    private final Set<Long> live = new HashSet<>();
    private long newest;
    private long active;
    public void connected(long id) { if (id > 0) live.add(id); }
    public boolean activate(long id) {
        if (!live.contains(id) || id < newest || (id == newest && active == 0)) return false;
        newest = id;
        active = id;
        return true;
    }
    public boolean accepts(long id) { return id > 0 && id == active && live.contains(id); }
    public boolean mayActivate(long id) { return live.contains(id) && id > newest; }
    public boolean closed(long id) {
        live.remove(id);
        if (id != active) return false;
        active = 0;
        return true;
    }
    public long activeId() { return active; }
    public long newestId() { return newest; }
    public void reset() { live.clear(); active = 0; }
}

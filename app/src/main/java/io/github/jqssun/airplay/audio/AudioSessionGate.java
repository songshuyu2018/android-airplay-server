package io.github.jqssun.airplay.audio;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Main-thread session ownership. Transport probes never acquire audio ownership. */
public final class AudioSessionGate {
    private final Set<Long> live = new HashSet<>();
    private final Map<Long, String> peers = new HashMap<>();
    private long newest;
    private long active;
    public void connected(long id) { if (id > 0) live.add(id); }
    public boolean activate(long id) {
        if (!live.contains(id) || id < newest || (id == newest && active == 0)) return false;
        newest = id;
        active = id;
        peers.keySet().removeIf(key -> key < id);
        return true;
    }
    /** A transport probe may supply an address but cannot change audio ownership. */
    public void peer(long id, String address) {
        if ((accepts(id) || mayActivate(id)) && address != null && !address.isEmpty()) peers.put(id, address);
    }
    public String peerAddress() { return peers.getOrDefault(active, ""); }
    public boolean accepts(long id) { return id > 0 && id == active && live.contains(id); }
    public boolean mayActivate(long id) { return live.contains(id) && id > newest; }
    public boolean closed(long id) {
        live.remove(id);
        peers.remove(id);
        if (id != active) return false;
        active = 0;
        return true;
    }
    public long activeId() { return active; }
    public long newestId() { return newest; }
    public void reset() { live.clear(); peers.clear(); active = 0; }
}

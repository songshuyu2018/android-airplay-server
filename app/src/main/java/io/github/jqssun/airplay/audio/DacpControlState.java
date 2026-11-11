package io.github.jqssun.airplay.audio;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Session-scoped transport intent and retry ownership, independent of Android NSD. */
public final class DacpControlState {
    public enum Outcome { SUCCESS, FAILED, EXPIRED, SUPERSEDED, SESSION_CHANGED, UNAVAILABLE, OVERFLOW }
    public static final class Completion {
        public final long id;
        public final Outcome outcome;
        Completion(long id, Outcome outcome) { this.id = id; this.outcome = outcome; }
    }
    private static final class Command {
        final long id, session, deadline;
        final String path;
        final boolean transport;
        int attempts;
        boolean done;
        Command(long id, long session, String path, long now) {
            this.id = id; this.session = session; this.path = path; deadline = now + 10000;
            transport = path.endsWith("/play") || path.endsWith("/pause");
        }
    }
    public static final class Attempt {
        private final Command command;
        public final long id, session, revision;
        public final String path, host, dacpId, activeRemote;
        public final int port, number;
        Attempt(Command c, long revision, String host, int port, String id, String remote) {
            command = c; this.id = c.id; session = c.session; path = c.path;
            this.revision = revision; this.host = host; this.port = port;
            dacpId = id; activeRemote = remote; number = c.attempts + 1;
        }
    }
    private final ArrayDeque<Command> pending = new ArrayDeque<>();
    private final ArrayDeque<Completion> completed = new ArrayDeque<>();
    private long session, nextId, latestTransport, revision;
    private String dacpId = "", activeRemote = "", host = "";
    private int port;
    private Attempt flight;
    private boolean closed;

    public synchronized long session() { return session; }
    public synchronized boolean canControl() { return !closed && !dacpId.isEmpty() && !activeRemote.isEmpty(); }
    public synchronized boolean ready() { return canControl() && !host.isEmpty() && port > 0; }
    public synchronized boolean update(String id, String remote) {
        if (closed || (dacpId.equals(id) && activeRemote.equals(remote))) return false;
        reset(); dacpId = id; activeRemote = remote;
        return true;
    }
    public synchronized void reset() {
        session++; revision++; host = ""; port = 0; dacpId = ""; activeRemote = "";
        for (Command c : pending) finish(c, Outcome.SESSION_CHANGED);
        pending.clear();
        if (flight != null) finish(flight.command, Outcome.SESSION_CHANGED);
        // Leave the occupied worker slot intact until its old HTTP attempt returns.
    }
    public synchronized void close() { reset(); closed = true; }
    public synchronized boolean endpoint(long owner, String address, int endpointPort) {
        if (owner != session || !canControl() || address == null || address.isEmpty() || endpointPort <= 0 || endpointPort > 65535) return false;
        host = address; port = endpointPort; revision++;
        return true;
    }
    public synchronized long enqueue(String path, long now) {
        expire(now);
        Command c = new Command(++nextId, session, path, now);
        if (!canControl()) { finish(c, Outcome.UNAVAILABLE); return c.id; }
        if (c.transport) {
            latestTransport = c.id;
            Iterator<Command> it = pending.iterator();
            while (it.hasNext()) {
                Command old = it.next();
                if (old.transport) { finish(old, Outcome.SUPERSEDED); it.remove(); }
            }
            if (flight != null && flight.command.transport) finish(flight.command, Outcome.SUPERSEDED);
        }
        if (pending.size() >= 16) finish(pending.removeFirst(), Outcome.OVERFLOW);
        pending.addLast(c);
        return c.id;
    }
    public synchronized void expire(long now) {
        Iterator<Command> it = pending.iterator();
        while (it.hasNext()) {
            Command c = it.next();
            if (now >= c.deadline) { finish(c, Outcome.EXPIRED); it.remove(); }
        }
        if (flight != null && now >= flight.command.deadline) finish(flight.command, Outcome.EXPIRED);
    }
    public synchronized Attempt claim(long now) {
        expire(now);
        if (flight != null || !ready()) return null;
        while (!pending.isEmpty()) {
            Command c = pending.removeFirst();
            if (c.done) continue;
            flight = new Attempt(c, revision, host, port, dacpId, activeRemote);
            return flight;
        }
        return null;
    }
    public synchronized boolean shouldSend(Attempt a, long now) {
        return flight == a && !a.command.done && a.session == session && a.revision == revision && canControl() && now < a.command.deadline;
    }
    public synchronized void complete(Attempt a, boolean success, boolean attempted, long now) {
        if (flight != a) return;
        flight = null;
        Command c = a.command;
        if (a.session != session || closed) { finish(c, Outcome.SESSION_CHANGED); return; }
        if (attempted) c.attempts++;
        if (attempted && !success && a.revision == revision) { host = ""; port = 0; revision++; }
        if (c.done) return;
        if (now >= c.deadline) { finish(c, Outcome.EXPIRED); return; }
        if (success) { finish(c, Outcome.SUCCESS); return; }
        // Only explicit idempotent play/pause can be replayed after an ambiguous HTTP
        // failure. Never blindly repeat next/previous/volume, which may have executed.
        if (c.attempts < 3 && ((!attempted && ready()) || (c.transport && c.id == latestTransport))) pending.addFirst(c);
        else finish(c, Outcome.FAILED);
    }
    private void finish(Command c, Outcome outcome) {
        if (!c.done) { c.done = true; completed.addLast(new Completion(c.id, outcome)); }
    }
    public synchronized List<Completion> completions() {
        List<Completion> result = new ArrayList<>(completed);
        completed.clear(); return result;
    }
}

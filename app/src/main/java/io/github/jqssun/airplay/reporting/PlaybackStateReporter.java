package io.github.jqssun.airplay.reporting;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Serial HTTP reports, independent of UI lifetime and the DACP command queue. */
public final class PlaybackStateReporter implements AutoCloseable {
    public interface Ticket { void cancel(); }
    public interface Scheduler { Ticket schedule(Runnable task, long delayMs); void shutdown(); }
    public interface Transport { int send(CurlRequest request) throws Exception; }
    public static final long INTERVAL_MS = 40000;
    private final Scheduler scheduler;
    private final Transport transport;
    private final LongSupplier clock;
    private final Consumer<String> log;
    private CurlRequest on, off;
    private boolean playing, closed, offConfirmed;
    private long generation;
    private Ticket pending;

    public PlaybackStateReporter(Consumer<String> log) {
        this(new Worker(), PlaybackStateReporter::sendHttp, () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()), log);
    }
    public PlaybackStateReporter(Scheduler scheduler, Transport transport, LongSupplier clock, Consumer<String> log) {
        this.scheduler = scheduler; this.transport = transport; this.clock = clock; this.log = log;
    }
    /** Null requests disable reporting. Enabling/saving synchronizes the current state. */
    public synchronized void configure(CurlRequest on, CurlRequest off) {
        if (closed) return;
        if (on == null || off == null) { on = null; off = null; }
        if (java.util.Objects.equals(this.on, on) && java.util.Objects.equals(this.off, off)) return;
        this.on = on; this.off = off; offConfirmed = false;
        changed();
    }
    public synchronized void setPlaying(boolean value) {
        if (closed || playing == value) return;
        playing = value; offConfirmed = false;
        changed();
    }
    private void changed() {
        generation++;
        if (pending != null) pending.cancel();
        pending = null;
        if (on != null) schedule(generation, playing, 0, clock.getAsLong(), 0);
    }
    private void schedule(long owner, boolean value, int attempt, long due, long delay) {
        pending = scheduler.schedule(() -> report(owner, value, attempt, due), delay);
    }
    private void report(long owner, boolean value, int attempt, long due) {
        CurlRequest request;
        synchronized (this) {
            if (closed || owner != generation || on == null) return;
            pending = null; request = value ? on : off;
        }
        boolean success = deliver(request, value);
        synchronized (this) {
            if (closed || owner != generation || on == null) return;
            if (value) {
                long now = clock.getAsLong();
                long next = due + INTERVAL_MS;
                if (next <= now) next += ((now - next) / INTERVAL_MS + 1) * INTERVAL_MS;
                schedule(owner, true, 0, next, Math.max(0, next - now));
            } else if (success) {
                offConfirmed = true;
            } else if (attempt < 2) {
                schedule(owner, false, attempt + 1, due, attempt == 0 ? 2000 : 5000);
            }
        }
    }
    private boolean deliver(CurlRequest request, boolean value) {
        String state = value ? "playing" : "paused";
        try {
            int code = transport.send(request);
            log.accept("Playback callback state=" + state + " HTTP=" + code);
            return code >= 200 && code < 300;
        } catch (Exception e) {
            // Never log command text, headers, bodies, URLs or exception messages.
            log.accept("Playback callback state=" + state + " failed=" + e.getClass().getSimpleName());
            return false;
        }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; generation++;
        if (pending != null) pending.cancel();
        pending = null;
        // A service stop must leave HA off. Preserve ordering after an in-flight on.
        CurlRequest finalOff = off;
        if (finalOff != null && !offConfirmed) scheduler.schedule(() -> deliver(finalOff, false), 0);
        scheduler.shutdown();
    }
    public static int sendHttp(CurlRequest request) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(request.url).openConnection();
        try {
            connection.setConnectTimeout(2000); connection.setReadTimeout(2000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(request.method);
            for (java.util.Map.Entry<String, String> header : request.headers.entrySet())
                connection.setRequestProperty(header.getKey(), header.getValue());
            if (request.body != null) {
                byte[] body = request.body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true); connection.setFixedLengthStreamingMode(body.length);
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(body); }
            }
            return connection.getResponseCode();
        } finally { connection.disconnect(); }
    }
    private static final class Worker implements Scheduler {
        private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "playback-callback"); thread.setDaemon(true); return thread;
        });
        Worker() { executor.setRemoveOnCancelPolicy(true); executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false); }
        public Ticket schedule(Runnable task, long delayMs) {
            java.util.concurrent.ScheduledFuture<?> future = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }
        public void shutdown() { executor.shutdown(); }
    }
}

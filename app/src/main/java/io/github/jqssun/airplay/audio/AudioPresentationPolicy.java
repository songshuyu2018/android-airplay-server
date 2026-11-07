package io.github.jqssun.airplay.audio;

/** Main-thread audio presentation decisions, driven by accepted session audio, not metadata. */
public final class AudioPresentationPolicy {
    public enum Action { NONE, OPEN, CLOSE }
    public static final long IDLE_MS = 30_000;
    private static final long STABLE_PAUSE_MS = 4_000;
    private static final long AUDIO_TIMEOUT_MS = 5_000;
    private long session = -1, lastAudio, stoppedAt;
    private boolean played, playing, closed;

    public Action audio(long id, long now) {
        if (id < session) return Action.NONE;
        boolean newSession = id != session;
        boolean show = newSession || !played || closed || (!playing && now - stoppedAt >= STABLE_PAUSE_MS);
        session = id;
        lastAudio = now;
        played = playing = true;
        closed = false;
        return show ? Action.OPEN : Action.NONE;
    }
    public void stopped(long now) {
        if (!played || !playing) return;
        playing = false;
        stoppedAt = now;
    }
    public Action tick(long now) {
        if (!played) return Action.NONE;
        if (playing && now - lastAudio >= AUDIO_TIMEOUT_MS) stopped(lastAudio);
        if (!playing && !closed && now - stoppedAt >= IDLE_MS) {
            closed = true;
            return Action.CLOSE;
        }
        return Action.NONE;
    }
    public boolean isPlaying() { return playing; }
    public long sessionId() { return session; }
    public void reset() {
        session = -1;
        played = playing = closed = false;
        lastAudio = stoppedAt = 0;
    }
}

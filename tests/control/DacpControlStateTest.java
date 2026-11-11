import io.github.jqssun.airplay.audio.DacpControlState;
import io.github.jqssun.airplay.audio.DacpControlState.Attempt;
import io.github.jqssun.airplay.audio.DacpControlState.Completion;
import io.github.jqssun.airplay.audio.DacpControlState.Outcome;
import java.net.URL;
import java.util.List;

public final class DacpControlStateTest {
    static final String PAUSE = "/ctrl-int/1/pause", PLAY = "/ctrl-int/1/play";
    static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    static DacpControlState session() {
        DacpControlState s = new DacpControlState(); s.update("sender", "token"); return s;
    }
    static void resolve(DacpControlState s, int port) { check(s.endpoint(s.session(), "127.0.0.1", port)); }
    static void outcome(DacpControlState s, long id, Outcome expected) {
        List<Completion> done = s.completions();
        check(done.size() == 1 && done.get(0).id == id && done.get(0).outcome == expected);
    }
    public static void main(String[] args) throws Exception {
        // Endpoint loss is recoverable; capability stays available to UI and Bluetooth.
        {
            DacpControlState s = session();
            check(s.canControl() && !s.ready());
            long pause = s.enqueue(PAUSE, 0);
            check(s.claim(10) == null && s.completions().isEmpty());
            resolve(s, 1000);
            Attempt first = s.claim(100);
            check(first.id == pause && first.port == 1000 && s.shouldSend(first, 100));
            s.complete(first, false, true, 200);
            check(!s.ready() && s.canControl() && s.completions().isEmpty());
            resolve(s, 2000);
            Attempt retry = s.claim(300);
            check(retry.id == pause && retry.port == 2000 && retry.number == 2);
            s.complete(retry, true, true, 400);
            outcome(s, pause, Outcome.SUCCESS);
        }
        // Latest explicit transport intent replaces an unresolved older pause.
        {
            DacpControlState s = session();
            long pause = s.enqueue(PAUSE, 0), play = s.enqueue(PLAY, 20);
            outcome(s, pause, Outcome.SUPERSEDED);
            resolve(s, 1000);
            check(s.claim(30).id == play);
        }
        // An old in-flight pause cannot be requeued after a newer play.
        {
            DacpControlState s = session(); resolve(s, 1000);
            long pause = s.enqueue(PAUSE, 0); Attempt old = s.claim(1);
            long play = s.enqueue(PLAY, 2);
            outcome(s, pause, Outcome.SUPERSEDED);
            check(!s.shouldSend(old, 3));
            s.complete(old, false, true, 4);
            resolve(s, 2000);
            Attempt next = s.claim(5); check(next.id == play);
            s.complete(next, true, true, 6);
            outcome(s, play, Outcome.SUCCESS); check(s.claim(7) == null);
        }
        // Old session HTTP/NSD callbacks never erase a replacement endpoint or intent.
        {
            DacpControlState s = session(); resolve(s, 1000);
            long oldId = s.enqueue(PAUSE, 0); Attempt old = s.claim(1); long oldSession = s.session();
            s.update("new-sender", "new-token");
            outcome(s, oldId, Outcome.SESSION_CHANGED);
            resolve(s, 3000);
            check(!s.endpoint(oldSession, "127.0.0.2", 9999));
            long current = s.enqueue(PLAY, 2);
            s.complete(old, false, true, 3);
            check(s.ready()); Attempt a = s.claim(4);
            check(a.id == current && a.port == 3000 && a.activeRemote.equals("new-token"));
        }
        // A failed old endpoint does not clear one resolved while HTTP was in flight.
        {
            DacpControlState s = session(); resolve(s, 1000);
            s.enqueue(PAUSE, 0); Attempt old = s.claim(1); resolve(s, 2000);
            s.complete(old, false, true, 2);
            check(s.ready()); check(s.claim(3).port == 2000);
        }
        // A queued worker snapshot superseded by a fresh resolve is safely re-claimed.
        {
            DacpControlState s = session(); resolve(s, 1000);
            long id = s.enqueue("/ctrl-int/1/nextitem", 0); Attempt old = s.claim(1);
            resolve(s, 2000); check(!s.shouldSend(old, 2));
            s.complete(old, false, false, 3);
            check(s.claim(4).id == id);
        }
        // Explicit commands retry at most three times, then complete with an error.
        {
            DacpControlState s = session(); long id = s.enqueue(PAUSE, 0);
            for (int i = 0; i < 3; ++i) {
                resolve(s, 1000+i); Attempt a = s.claim(i*100+1);
                check(a != null); s.complete(a, false, true, i*100+2);
            }
            outcome(s, id, Outcome.FAILED);
            resolve(s, 2000); check(s.claim(500) == null);
        }
        // No permanent failure counter: a later user request can recover normally.
        {
            DacpControlState s = session(); long id = s.enqueue(PAUSE, 0);
            s.expire(10000); outcome(s, id, Outcome.EXPIRED);
            long current = s.enqueue(PAUSE, 20000); resolve(s, 1000);
            Attempt a = s.claim(20001); s.complete(a, true, true, 20002);
            outcome(s, current, Outcome.SUCCESS);
        }
        // An ambiguous skip failure is not replayed, and does not discard the next intent.
        {
            DacpControlState s = session(); resolve(s, 1000);
            long skip = s.enqueue("/ctrl-int/1/nextitem", 0); Attempt a = s.claim(1);
            long pause = s.enqueue(PAUSE, 2);
            s.complete(a, false, true, 3); outcome(s, skip, Outcome.FAILED);
            resolve(s, 2000); check(s.claim(4).id == pause);
        }
        // Expired/disconnected/released commands finish, never hang as pending futures.
        {
            DacpControlState s = session(); resolve(s, 1000);
            long id = s.enqueue(PAUSE, 0); Attempt a = s.claim(1);
            s.expire(10001); outcome(s, id, Outcome.EXPIRED);
            check(!s.shouldSend(a, 10002)); s.complete(a, false, false, 10003);
            check(s.completions().isEmpty()); s.reset();
            id = s.enqueue(PLAY, 10004); outcome(s, id, Outcome.UNAVAILABLE);
            check(!s.canControl());
            s.update("sender", "token"); id = s.enqueue(PAUSE, 10005); s.close();
            outcome(s, id, Outcome.SESSION_CHANGED); check(!s.canControl());
        }
        check(new URL("http", "::1", 1234, PAUSE).toExternalForm().equals("http://[::1]:1234" + PAUSE));
        check(new URL("http", "fe80::1%wlan0", 1234, PAUSE).getAuthority().startsWith("[fe80::1%wlan0]"));
        System.out.println("DACP control recovery tests passed");
    }
}

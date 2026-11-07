import io.github.jqssun.airplay.audio.AudioPresentationPolicy;
import static io.github.jqssun.airplay.audio.AudioPresentationPolicy.Action.*;

public class AudioPresentationPolicyTest {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        AudioPresentationPolicy p = new AudioPresentationPolicy();
        check(p.tick(100_000) == NONE); // Idle manual home does not auto-close.
        check(p.audio(1, 1000) == OPEN);
        for (long t=1500; t<=10_000; t+=500) check(p.audio(1,t)==NONE);
        p.stopped(10_000);
        p.stopped(20_000); // Duplicate pause/teardown cannot extend the deadline.
        check(p.tick(39_999)==NONE);
        check(p.tick(40_000)==CLOSE);
        check(p.tick(40_500)==NONE);
        check(p.audio(1,41_000)==OPEN);
        check(p.tick(41_500)==NONE);

        p.reset(); check(p.audio(2,1000)==OPEN);
        p.stopped(2000); check(p.audio(2,4500)==NONE); // Seek/brief FLUSH.
        for(long t=5000;t<40_000;t+=500){check(p.audio(2,t)==NONE);check(p.tick(t)==NONE);}
        p.stopped(40_000);
        check(p.audio(2,69_999)==OPEN); // Resume cancels old 30s expiry.
        check(p.tick(70_000)==NONE);
        check(p.audio(3,70_001)==OPEN); // New sender owns presentation.
        check(p.audio(2,99_999)==NONE); // Old RTP cannot keep the UI alive.
        check(p.tick(100_000)==NONE);
        check(p.tick(100_001)==CLOSE);

        p.reset(); check(p.audio(4,1000)==OPEN);
        check(p.tick(5999)==NONE && p.isPlaying());
        check(p.tick(6000)==NONE && !p.isPlaying()); // No TEARDOWN / network loss.
        check(p.tick(30_999)==NONE); check(p.tick(31_000)==CLOSE);
        p.reset(); check(p.tick(500_000)==NONE);
        System.out.println("PASS: initial idle, single open, exact timeout, seek, resume, takeover, stale RTP, lost connection");
    }
}

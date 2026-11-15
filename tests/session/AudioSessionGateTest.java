import io.github.jqssun.airplay.audio.AudioSessionGate;
public class AudioSessionGateTest {
 static void check(boolean v) { if (!v) throw new AssertionError(); }
 public static void main(String[] args) {
  for(boolean closeFirst:new boolean[]{false,true}) {
   AudioSessionGate g=new AudioSessionGate();
   g.connected(10);check(g.activate(10));g.connected(11);
   if(closeFirst) check(g.closed(10));
   check(g.activate(11));
   if(!closeFirst) check(!g.closed(10));
   for(int i=0;i<1000;i++){check(!g.accepts(10));check(!g.activate(10));check(g.accepts(11));}
   g.connected(12);check(!g.accepts(12));check(!g.closed(12));check(g.accepts(11));
   check(g.closed(11));check(!g.activate(11));check(!g.accepts(11));
   g.connected(13);check(g.activate(13));g.reset();check(!g.accepts(13));check(!g.activate(13));
   g.connected(14);check(g.activate(14));
  }
  AudioSessionGate g=new AudioSessionGate();
  g.connected(20);g.peer(20,"192.168.1.20");check(g.peerAddress().isEmpty());
  check(g.activate(20));check(g.peerAddress().equals("192.168.1.20"));
  g.connected(21);g.peer(21,"192.168.1.21");
  check(g.peerAddress().equals("192.168.1.20")); // probes do not acquire ownership
  check(g.activate(21));check(g.peerAddress().equals("192.168.1.21"));
  g.peer(20,"wrong-old-peer");check(!g.closed(20));
  check(g.peerAddress().equals("192.168.1.21"));
  check(g.closed(21));g.peer(21,"late-closed-peer");check(g.peerAddress().isEmpty());
  g.connected(22);g.peer(22,"192.168.1.22");g.activate(22);g.reset();
  check(g.peerAddress().isEmpty());g.connected(23);g.activate(23);check(g.peerAddress().isEmpty());
  System.out.println("PASS: takeover order, stale events, probes, teardown, restart");
 }
}

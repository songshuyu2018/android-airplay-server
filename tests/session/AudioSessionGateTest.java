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
  System.out.println("PASS: takeover order, stale events, probes, teardown, restart");
 }
}
